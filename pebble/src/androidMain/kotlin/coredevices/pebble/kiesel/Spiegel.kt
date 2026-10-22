package coredevices.pebble.kiesel

import CoreAppVersion
import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import boulder.austausch.Lieferung
import boulder.austausch.Nacht
import boulder.austausch.Quittung
import boulder.austausch.Stand
import boulder.austausch.Training
import boulder.austausch.austauschJson
import co.touchlab.kermit.Logger
import coredevices.pebble.R
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.rebble.libpebblecommon.connection.HealthDataApi
import io.rebble.libpebblecommon.connection.KnownPebbleDevice
import io.rebble.libpebblecommon.connection.LibPebble
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import org.koin.mp.KoinPlatform
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException

/**
 * Boulder: der Spiegel auf dem eigenen Server (server/ im Repo, LIESMICH.md dort). Boulder schickt
 * Tage, Naechte, Trainings und auf Wunsch die Minuten; Claude liest sie dort ueber MCP.
 *
 * OHNE ADRESSE UND TOKEN GEHT NICHTS HINAUS. Beide liegen in einer eigenen Datei, die weder das
 * Cloud-Backup noch ein Geraeteumzug mitnimmt (backup_rules.xml): das Sync-Token darf auf dem
 * Server schreiben und gehoert auf dieses Telefon.
 *
 * GESENDET WIRD eine Minute nach jedem Schub der Uhr - ein Schub kommt in mehreren Teilen und soll
 * eine Lieferung werden - und sonst alle 30 Minuten.
 */
internal object Spiegel {
    /** Die Datei der Einstellungen; backup_rules.xml nimmt sie aus (SpiegelGeheimTest). */
    internal const val DATEI = "boulder-server"
    private const val TAKT = "boulder-spiegel"
    private const val ANSTOSS = "boulder-spiegel-anstoss"
    private const val G_TAGE = "gesendet_tage"
    private const val G_NAECHTE = "gesendet_naechte"
    private const val G_TRAININGS = "gesendet_trainings"
    private const val G_MINUTEN = "gesendet_minuten_bis"

    /** Wann zuletzt ein gruendlicher Lauf ([Spiegelrechnung.GRUENDLICH_S]) quittiert wurde, in Sekunden. */
    private const val G_GRUENDLICH = "gruendlich"

    /** Wann zuletzt Tage gerechnet und quittiert wurden ([Spiegelrechnung.TAGE_TAKT_S]), in Sekunden. */
    private const val G_TAGE_ZEIT = "tage_zeit"

    /** Wann zuletzt neue Daten von der Uhr kamen (stosseAn), in Millisekunden wie "versuch". */
    private const val G_ANGESTOSSEN = "angestossen"

    /** Wie oft ein voruebergehender Fehler (Netz, Server ab) sofort wiederholt wird; danach wartet es auf den Takt. */
    internal const val VERSUCHE = 3

    private val logger = Logger.withTag("Spiegel")
    private val KARTE = MapSerializer(String.serializer(), String.serializer())

    // Ein Senden zur Zeit: Takt, Anstoss und Knopf rechneten sonst dieselben Aenderungen doppelt
    // und schrieben ihren Stand durcheinander.
    private val einzeln = Mutex()

    // Adresse und Sendestand aendern sich nur unter dieser Sperre: ein neuer Server vergisst den
    // Stand (speichere, entferne), und ein Senden schreibt ihn nur, solange es noch dessen Adresse
    // gilt (merkeGesendet). Das Senden selbst haelt sie nicht - Speichern wartete sonst auf 90 POSTs.
    private val stand = Any()

    data class Ziel(val adresse: String, val token: String)

    enum class Art(val schluessel: String, val vorgabe: Boolean) {
        TAGE("tage", true),
        NAECHTE("naechte", true),
        TRAININGS("trainings", true),

        /** Die Rohminuten nur auf Wunsch: gut 130 KB je Tag, und Claude braucht sie selten. */
        MINUTEN("minuten", false),
    }

    enum class Eingabe { ADRESSE, TOKEN }

    /** [erfolg]: wann zuletzt quittiert; [fehler]: der Text, wenn das letzte Senden scheiterte. */
    data class Status(val erfolg: Long, val fehler: String?)

