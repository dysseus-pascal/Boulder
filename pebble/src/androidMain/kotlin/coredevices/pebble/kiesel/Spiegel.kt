package coredevices.pebble.kiesel

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

    /** Wie oft ein voruebergehender Fehler (Netz, Server ab) sofort wiederholt wird; danach wartet es auf den Takt. */
    internal const val VERSUCHE = 3

    private val logger = Logger.withTag("Spiegel")
    private val KARTE = MapSerializer(String.serializer(), String.serializer())

    // Ein Senden zur Zeit: Takt, Anstoss und Knopf rechneten sonst dieselben Aenderungen doppelt
    // und schrieben ihren Stand durcheinander.
    private val einzeln = Mutex()

    data class Ziel(val adresse: String, val token: String)

    enum class Art(val schluessel: String, val vorgabe: Boolean) {
        TAGE("tage", true),
        NAECHTE("naechte", true),
        TRAININGS("trainings", true),

        /** Die Rohminuten nur auf Wunsch: gut 130 KB je Tag, und Claude braucht sie selten. */
        MINUTEN("minuten", false),
    }

    enum class Eingabe { ADRESSE, TOKEN }

    data class Status(val versuch: Long, val erfolg: Long, val meldung: String?, val fehler: Boolean)

    sealed interface Ausgang {
        /** Nicht eingerichtet. */
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

    fun status(context: Context): Status = prefs(context).let {
        Status(it.getLong("versuch", 0), it.getLong("erfolg", 0), it.getString("meldung", null), it.getBoolean("fehler", false))
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

    internal fun speichere(context: Context, ziel: Ziel) {
        val alt = ziel(context)
        val e = prefs(context).edit().putString("adresse", ziel.adresse).putString("token", ziel.token)
        // Ein anderer Server kennt nichts von dem, was der alte bekam.
        if (alt?.adresse != ziel.adresse) vergissGesendet(e)
        e.apply()
    }

    /** Vergisst Adresse, Token und Stand und sendet nicht mehr. */
    fun entferne(context: Context) {
        prefs(context).edit().clear().apply()
        WorkManager.getInstance(context).run {
            cancelUniqueWork(TAKT)
            cancelUniqueWork(ANSTOSS)
        }
    }

    private fun vergissGesendet(e: android.content.SharedPreferences.Editor) {
        e.remove(G_TAGE).remove(G_NAECHTE).remove(G_TRAININGS).remove(G_MINUTEN)
    }

    /** Bei jedem Prozessstart: der Takt alle 30 Minuten, nur eingerichtet; KEEP laesst einen geplanten stehen. */
    fun plane(context: Context) {
        val wm = WorkManager.getInstance(context)
        if (ziel(context) == null) {
            wm.cancelUniqueWork(TAKT)
            return
        }
        wm.enqueueUniquePeriodicWork(
            TAKT, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<Arbeit>(Duration.ofMinutes(30)).setConstraints(MIT_NETZ).build(),
        )
    }

    /**
     * Neue Daten von der Uhr: in einer Minute senden. KEEP: ein schon geplanter Anstoss nimmt sie
     * mit. [sofort] fuer eine neue Einrichtung, die man gleich pruefen will.
     */
    fun stosseAn(context: Context, sofort: Boolean = false) {
        if (ziel(context) == null) return
        WorkManager.getInstance(context).enqueueUniqueWork(
            ANSTOSS, if (sofort) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<Arbeit>()
                .setInitialDelay(if (sofort) Duration.ZERO else Duration.ofMinutes(1))
                .setConstraints(MIT_NETZ)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofMinutes(1))
                .build(),
        )
    }

    private val MIT_NETZ = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** [alles]: vergisst vorher, was schon gesendet ist - fuer einen Server, der seine Daten verlor. */
    suspend fun sende(
        context: Context,
        api: HealthDataApi,
        libPebble: LibPebble,
        alles: Boolean = false,
        post: Spiegelpost = Spiegelpost.standard,
        uhr: () -> Long = { Instant.now().epochSecond },
    ): Ausgang = einzeln.withLock {
        withContext(Dispatchers.IO) {
            val ziel = ziel(context) ?: return@withContext Ausgang.Aus
            if (alles) prefs(context).edit().also(::vergissGesendet).apply()
            prefs(context).edit().putLong("versuch", System.currentTimeMillis()).apply()
            val aus = try {
                sendeAn(context, api, libPebble, ziel, post, uhr())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Eine Ausnahme beim Lesen (Datenbank, Akte) - beim naechsten Mal kann es gehen.
                logger.w(e) { "Senden abgebrochen" }
                Ausgang.Fehler(context.getString(R.string.ks_sv_fehler_lesen, e.message ?: e.javaClass.simpleName), voruebergehend = true)
            }
            melde(context, aus)
            aus
        }
    }

    private suspend fun sendeAn(context: Context, api: HealthDataApi, libPebble: LibPebble, ziel: Ziel, post: Spiegelpost, jetzt: Long): Ausgang {
        val p = prefs(context)
        val zone = ZoneId.systemDefault().id
        val fassung = versuche("Spiegel", "Fassung lesen") { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.orEmpty()

        val alteTage = karte(context, G_TAGE)
        val tageJetzt = if (an(context, Art.TAGE)) {
            Spiegelrechnung.tage(Trenddaten.lies(context, api, merken = false)).also { if (it == null) logger.i { "Akte nicht ganz lesbar, Tage warten" } }
        } else null
        val tage = tageJetzt?.let { Spiegelrechnung.neueTage(it, alteTage) }.orEmpty()

        val alteNaechte = karte(context, G_NAECHTE)
        val naechte = if (an(context, Art.NAECHTE)) neueNaechte(api, alteNaechte, jetzt) else emptyList()

        val alteTrainings = karte(context, G_TRAININGS)
        val trainings = if (an(context, Art.TRAININGS)) neueTrainings(context, alteTrainings) else null

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
                trainings = trainings?.map { it.training }.orEmpty(), stand = stand,
            ),
        )
        if (antwort !is Spiegelpost.Antwort.Angenommen) return fehler(context, antwort)

        // Erst nach der Quittung gilt etwas als gesendet: ging die Lieferung verloren, kommt sie wieder.
        val e = p.edit()
        e.putString(G_TAGE, json(alteTage + tage.associate { it.datum to Spiegelrechnung.abdruck(it.werte) }))
        e.putString(G_NAECHTE, json(alteNaechte + naechte.associate { it.beginn.toString() to Spiegelrechnung.abdruck(it) }))
        if (trainings != null) {
            // Was aus dem Fenster der Akte faellt, kommt nicht wieder; der Rest bleibt, auch was ein
            // Lauf im Hintergrund nicht sah (fremde Trainings ohne Leserecht).
            val grenze = jetzt - (Fenster.SPORT_TAGE + 1) * 86_400L
            val bleiben = alteTrainings.filterValues { (it.substringAfter('@').toLongOrNull() ?: 0L) >= grenze }
            e.putString(G_TRAININGS, json(bleiben + trainings.associate { it.training.kennung to "${it.abdruck}@${it.training.beginn}" }))
        }
        e.apply()

        if (!an(context, Art.MINUTEN)) return Ausgang.Fertig(antwort.quittung)
        val minuten = sendeMinuten(context, api, ziel, post, jetzt, zone, fassung)
        return if (minuten is Ausgang.Fertig) Ausgang.Fertig(antwort.quittung.copy(minuten = minuten.quittung.minuten)) else minuten
    }

    /** Die neuen oder geaenderten Naechte - siehe [Spiegelrechnung.zuPruefen]. */
    private suspend fun neueNaechte(api: HealthDataApi, gesendet: Map<String, String>, jetzt: Long): List<Nacht> {
        val pruefen = Spiegelrechnung.zuPruefen(api.getNaechte(0, jetzt + 86_400L), gesendet, jetzt)
        if (pruefen.isEmpty()) return emptyList()
        val abschnitte = api.getNachtAbschnitte(pruefen.minOf { it.beginnUtc }, pruefen.maxOf { it.beginnUtc } + 1).groupBy { it.beginnUtc }
        return pruefen.map { n -> Spiegelrechnung.nacht(n, abschnitte[n.beginnUtc].orEmpty(), api.getNachtHrv(n.beginnUtc)) }
            .filter { gesendet[it.beginn.toString()] != Spiegelrechnung.abdruck(it) }
    }

    private class NeuesTraining(val training: Training, val abdruck: String)

    /**
     * Die neuen oder geaenderten Trainings; null, wenn die Akte nicht liest. Den Puls liest es nur
     * fuer diese - fuer jedes Training des Vierteljahres waeren es bei jedem Lauf Dutzende Abfragen.
     */
    private suspend fun neueTrainings(context: Context, gesendet: Map<String, String>): List<NeuesTraining>? =
        TrainingsDaten.fuerSpiegel(context)?.mapNotNull { t ->
            val ohnePuls = Training(
                kennung = t.id, sport = context.getString(t.sport.nameId),
                beginn = t.sitzung.startTime.epochSecond, ende = t.sitzung.endTime.epochSecond,
                aktivSekunden = t.sekunden, meter = t.meter, eigen = t.eigen,
            )
            val abdruck = Spiegelrechnung.abdruck(ohnePuls.toString())
            if (gesendet[t.id]?.substringBefore('@') == abdruck) return@mapNotNull null
            val puls = TrainingsDaten.pulsKennzahlen(context, t.sitzung)
            NeuesTraining(ohnePuls.copy(pulsMittel = puls?.first, pulsMax = puls?.second), abdruck)
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
            p.edit().putLong(G_MINUTEN, ende - 1).apply()
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
                p.edit().putLong("erfolg", System.currentTimeMillis()).putBoolean("fehler", false)
                    .putString("meldung", gesendetText(context, aus.quittung)).apply()
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
            val aus = sende(applicationContext, koin.get(), koin.get())
            return if (aus is Ausgang.Fehler && aus.voruebergehend && runAttemptCount < VERSUCHE) Result.retry() else Result.success()
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
            Spiegelpost(HttpClient {
                expectSuccess = false
                // Ein Tag Minuten ueber ein langsames Mobilnetz braucht seine Zeit; haengen soll nichts.
                install(HttpTimeout) {
                    connectTimeoutMillis = 20_000
                    requestTimeoutMillis = 120_000
                }
            })
        }
    }
}
