package coredevices.pebble.kiesel.ablauf

import android.content.Context
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.NutritionRecord
import coredevices.pebble.R
import coredevices.pebble.kiesel.Akte
import coredevices.pebble.kiesel.Drinktervall
import coredevices.pebble.kiesel.KieselNachricht
import coredevices.pebble.kiesel.KieselVerlauf
import coredevices.pebble.kiesel.Kieselsport
import coredevices.pebble.kiesel.KieselsportBegleiter
import coredevices.pebble.kiesel.Mithoerer
import coredevices.pebble.kiesel.Pulskurve
import coredevices.pebble.kiesel.SupCycle
import coredevices.pebble.kiesel.Wartend
import io.rebble.libpebblecommon.services.appmessage.AppMessageData
import io.rebble.libpebblecommon.services.appmessage.AppMessageResult
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * "ERST SICHERN, DANN BESTAETIGEN" IM ABLAUF: Nachricht der Uhr hinein, und
 * im Augenblick der Antwort an die Uhr nachsehen, was auf der Platte liegt -
 * mit der Bestaetigung gibt die Uhr ihre Kopie auf.
 *
 * Dazu, was geschieht, wenn das Sichern scheitert (Speicher voll): ein NACK,
 * die Uhr behaelt ihre Kopie - und was ohne Eintrag ist (Zielmeldung,
 * Zustandsmeldung), wird ohne Datei bestaetigt. Und was nach einem Neustart
 * von der Platte kommt: es landet in der Akte, und ein Scheitern steht
 * einmal im Protokoll, nicht bei jedem Start und nicht nie.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BegleiterAblaufTest {
    private lateinit var context: Context
    private lateinit var akte: FakeAkte

    @Before
    fun auf() {
        context = RuntimeEnvironment.getApplication()
        akte = FakeAkte(context.packageName)
        Akte.ersatz = akte
    }

    @After
    fun ab() {
        Akte.ersatz = null
    }

    private fun ablage(uuid: Uuid) = File(context.filesDir, "uhrapp-wartend-$uuid")

    private fun warte(was: String, bedingung: () -> Boolean) = runBlocking {
        withTimeoutOrNull(10_000) { while (!bedingung()) delay(20) }
        assertTrue(bedingung(), was)
    }

    private fun antworten(uhr: FakeUhr) = synchronized(uhr.antworten) { uhr.antworten.toList() }

    private val glasZeit = Instant.now().epochSecond - 600

    private fun glas(id: Int) = AppMessageData(
        id.toUByte(), Drinktervall.UUID,
        mapOf(Drinktervall.DT_DRANK_AT to glasZeit.toInt(), Drinktervall.DT_GLASS_ML to 250, Drinktervall.DT_GLASSES to 8),
    )

    @Test
    fun das_glas_liegt_auf_der_platte_bevor_die_uhr_die_bestaetigung_bekommt() {
        val uhr = FakeUhr(ablage(Drinktervall.UUID))
        val eingang = Channel<AppMessageData>(Channel.UNLIMITED)
        runBlocking { Mithoerer(context, Drinktervall.UUID, geraet(uhr)).start(eingang.consumeAsFlow()) }
        eingang.trySend(glas(5))

        warte("Antwort an die Uhr") { antworten(uhr).isNotEmpty() }
        val (antwort, lag) = antworten(uhr).single()
        assertTrue(antwort is AppMessageResult.ACK, "Antwort $antwort")
        assertEquals(5.toUByte(), antwort.transactionId)
        // In dem Augenblick, in dem die Uhr ihre Kopie aufgibt, liegt das Glas schon hier.
        assertEquals(listOf(glasZeit), lag.map { it.zahlen[Drinktervall.DT_DRANK_AT] })
        // Und danach in der Akte, die Datei wieder weg.
        warte("Glas in der Akte") { akte.saetze<HydrationRecord>().isNotEmpty() }
        assertEquals(250.0, akte.saetze<HydrationRecord>().single().volume.inMilliliters, 0.001)
        warte("Datei verarbeitet") { ablage(Drinktervall.UUID).listFiles().orEmpty().isEmpty() }
    }

    @Test
    fun scheitert_das_sichern_kommt_ein_nack_und_nichts_wird_eingetragen() {
        // Wo der Ordner hin sollte, liegt eine Datei: kein Sichern moeglich, wie bei vollem Speicher.
        ablage(Drinktervall.UUID).writeText("kein Ordner")
        val uhr = FakeUhr(ablage(Drinktervall.UUID))
        val eingang = Channel<AppMessageData>(Channel.UNLIMITED)
        runBlocking { Mithoerer(context, Drinktervall.UUID, geraet(uhr)).start(eingang.consumeAsFlow()) }
        eingang.trySend(glas(7))

        warte("Antwort an die Uhr") { antworten(uhr).isNotEmpty() }
        val antwort = antworten(uhr).single().first
        // Die Uhr behaelt das Glas in ihrer Schlange und schickt es wieder.
        assertTrue(antwort is AppMessageResult.NACK, "Antwort $antwort")
        assertEquals(7.toUByte(), antwort.transactionId)
        runBlocking { delay(500) }
        assertEquals(emptyList(), akte.saetze<HydrationRecord>(), "eingetragen trotz NACK - die Uhr schickt es noch einmal")
    }

    @Test
    fun eine_zielmeldung_braucht_keine_datei_und_wird_bestaetigt() {
        // Auch mit kaputter Ablage: ohne Glas und Kaffee ist nichts zu sichern.
        ablage(Drinktervall.UUID).writeText("kein Ordner")
        val uhr = FakeUhr(ablage(Drinktervall.UUID))
        val eingang = Channel<AppMessageData>(Channel.UNLIMITED)
        runBlocking { Mithoerer(context, Drinktervall.UUID, geraet(uhr)).start(eingang.consumeAsFlow()) }
        eingang.trySend(AppMessageData(9u, Drinktervall.UUID, mapOf(Drinktervall.DT_GLASSES to 11, Drinktervall.DT_GLASS_ML to 300)))

        warte("Antwort an die Uhr") { antworten(uhr).isNotEmpty() }
        assertTrue(antworten(uhr).single().first is AppMessageResult.ACK)
        warte("Ziel gemerkt") { Drinktervall.ziel(context)?.glaeser == 11 }
    }

    /** Ein SupCycle-Stand von heute: Zinc faellig und abgehakt, vor einer Minute (kurz nach Mitternacht: um Mitternacht). */
    private fun supcycleStand(id: Int): AppMessageData {
        val heute = LocalDate.now()
        val haken = maxOf(heute.atStartOfDay(ZoneId.systemDefault()).toEpochSecond(), Instant.now().epochSecond - 60)
        return AppMessageData(
            id.toUByte(), SupCycle.UUID,
            mapOf(
                SupCycle.SC_TODAY to (heute.year * 10000 + heute.monthValue * 100 + heute.dayOfMonth),
                SupCycle.SC_DUE to 1, SupCycle.SC_TAKEN to 1, SupCycle.SC_NAMES to "Zinc",
                SupCycle.SC_TAKEN_AT to SupCycle.hakenzeitenBytes(listOf(haken)),
            ),
        )
    }

    @Test
    fun scheitert_das_sichern_eines_supcycle_stands_kommt_ein_nack() {
        // Wie beim Glas: ohne Datei kein ACK - SupCycle schickt seinen Stand beim naechsten Haken oder Start neu.
        ablage(SupCycle.UUID).writeText("kein Ordner")
        val uhr = FakeUhr(ablage(SupCycle.UUID))
        val eingang = Channel<AppMessageData>(Channel.UNLIMITED)
        runBlocking { Mithoerer(context, SupCycle.UUID, geraet(uhr)).start(eingang.consumeAsFlow()) }
        eingang.trySend(supcycleStand(12))

        warte("Antwort an die Uhr") { antworten(uhr).isNotEmpty() }
        val antwort = antworten(uhr).first().first
        assertTrue(antwort is AppMessageResult.NACK, "Antwort $antwort")
        assertEquals(12.toUByte(), antwort.transactionId)
        runBlocking { delay(500) }
        assertEquals(emptyList(), akte.saetze<NutritionRecord>(), "eingetragen trotz NACK")
    }

    @Test
    fun ein_supcycle_stand_liegt_auf_der_platte_bevor_die_uhr_die_bestaetigung_bekommt() {
        val uhr = FakeUhr(ablage(SupCycle.UUID))
        val eingang = Channel<AppMessageData>(Channel.UNLIMITED)
        runBlocking { Mithoerer(context, SupCycle.UUID, geraet(uhr)).start(eingang.consumeAsFlow()) }
        eingang.trySend(supcycleStand(13))

        warte("Antwort an die Uhr") { antworten(uhr).isNotEmpty() }
        val (antwort, lag) = antworten(uhr).first()
        assertTrue(antwort is AppMessageResult.ACK, "Antwort $antwort")
        assertEquals(listOf("Zinc"), lag.map { it.texte[SupCycle.SC_NAMES] })
        warte("Zinc in der Akte") { akte.saetze<NutritionRecord>().any { it.name == "Zinc" } }
    }

    @Test
    fun ein_glas_dessen_erster_versuch_erst_nach_einem_neustart_kommt_steht_einmal_im_protokoll() {
        // Gesichert und bestaetigt, dann starb die App - die Datei liegt, versucht wurde nichts.
        // Die Akte verweigert das Wasser (Recht fehlt), bei zwei Starts hintereinander.
        assertNotNull(Wartend.lege(context, KieselsportBegleiter.zerlege(glas(21).data), "uhrapp-wartend-${Drinktervall.UUID}"))
        akte.weistAb = { r -> if (r is HydrationRecord) SecurityException("WRITE_HYDRATION fehlt") else null }
        val vorher = KieselVerlauf.alle(context).size
        for (start in 1..2) {
            val versuche = akte.schreibversuche
            runBlocking { Mithoerer(context, Drinktervall.UUID, geraet(FakeUhr(ablage(Drinktervall.UUID)))).start(emptyFlow()) }
            // Bis auch der Vermerk im Namen steht: der Versuch kommt kurz davor, und ein Start dazwischen
            // faende die Datei noch unter dem alten Namen, die der Arbeiter gleich darauf umbenennt.
            warte("Start $start versucht") {
                akte.schreibversuche > versuche && ablage(Drinktervall.UUID).listFiles().orEmpty().all { Wartend.gemeldet(it) }
            }
        }
        runBlocking { delay(300) }
        // Beide Versuche sind Nachtraege - im Protokoll steht das Glas trotzdem einmal.
        assertEquals(1, KieselVerlauf.alle(context).size - vorher, KieselVerlauf.alle(context).toString())
        assertEquals(1, ablage(Drinktervall.UUID).listFiles().orEmpty().size)

        akte.weistAb = { null }
        runBlocking { Mithoerer(context, Drinktervall.UUID, geraet(FakeUhr(ablage(Drinktervall.UUID)))).start(emptyFlow()) }
        warte("Glas in der Akte") { akte.saetze<HydrationRecord>().isNotEmpty() }
        warte("Datei verarbeitet") { ablage(Drinktervall.UUID).listFiles().orEmpty().isEmpty() }
    }

    // --- Kieselsport ---

    private val beginn = Instant.now().epochSecond / 60 * 60 - 7200

    private fun zusammenfassung(id: Int) = AppMessageData(
        id.toUByte(), Kieselsport.UUID,
        mapOf(Kieselsport.SP_ART to 3, Kieselsport.SP_BEGINN to beginn.toInt(), Kieselsport.SP_DAUER to 1800, Kieselsport.SP_ENDE to (beginn + 1800).toInt()),
    )

    private val kieselAblage get() = File(context.filesDir, "kieselsport-wartend")

    /** Ein Stueck der Pulskurve: ab dem [ab]-ten Wert, [n] Werte bei 120 bpm, von [anzahl] insgesamt. */
    private fun kurvenstueck(id: Int, ab: Int, n: Int, anzahl: Int) = AppMessageData(
        id.toUByte(), Kieselsport.UUID,
        mapOf(
            Kieselsport.SP_BEGINN to beginn.toInt(), Kieselsport.SP_KURVE_AB to ab, Kieselsport.SP_KURVE_ANZAHL to anzahl,
            Kieselsport.SP_KURVE to ByteArray(n) { 120.toByte() },
        ),
    )

    private val kurvendatei get() = File(context.filesDir, "puls/$beginn.csv")

    private fun kurvenproben() = akte.saetze<HeartRateRecord>().flatMap { it.samples }.map { it.time.epochSecond }.toSet()

    @Test
    fun ein_kurvenstueck_liegt_auf_der_platte_bevor_die_uhr_die_bestaetigung_bekommt() {
        val uhr = FakeUhr(kieselAblage)
        val eingang = Channel<AppMessageData>(Channel.UNLIMITED)
        runBlocking { KieselsportBegleiter(context, geraet(uhr)).start(eingang.consumeAsFlow()) }
        eingang.trySend(kurvenstueck(30, ab = 0, n = 60, anzahl = 100))

        warte("Antwort an die Uhr") { antworten(uhr).isNotEmpty() }
        val (antwort, lag) = antworten(uhr).single()
        // Mit der Bestaetigung rueckt die Uhr weiter und vergisst das Stueck - es liegt schon hier.
        assertTrue(antwort is AppMessageResult.ACK, "Antwort $antwort")
        assertEquals(listOf(60), lag.map { it.bytes[Kieselsport.SP_KURVE]?.size })
        // Danach steht es in der Kurve, und die Datei der Schlange ist weg.
        warte("Stueck in der Kurve") { kurvendatei.exists() && kurvendatei.readLines().size == 60 }
        warte("Datei verarbeitet") { kieselAblage.listFiles().orEmpty().isEmpty() }
        // Das letzte Stueck macht sie voll: 100 Werte in der Akte.
        eingang.trySend(kurvenstueck(31, ab = 60, n = 40, anzahl = 100))
        warte("Kurve in der Akte") { kurvenproben().size == 100 }
    }

    @Test
    fun scheitert_das_sichern_eines_kurvenstuecks_kommt_ein_nack() {
        // Kein Sichern moeglich: wo der Ordner hin sollte, liegt eine Datei.
        kieselAblage.writeText("kein Ordner")
        val uhr = FakeUhr(kieselAblage)
        val eingang = Channel<AppMessageData>(Channel.UNLIMITED)
        runBlocking { KieselsportBegleiter(context, geraet(uhr)).start(eingang.consumeAsFlow()) }
        eingang.trySend(kurvenstueck(32, ab = 0, n = 60, anzahl = 100))

        warte("Antwort an die Uhr") { antworten(uhr).isNotEmpty() }
        val antwort = antworten(uhr).single().first
        assertTrue(antwort is AppMessageResult.NACK, "Antwort $antwort")
        assertEquals(32.toUByte(), antwort.transactionId)
        runBlocking { delay(500) }
        // Nichts angehaengt: die Uhr schickt dasselbe Stueck nach 2 s wieder.
        assertTrue(!kurvendatei.exists(), "angehaengt trotz NACK")
    }

    @Test
    fun eine_zustandsmeldung_braucht_keine_datei_und_wird_bestaetigt() {
        // Auch mit kaputter Ablage: eine Pause ist kein Eintrag - gesichert wuerde sie beim
        // naechsten Start ein laengst beendetes Training wieder anfassen.
        kieselAblage.writeText("kein Ordner")
        val uhr = FakeUhr(kieselAblage)
        val eingang = Channel<AppMessageData>(Channel.UNLIMITED)
        runBlocking { KieselsportBegleiter(context, geraet(uhr)).start(eingang.consumeAsFlow()) }
        eingang.trySend(
            AppMessageData(
                33u, Kieselsport.UUID,
                mapOf(Kieselsport.SP_ART to 3, Kieselsport.SP_BEGINN to beginn.toInt(), Kieselsport.SP_ZUSTAND to 2, Kieselsport.SP_ZEIT to (beginn + 600).toInt()),
            ),
        )

        warte("Antwort an die Uhr") { antworten(uhr).isNotEmpty() }
        val antwort = antworten(uhr).single().first
        assertTrue(antwort is AppMessageResult.ACK, "Antwort $antwort")
        assertEquals(33.toUByte(), antwort.transactionId)
        // Und verarbeitet: die Pause steht im Protokoll.
        val pausiert = context.getString(R.string.ks_a_pausiert, context.getString(R.string.ks_a_titel_kraft))
        warte("Pause verarbeitet") { KieselVerlauf.alle(context).any { it.second == pausiert } }
    }

    @Test
    fun ein_kurvenstueck_das_vor_dem_eintragen_liegen_blieb_kommt_beim_naechsten_start_in_die_kurve() {
        // Gesichert und bestaetigt, dann starb die App: zwei Stuecke liegen, die Uhr hat sie vergessen.
        assertNotNull(Wartend.lege(context, KieselsportBegleiter.zerlege(kurvenstueck(40, ab = 0, n = 60, anzahl = 90).data)))
        assertNotNull(Wartend.lege(context, KieselsportBegleiter.zerlege(kurvenstueck(41, ab = 60, n = 30, anzahl = 90).data)))
        runBlocking { KieselsportBegleiter(context, geraet(FakeUhr(kieselAblage))).start(emptyFlow()) }
        warte("Kurve in der Akte") { kurvenproben().size == 90 }
        warte("Dateien verarbeitet") { kieselAblage.listFiles().orEmpty().isEmpty() && !kurvendatei.exists() }
    }

    @Test
    fun ein_training_dessen_erster_versuch_erst_nach_einem_neustart_kommt_steht_einmal_im_protokoll() {
        // Gesichert und bestaetigt, dann starb die App vor dem ersten Versuch. Die Akte verweigert das Training.
        assertNotNull(Wartend.lege(context, KieselsportBegleiter.zerlege(zusammenfassung(42).data)))
        akte.weistAb = { r -> if (r is ExerciseSessionRecord) SecurityException("WRITE_EXERCISE fehlt") else null }
        val nicht = context.getString(R.string.ks_a_nicht_eingetragen)
        fun zeilen() = KieselVerlauf.alle(context).count { it.second == nicht }
        for (start in 1..2) {
            val versuche = akte.schreibversuche
            runBlocking { KieselsportBegleiter(context, geraet(FakeUhr(kieselAblage))).start(emptyFlow()) }
            // Wie oben: erst mit dem Vermerk im Namen ist der Versuch abgeschlossen.
            warte("Start $start versucht") {
                akte.schreibversuche > versuche && kieselAblage.listFiles().orEmpty().all { Wartend.gemeldet(it) }
            }
        }
        runBlocking { delay(300) }
        // Beide Versuche sind Nachtraege - im Protokoll steht es trotzdem einmal.
        assertEquals(1, zeilen(), KieselVerlauf.alle(context).toString())
        assertEquals(1, kieselAblage.listFiles().orEmpty().size)
    }

    @Test
    fun die_zusammenfassung_liegt_auf_der_platte_bevor_die_uhr_die_bestaetigung_bekommt() {
        val uhr = FakeUhr(kieselAblage)
        val eingang = Channel<AppMessageData>(Channel.UNLIMITED)
        runBlocking { KieselsportBegleiter(context, geraet(uhr)).start(eingang.consumeAsFlow()) }
        eingang.trySend(zusammenfassung(3))

        warte("Antwort an die Uhr") { antworten(uhr).isNotEmpty() }
        val (antwort, lag) = antworten(uhr).single()
        assertTrue(antwort is AppMessageResult.ACK, "Antwort $antwort")
        assertEquals(listOf(beginn), lag.map { it.zahlen[Kieselsport.SP_BEGINN] })
        warte("Training in der Akte") { akte.saetze<ExerciseSessionRecord>().isNotEmpty() }
        warte("Datei verarbeitet") { kieselAblage.listFiles().orEmpty().isEmpty() }
    }

    @Test
    fun scheitert_das_sichern_der_zusammenfassung_kommt_ein_nack() {
        kieselAblage.writeText("kein Ordner")
        val uhr = FakeUhr(kieselAblage)
        val eingang = Channel<AppMessageData>(Channel.UNLIMITED)
        runBlocking { KieselsportBegleiter(context, geraet(uhr)).start(eingang.consumeAsFlow()) }
        eingang.trySend(zusammenfassung(4))

        warte("Antwort an die Uhr") { antworten(uhr).isNotEmpty() }
        assertTrue(antworten(uhr).single().first is AppMessageResult.NACK, "Antwort ${antworten(uhr)}")
        runBlocking { delay(500) }
        assertEquals(emptyList(), akte.saetze<ExerciseSessionRecord>())
    }

    @Test
    fun ein_wartendes_training_steht_einmal_im_protokoll_nicht_bei_jedem_start() {
        // Die Akte verweigert das Training (Recht fehlt) - bei der Ankunft und bei zwei weiteren Starts.
        akte.weistAb = { r -> if (r is ExerciseSessionRecord) SecurityException("WRITE_EXERCISE fehlt") else null }
        val nicht = context.getString(R.string.ks_a_nicht_eingetragen)
        fun zeilen() = KieselVerlauf.alle(context).count { it.second == nicht }

        val uhr = FakeUhr(kieselAblage)
        val eingang = Channel<AppMessageData>(Channel.UNLIMITED)
        runBlocking { KieselsportBegleiter(context, geraet(uhr)).start(eingang.consumeAsFlow()) }
        eingang.trySend(zusammenfassung(6))
        // Bis auch der Vermerk im Namen steht: die Zeile kommt kurz davor, und ein Start dazwischen
        // fande die Datei noch unter dem alten Namen, die der Arbeiter gleich darauf umbenennt.
        warte("erster Versuch") { zeilen() == 1 && kieselAblage.listFiles().orEmpty().all { Wartend.gemeldet(it) } }
        val versuche = akte.schreibversuche
        for (start in 1..2) {
            runBlocking { KieselsportBegleiter(context, geraet(FakeUhr(kieselAblage))).start(emptyFlow()) }
            warte("Nachtrag $start versucht") { akte.schreibversuche >= versuche + start }
        }
        runBlocking { delay(300) }
        // Das Training liegt noch, aber im Protokoll steht es einmal - nicht dreimal.
        assertEquals(1, kieselAblage.listFiles().orEmpty().size)
        assertEquals(1, zeilen(), KieselVerlauf.alle(context).toString())

        // Wird das Recht erteilt, kommt es beim naechsten Start doch noch hinein.
        akte.weistAb = { null }
        runBlocking { KieselsportBegleiter(context, geraet(FakeUhr(kieselAblage))).start(emptyFlow()) }
        warte("Training in der Akte") { akte.saetze<ExerciseSessionRecord>().isNotEmpty() }
        warte("Datei verarbeitet") { kieselAblage.listFiles().orEmpty().isEmpty() }
    }

    /** Yoga, 30 min, mit der HRV der Uhr. */
    private fun yoga(id: Int, hrv: Int) = AppMessageData(
        id.toUByte(), Kieselsport.UUID,
        mapOf(
            Kieselsport.SP_ART to 5, Kieselsport.SP_BEGINN to beginn.toInt(), Kieselsport.SP_DAUER to 1800,
            Kieselsport.SP_ENDE to (beginn + 1800).toInt(), Kieselsport.SP_HRV to hrv,
        ),
    )

    /** Bis die Datei verarbeitet ist: geloescht oder als gemeldet umbenannt. */
    private fun warteBisVerarbeitet() = warte("Datei verarbeitet") { kieselAblage.listFiles().orEmpty().all { Wartend.gemeldet(it) } }

    @Test
    fun ein_abgewiesenes_yoga_mit_hrv_bleibt_liegen() {
        // Die Akte nimmt die HRV, verweigert aber die Sitzung (Recht fehlt). Yoga hat keine Spur:
        // ohne die Datei gaebe es keinen zweiten Versuch.
        assertNotNull(Wartend.lege(context, KieselsportBegleiter.zerlege(yoga(50, hrv = 45).data)))
        akte.weistAb = { r -> if (r is ExerciseSessionRecord) SecurityException("WRITE_EXERCISE fehlt") else null }
        runBlocking { KieselsportBegleiter(context, geraet(FakeUhr(kieselAblage))).start(emptyFlow()) }
        warte("HRV in der Akte") { akte.saetze<HeartRateVariabilityRmssdRecord>().isNotEmpty() }
        warteBisVerarbeitet()
        assertEquals(1, kieselAblage.listFiles().orEmpty().size, "Training mit der Datei verloren")
        val nicht = context.getString(R.string.ks_a_nicht_eingetragen)
        assertTrue(KieselVerlauf.alle(context).any { it.second == nicht }, KieselVerlauf.alle(context).toString())

        // Mit dem Recht kommt die Sitzung beim naechsten Start hinein - die HRV nicht doppelt.
        akte.weistAb = { null }
        runBlocking { KieselsportBegleiter(context, geraet(FakeUhr(kieselAblage))).start(emptyFlow()) }
        warte("Training in der Akte") { akte.saetze<ExerciseSessionRecord>().isNotEmpty() }
        warte("Datei weg") { kieselAblage.listFiles().orEmpty().isEmpty() }
        assertEquals(1, akte.saetze<HeartRateVariabilityRmssdRecord>().size)
    }

    @Test
    fun ein_abgewiesenes_training_dessen_kurve_schon_wartete_gilt_als_gescheitert() {
        // Die Kurve lag schon da (sie wartete auf die Zusammenfassung); die Sitzung weist die Akte ab.
        // Direkt durch verarbeite: beim Start des Begleiters truege sonst Pulskurve.alleEintragen
        // die Kurve womoeglich vorher ein, und der Zusatz kaeme gar nicht erst an die Meldung.
        for (i in 0 until 60) Pulskurve.anhaengen(context, beginn, i * 10, 120)
        akte.weistAb = { r -> if (r is ExerciseSessionRecord) SecurityException("WRITE_EXERCISE fehlt") else null }
        val meldung = runBlocking { Kieselsport.verarbeite(context, KieselsportBegleiter.zerlege(zusammenfassung(51).data), beginn + 1810) }
        assertEquals(60, kurvenproben().size)
        // Nur an gescheitert() erkennt der Begleiter, dass die Datei liegen bleiben muss.
        assertTrue(Kieselsport.gescheitert(context, meldung), "Meldung $meldung")
    }

    @Test
    fun eine_hrv_die_die_akte_nicht_nahm_steht_nicht_im_protokoll() {
        akte.weistAb = { r -> if (r is HeartRateVariabilityRmssdRecord) SecurityException("WRITE_HRV fehlt") else null }
        assertNotNull(Wartend.lege(context, KieselsportBegleiter.zerlege(yoga(52, hrv = 45).data)))
        runBlocking { KieselsportBegleiter(context, geraet(FakeUhr(kieselAblage))).start(emptyFlow()) }
        warte("Training in der Akte") { akte.saetze<ExerciseSessionRecord>().isNotEmpty() }
        warte("Datei weg") { kieselAblage.listFiles().orEmpty().isEmpty() }
        val hrv = context.getString(R.string.ks_a_hrv, 45)
        val zeile = KieselVerlauf.alle(context).map { it.second }.first { it.startsWith(context.getString(R.string.ks_a_titel_yoga)) }
        assertTrue(hrv !in zeile, "HRV im Protokoll, obwohl nicht eingetragen: $zeile")
    }
}