    sealed interface Ausgang {
        /** Nicht eingerichtet, oder waehrend des Sendens auf einen anderen Server umgestellt: nichts zu melden. */
        data object Aus : Ausgang

        data class Fertig(val quittung: Quittung) : Ausgang

        data class Fehler(val text: String, val voruebergehend: Boolean) : Ausgang
    }

    private fun prefs(context: Context) = context.getSharedPreferences(DATEI, Context.MODE_PRIVATE)

    fun ziel(context: Context): Ziel? {
        val p = prefs(context)
        return Ziel(p.getString("adresse", null) ?: return null, p.getString("token", null) ?: return null)
    }

    fun an(context: Context, art: Art): Boolean = prefs(context).getBoolean(art.schluessel, art.vorgabe)

    fun setze(context: Context, art: Art, an: Boolean) {
        merke(context, art, an)
        if (an) stosseAn(context)
    }

    internal fun merke(context: Context, art: Art, an: Boolean) = prefs(context).edit().putBoolean(art.schluessel, an).apply()

    // "fehler" bleibt ein Boolean wie in 0.55.0: getString auf einem gespeicherten Boolean wuerfe.
    fun status(context: Context): Status = prefs(context).let {
        Status(it.getLong("erfolg", 0), if (it.getBoolean("fehler", false)) it.getString("meldung", null).orEmpty() else null)
    }

    /**
     * Uebernimmt Adresse und Token und sendet gleich. Ein leeres Token behaelt das gespeicherte:
     * das Feld zeigt es nie wieder an. Der Grund, wenn eine Eingabe nicht taugt.
     */
    fun richteEin(context: Context, adresse: String, token: String): Eingabe? {
        val a = Spiegelrechnung.adresse(adresse) ?: return Eingabe.ADRESSE
        val t = if (token.isBlank()) ziel(context)?.token ?: return Eingabe.TOKEN else Spiegelrechnung.token(token) ?: return Eingabe.TOKEN
        speichere(context, Ziel(a, t))
        plane(context)
        stosseAn(context, sofort = true)
        return null
    }

    internal fun speichere(context: Context, ziel: Ziel) = synchronized(stand) {
        val alt = ziel(context)
        val e = prefs(context).edit().putString("adresse", ziel.adresse).putString("token", ziel.token)
        // Ein anderer Server kennt nichts von dem, was der alte bekam.
        if (alt?.adresse != ziel.adresse) vergissGesendet(e)
        e.apply()
    }

    /** Vergisst Adresse, Token und Stand und sendet nicht mehr. */
    fun entferne(context: Context) {
        synchronized(stand) { prefs(context).edit().clear().apply() }
        arbeit(context)?.run {
            cancelUniqueWork(TAKT)
            cancelUniqueWork(ANSTOSS)
        }
    }

    /**
     * Schreibt den Stand eines Sendens an [ziel] - nur, wenn dessen Adresse noch gilt. Sonst hat
     * inzwischen ein anderer Server den Stand vergessen, und der alte Stand gaebe ihm als gesendet
     * aus, was er nie bekam. false: das Senden ist ueberholt.
     */
    private fun merkeGesendet(context: Context, ziel: Ziel, e: android.content.SharedPreferences.Editor): Boolean = synchronized(stand) {
        if (ziel(context)?.adresse != ziel.adresse) return false
        e.apply()
        true
    }

    private fun vergissGesendet(e: android.content.SharedPreferences.Editor) {
        e.remove(G_TAGE).remove(G_NAECHTE).remove(G_TRAININGS).remove(G_MINUTEN).remove(G_GRUENDLICH).remove(G_TAGE_ZEIT)
    }

    /**
     * Bei jedem Prozessstart: der Takt alle 30 Minuten, nur eingerichtet; KEEP laesst einen geplanten
     * stehen. Ohne Server beruehrt der Start WorkManager nicht - entferne hat den Takt beendet.
     */
    fun plane(context: Context) {
        if (ziel(context) == null) return
        arbeit(context)?.enqueueUniquePeriodicWork(
            TAKT, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<Arbeit>(Duration.ofMinutes(30)).setConstraints(MIT_NETZ).build(),
        )
    }

    /**
     * Neue Daten von der Uhr: in einer Minute senden. KEEP: ein schon geplanter Anstoss nimmt sie
     * mit; kamen sie, waehrend einer lief, holt [Arbeit] sie nach. [sofort] fuer eine neue
     * Einrichtung, die man gleich pruefen will.
     */
    fun stosseAn(context: Context, sofort: Boolean = false) {
        if (ziel(context) == null) return
        prefs(context).edit().putLong(G_ANGESTOSSEN, System.currentTimeMillis()).apply()
        arbeit(context)?.enqueueUniqueWork(
            ANSTOSS, if (sofort) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            anstoss(if (sofort) Duration.ZERO else Duration.ofMinutes(1)),
        )
    }

    private fun anstoss(warten: Duration) = OneTimeWorkRequestBuilder<Arbeit>()
        .setInitialDelay(warten)
        .setConstraints(MIT_NETZ)
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofMinutes(1))
        .addTag(ANSTOSS)
        .build()

    /**
     * Kamen neue Daten, nachdem das letzte Senden zu lesen begann? Dann fehlen sie in dem, was es
     * schickte ("versuch" steht, sobald es an der Reihe ist, vor dem Lesen).
     */
    internal fun nachgestossen(context: Context): Boolean = prefs(context).let { it.getLong(G_ANGESTOSSEN, 0) > it.getLong("versuch", 0) }

    /**
     * WorkManager, oder null, wenn es nicht startet: auf kaputten Hersteller-Builds wirft schon
     * getInstance (NoSuchMethodError, siehe MainApplication). [plane] laeuft in Application.onCreate;
     * ein Wurf dort beendete jeden Start der App, mit eigenem Server oder ohne.
     */
    private fun arbeit(context: Context): WorkManager? = try {
        WorkManager.getInstance(context)
    } catch (t: Throwable) {
        logger.e(t) { "WorkManager startet nicht; gesendet wird nur noch von Hand" }
        null
    }

    private val MIT_NETZ = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /**
     * [alles]: vergisst vorher, was schon gesendet ist - fuer einen Server, der seine Daten verlor.
     * [gruendlich]: vergleicht alles wie der Lauf einmal am Tag ([Spiegelrechnung.GRUENDLICH_S]) -
     * fuer den Knopf, der zeigen soll, was der Server noch nicht hat.
     */
    suspend fun sende(
        context: Context,
        api: HealthDataApi,
        libPebble: LibPebble,
        fassung: CoreAppVersion,
        alles: Boolean = false,
        post: Spiegelpost = Spiegelpost.standard,
        gruendlich: Boolean = false,
        uhr: () -> Long = { Instant.now().epochSecond },
    ): Ausgang = einzeln.withLock {
        withContext(Dispatchers.IO) {
            val ziel = ziel(context) ?: return@withContext Ausgang.Aus
            if (alles) {
                // Gesendete Trainings bleiben verzeichnet, nur ohne Abdruck: sie gehen alle neu, und eines,
                // das es inzwischen nicht mehr gibt, kann der Server trotzdem entfernen.
                val trainings = karte(context, G_TRAININGS).mapValues { (_, t) -> Spiegelrechnung.GesendetesTraining.aus(t).copy(abdruck = "-").toString() }
                prefs(context).edit().also(::vergissGesendet).putString(G_TRAININGS, json(trainings)).apply()
            }
            prefs(context).edit().putLong("versuch", System.currentTimeMillis()).apply()
            val aus = try {
                sendeAn(context, api, libPebble, fassung.version, ziel, post, uhr(), gruendlich)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Eine Ausnahme beim Lesen kommt aus dem Telefon (Datenbank, Akte, ein Fehler hier), nicht
                // aus dem Netz: kein sofortiges Wiederholen. Der naechste Schub oder Takt versucht es wieder.
                logger.w(e) { "Senden abgebrochen" }
                Ausgang.Fehler(context.getString(R.string.ks_sv_fehler_lesen, e.message ?: e.javaClass.simpleName), voruebergehend = false)
            }
            melde(context, aus)
            aus
        }
    }

    private suspend fun sendeAn(
        context: Context, api: HealthDataApi, libPebble: LibPebble, fassung: String, ziel: Ziel, post: Spiegelpost, jetzt: Long, vonHand: Boolean,
    ): Ausgang {
        val p = prefs(context)
        // Nach "alles" und einem neuen Server fehlt G_GRUENDLICH: dann ist der Lauf ebenfalls gruendlich.
        val gruendlich = vonHand || jetzt - p.getLong(G_GRUENDLICH, 0) >= Spiegelrechnung.GRUENDLICH_S
        val zone = ZoneId.systemDefault().id

        val alteTage = karte(context, G_TAGE)
        val tageFaellig = an(context, Art.TAGE) && (gruendlich || jetzt - p.getLong(G_TAGE_ZEIT, 0) >= Spiegelrechnung.TAGE_TAKT_S)
        val tage = if (tageFaellig) {
            Spiegelrechnung.neueTage(Spiegelrechnung.tage(Trenddaten.lies(context, api, merken = false), LocalDate.now()), alteTage)
        } else emptyList()

        val alteNaechte = karte(context, G_NAECHTE)
        val naechte = if (an(context, Art.NAECHTE)) neueNaechte(api, alteNaechte, jetzt, gruendlich) else emptyList()

        val alteTrainings = karte(context, G_TRAININGS)
        val trainingsTage = if (gruendlich) Fenster.SPORT_TAGE else Spiegelrechnung.TRAININGS_KURZ_TAGE
        val trainings = if (an(context, Art.TRAININGS)) trainings(context, alteTrainings, jetzt, trainingsTage, vonHand) else null

        val ziele = Gesundheitsziele.ziele(context)
        val stand = Stand(
            firmware = libPebble.watches.value.filterIsInstance<KnownPebbleDevice>().maxByOrNull { it.lastConnected }?.runningFwVersion,
            ziele = mapOf("schritte" to ziele.schritte, "schlafMinuten" to ziele.schlafMin),
            maxPuls = Herzgrenzen.maximum(context, null).wert,
        )
        // AUCH OHNE NEUES: der Server zeigt Claude, wann Boulder zuletzt kam, und den Stand der Uhr.
        val antwort = post.schicke(
            ziel,
            Lieferung(
                gesendet = jetzt, zone = zone, app = fassung, tage = tage, naechte = naechte,
                trainings = trainings?.neu?.map { it.training }.orEmpty(), stand = stand,
                trainingsEntfernt = trainings?.entfernt.orEmpty(),
            ),
        )
        if (antwort !is Spiegelpost.Antwort.Angenommen) return fehler(context, antwort)

        // Erst nach der Quittung gilt etwas als gesendet: ging die Lieferung verloren, kommt sie wieder.
        val e = p.edit()
        e.putString(G_TAGE, json(alteTage + tage.associate { it.datum to Spiegelrechnung.abdruck(it) }))
        e.putString(G_NAECHTE, json(alteNaechte + naechte.associate { it.beginn.toString() to Spiegelrechnung.abdruck(it) }))
        if (trainings != null) {
            // Was aus dem Fenster der Akte faellt, kommt nicht wieder; der Rest bleibt, auch was ein
            // Lauf im Hintergrund nicht sah (fremde Trainings ohne Leserecht).
            val grenze = jetzt - (Fenster.SPORT_TAGE + 1) * 86_400L
            // Ein Server vor 0.56.0 kennt das Entfernen nicht (keine Zahl in der Quittung): dann bleiben die
            // entfernten vorgemerkt und gehen wieder mit, bis ein neuerer Server sie entfernt.
            val entfernt = if (antwort.quittung.trainingsEntfernt != null) trainings.entfernt.toSet() else emptySet()
            val bleiben = alteTrainings.filter { (kennung, text) ->
                kennung !in entfernt && Spiegelrechnung.GesendetesTraining.aus(text).beginn >= grenze
            }
            e.putString(G_TRAININGS, json(bleiben + trainings.neu.associate { it.training.kennung to it.gesendet.toString() }))
        }
        if (gruendlich) e.putLong(G_GRUENDLICH, jetzt)
        if (tageFaellig) e.putLong(G_TAGE_ZEIT, jetzt)
        if (!merkeGesendet(context, ziel, e)) return Ausgang.Aus

        if (!an(context, Art.MINUTEN)) return Ausgang.Fertig(antwort.quittung)
        val minuten = sendeMinuten(context, api, ziel, post, jetzt, zone, fassung)
        return if (minuten is Ausgang.Fertig) Ausgang.Fertig(antwort.quittung.copy(minuten = minuten.quittung.minuten)) else minuten
    }

    /** Die neuen oder geaenderten Naechte - siehe [Spiegelrechnung.zuPruefen]. */
    private suspend fun neueNaechte(api: HealthDataApi, gesendet: Map<String, String>, jetzt: Long, alle: Boolean): List<Nacht> {
        val pruefen = Spiegelrechnung.zuPruefen(api.getNaechte(0, jetzt + 86_400L), gesendet, jetzt, alle)
        if (pruefen.isEmpty()) return emptyList()
        val abschnitte = api.getNachtAbschnitte(pruefen.minOf { it.beginnUtc }, pruefen.maxOf { it.beginnUtc } + 1).groupBy { it.beginnUtc }
        return pruefen.map { n -> Spiegelrechnung.nacht(n, abschnitte[n.beginnUtc].orEmpty(), api.getNachtHrv(n.beginnUtc)) }
            .filter { gesendet[it.beginn.toString()] != Spiegelrechnung.abdruck(it) }
    }

    private class NeuesTraining(val training: Training, val gesendet: Spiegelrechnung.GesendetesTraining)

    /** Was ein Lauf an Trainings schickt: die neuen oder geaenderten und die Kennungen der verschwundenen. */
    private class Trainingslauf(val neu: List<NeuesTraining>, val entfernt: List<String>)

    /**
     * Die Trainings der letzten [tage] Tage, die neu sind oder sich geaendert haben, und die gesendeten,
     * die es dort nicht mehr gibt ([Spiegelrechnung.entfernteTrainings]); null, wenn die Akte nicht
     * liest. DER PULS GEHOERT ZUM ABDRUCK: traegt Kieselsport eine Kurve nach, die beim ersten Senden
     * fehlte, oder las die Akte sie da nicht, geht das Training noch einmal.
     */
    private suspend fun trainings(context: Context, gesendet: Map<String, String>, jetzt: Long, tage: Long, vonHand: Boolean): Trainingslauf? {
        val gelesen = TrainingsDaten.fuerSpiegel(context, tage, vonHand) ?: return null
        val neu = gelesen.trainings.mapNotNull { t ->
            // Liest die Akte den Puls nicht (Recht verweigert, Rate-Limit): ein neues Training geht ohne ihn
            // und kommt mit ihm wieder, sobald er lesbar ist. Ein schon gesendetes wartet - sonst loeschte
            // ein Fehler seinen Puls auf dem Server.
            val puls = TrainingsDaten.pulsKennzahlen(context, t.sitzung)
            if (puls == null && t.id in gesendet) return@mapNotNull null
            val (mittel, max) = puls ?: (null to null)
            val training = Training(
                kennung = t.id, sport = context.getString(t.sport.nameId),
                beginn = t.sitzung.startTime.epochSecond, ende = t.sitzung.endTime.epochSecond,
                aktivSekunden = t.sekunden, meter = t.meter, pulsMittel = mittel, pulsMax = max, eigen = t.eigen,
            )
            val g = Spiegelrechnung.GesendetesTraining(
                Spiegelrechnung.abdruck(training.toString()), training.beginn, t.sitzung.metadata.dataOrigin.packageName == context.packageName,
            )
            if (gesendet[t.id]?.let { Spiegelrechnung.GesendetesTraining.aus(it).abdruck } == g.abdruck) null else NeuesTraining(training, g)
        }
        // Ein Tag Abstand zum Rand des Fensters: dort haengt es an der Lesart der Akte, ob ein Training noch dazugehoert.
        val entfernt = Spiegelrechnung.entfernteTrainings(gesendet, gelesen.trainings.mapTo(HashSet()) { it.id }, jetzt - (tage - 1) * 86_400L, gelesen.alleHerkuenfte)
        return Trainingslauf(neu, entfernt)
    }

    /**
     * Die Minuten seit dem letzten Senden, ein Tag je Lieferung. Der Stand rueckt nach jeder
     * Quittung vor: bricht ein langer erster Lauf ab (90 Tage), macht der naechste dort weiter.
     */
    private suspend fun sendeMinuten(
        context: Context, api: HealthDataApi, ziel: Ziel, post: Spiegelpost, jetzt: Long, zone: String, fassung: String,
    ): Ausgang {
        val p = prefs(context)
        // DER STAND BLEIBT BEI DER JUENGSTEN MINUTE, DIE SCHON DA IST: die Uhr schickt ihre Minuten
        // 15 bis 50 Minuten spaet, nach einer Zeit ausser Reichweite Stunden spaet, und der naechste
        // Lauf beginnt nur MINUTEN_RUECKBLICK_S vor dem Stand. Und nie nach jetzt: eine Minute mit
        // falscher Uhrzeit aus der Zukunft setzte ihn dorthin, und jede echte laege davor.
        val neueste = api.getLetzteMinuteBis(jetzt) ?: return Ausgang.Fertig(Quittung(0, 0, 0, 0))
        val bis = p.getLong(G_MINUTEN, -1L).takeIf { it >= 0 }
        if (bis != null && neueste <= bis) return Ausgang.Fertig(Quittung(0, 0, 0, 0))
        // Mehr als 90 Tage haelt die Datenbank nicht (Housekeeping), der Server auch nicht.
        var ab = Spiegelrechnung.minutenAb(bis, jetzt - 90 * 86_400L)
        var zahl = 0
        while (ab <= neueste) {
            val ende = minOf(ab + Spiegelrechnung.MINUTEN_JE_LIEFERUNG * 60L, neueste + 1)
            val minuten = api.getHealthDataForRange(ab, ende).map(Spiegelrechnung::minute)
            if (minuten.isNotEmpty()) {
                val a = post.schicke(ziel, Lieferung(gesendet = jetzt, zone = zone, app = fassung, minuten = minuten))
                if (a !is Spiegelpost.Antwort.Angenommen) return fehler(context, a)
                zahl += a.quittung.minuten
            }
            if (!merkeGesendet(context, ziel, p.edit().putLong(G_MINUTEN, ende - 1))) return Ausgang.Aus
            ab = ende
        }
        return Ausgang.Fertig(Quittung(0, 0, 0, zahl))
    }

    private fun fehler(context: Context, a: Spiegelpost.Antwort): Ausgang.Fehler = when (a) {
        is Spiegelpost.Antwort.Unerreichbar -> Ausgang.Fehler(context.getString(R.string.ks_sv_fehler_netz, a.grund), voruebergehend = true)
        is Spiegelpost.Antwort.Abgelehnt -> when (a.status) {
            HttpStatusCode.Unauthorized.value -> Ausgang.Fehler(context.getString(R.string.ks_sv_fehler_token), voruebergehend = false)
            HttpStatusCode.NotFound.value -> Ausgang.Fehler(context.getString(R.string.ks_sv_fehler_adresse), voruebergehend = false)
            0 -> Ausgang.Fehler(context.getString(R.string.ks_sv_fehler_antwort), voruebergehend = false)
            // Cloudflare ohne laufenden Server (502, 530), Ueberlast, Zeitueberschreitung: das geht vorbei.
            in 500..599, 408, 429 -> Ausgang.Fehler(context.getString(R.string.ks_sv_fehler_status, a.status), voruebergehend = true)
            else -> Ausgang.Fehler(context.getString(R.string.ks_sv_fehler_status, a.status), voruebergehend = false)
        }
        is Spiegelpost.Antwort.Angenommen -> error("kein Fehler")
    }

    /**
     * Der Stand fuer die Einstellungen und das Protokoll. Ins Protokoll nur, was sich aendert -
     * ein neuer Fehler und das Ende eines Fehlers: ein Senden alle 15 Minuten fuellte sonst seine
     * 100 Zeilen in einem Tag.
     */
    private fun melde(context: Context, aus: Ausgang) {
        val p = prefs(context)
        val warFehler = p.getBoolean("fehler", false)
        val titel = context.getString(R.string.ks_sv_titel)
        when (aus) {
            Ausgang.Aus -> return
            is Ausgang.Fertig -> {
                p.edit().putLong("erfolg", System.currentTimeMillis()).putBoolean("fehler", false).remove("meldung").apply()
                if (warFehler) KieselVerlauf.merke(context, "$titel: " + context.getString(R.string.ks_sv_wieder))
            }
            is Ausgang.Fehler -> {
                val vorher = p.getString("meldung", null)
                p.edit().putBoolean("fehler", true).putString("meldung", aus.text).apply()
                if (!warFehler || vorher != aus.text) KieselVerlauf.merke(context, "$titel: ${aus.text}")
            }
        }
    }

    fun gesendetText(context: Context, q: Quittung): String =
        context.getString(R.string.ks_sv_gesendet, q.tage, q.naechte, q.trainings, q.minuten)

    private fun karte(context: Context, schluessel: String): Map<String, String> {
        val text = prefs(context).getString(schluessel, null) ?: return emptyMap()
        // Unlesbar: alles dieser Art geht noch einmal - lieber doppelt als eine Luecke.
        return versuche("Spiegel", "Stand $schluessel unlesbar") { austauschJson.decodeFromString(KARTE, text) }.orEmpty()
    }

    private fun json(karte: Map<String, String>): String = austauschJson.encodeToString(KARTE, karte)

    class Arbeit(context: Context, gaben: WorkerParameters) : CoroutineWorker(context, gaben) {
        override suspend fun doWork(): Result {
            val koin = KoinPlatform.getKoin()
            val aus = sende(applicationContext, koin.get(), koin.get(), koin.get())
            val nochmal = aus is Ausgang.Fehler && aus.voruebergehend && runAttemptCount < VERSUCHE
            // Ein Schub, waehrend dieser Anstoss lief: KEEP liess ihn liegen, weil dieser noch lief.
            // APPEND reiht einen weiteren dahinter. Eine Wiederholung liest ohnehin neu.
            // Offen bleiben Millisekunden: ein Schub nach dieser Pruefung, bevor doWork endet, trifft
            // noch auf diesen Anstoss und wartet auf den Takt - spaeter gesendet, nicht verloren.
            if (!nochmal && aus != Ausgang.Aus && ANSTOSS in tags && nachgestossen(applicationContext)) {
                arbeit(applicationContext)?.enqueueUniqueWork(ANSTOSS, ExistingWorkPolicy.APPEND_OR_REPLACE, anstoss(Duration.ofMinutes(1)))
            }
            return if (nochmal) Result.retry() else Result.success()
        }
    }
}

/** Der Weg zum Server; eigens, damit ein Test ihn gegen eine nachgestellte Gegenstelle prueft. */
internal class Spiegelpost(private val client: HttpClient) {
    sealed interface Antwort {
        data class Angenommen(val quittung: Quittung) : Antwort

        /** Der Server lehnte ab: HTTP-[status]; 0 bei einer Antwort, die keine Quittung ist. */
        data class Abgelehnt(val status: Int) : Antwort

        data class Unerreichbar(val grund: String) : Antwort
    }

    suspend fun schicke(ziel: Spiegel.Ziel, l: Lieferung): Antwort = try {
        val r = client.post(ziel.adresse + "/sync") {
            bearerAuth(ziel.token)
            contentType(ContentType.Application.Json)
            setBody(austauschJson.encodeToString(Lieferung.serializer(), l))
        }
        if (r.status != HttpStatusCode.OK) {
            logger.w { "Server lehnt ab: ${r.status}" }
            Antwort.Abgelehnt(r.status.value)
        } else {
            val text = r.bodyAsText()
            versuche("Spiegel", "Keine Quittung") { austauschJson.decodeFromString(Quittung.serializer(), text) }
                ?.let { Antwort.Angenommen(it) } ?: Antwort.Abgelehnt(0)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.w { "Server nicht erreicht: ${e.javaClass.simpleName}: ${e.message}" }
        Antwort.Unerreichbar(e.message ?: e.javaClass.simpleName)
    }

    companion object {
        private val logger = Logger.withTag("Spiegel")

        val standard: Spiegelpost by lazy {
            // Der Client der App, mit eigenen Fristen: ein Tag Minuten ueber ein langsames Mobilnetz
            // braucht seine Zeit; haengen soll nichts. Seine ContentNegotiation laesst den Text durch.
            Spiegelpost(KoinPlatform.getKoin().get<HttpClient>().config {
                expectSuccess = false
                install(HttpTimeout) {
                    connectTimeoutMillis = 20_000
                    requestTimeoutMillis = 120_000
                }
            })
        }
    }
}
