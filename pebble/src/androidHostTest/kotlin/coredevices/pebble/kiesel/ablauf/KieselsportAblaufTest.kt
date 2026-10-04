package coredevices.pebble.kiesel.ablauf

import android.content.Context
import androidx.health.connect.client.records.ExerciseSegment
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import coredevices.pebble.kiesel.Akte
import coredevices.pebble.kiesel.KieselNachricht
import coredevices.pebble.kiesel.Kieselsport
import coredevices.pebble.kiesel.Pulskurve
import coredevices.pebble.kiesel.Spur
import coredevices.pebble.kiesel.TrainingEintrag
import coredevices.pebble.kiesel.TrainingsDaten
import coredevices.pebble.kiesel.Sport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * DER GANZE WEG EINES TRAININGS MIT PAUSE, wie die Uhr ihn schickt: Start,
 * Pause, Weiter, Pause zum Speichern, die Zusammenfassung mit der
 * Pausenliste, dann die Pulskurve in zwei Stuecken - durch
 * Kieselsport.verarbeite in eine Akte im Speicher (FakeAkte), und zurueck
 * durch TrainingsDaten.rohpuls, wie Detail und Kalorien sie lesen.
 *
 * Die Hilfsfunktionen (wand, alsSegmente, ohnePausen) hatten ihre Tests;
 * dass die Pausen auch bis zu ihnen KOMMEN, nicht. Fiel die Verdrahtung weg
 * (eine leere Liste statt der Pausen), blieb alles gruen - und auf dem
 * Telefon standen Saetze, Puls und Dauer an der falschen Stelle.
 *
 * Das Training: 10 min aktiv, 5 min Pause, 10 min aktiv, gespeichert aus
 * der Pause. Zwei Saetze, der zweite nach der Pause.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class KieselsportAblaufTest {
    private lateinit var context: Context
    private lateinit var akte: FakeAkte

    private val b = Instant.now().epochSecond / 60 * 60 - 7200
    private val pauseVon = b + 600
    private val pauseBis = b + 900
    private val dauer = 1200L
    private val gespeichert = b + 1500

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

    private fun zustand(art: Long, zustand: Long, zeit: Long) = KieselNachricht(
        mapOf(Kieselsport.SP_ART to art, Kieselsport.SP_BEGINN to b, Kieselsport.SP_ZUSTAND to zustand, Kieselsport.SP_ZEIT to zeit),
    )

    /** Die Pausenliste der Uhr: Fassung, Zahl, je Pause aktive Sekunde und Dauer (uint16 LE) - hier von Hand. */
    private val pausenliste = byteArrayOf(1, 1, (600 and 0xFF).toByte(), (600 shr 8).toByte(), (300 and 0xFF).toByte(), (300 shr 8).toByte())

    private fun zusammenfassung(art: Long, mitAbschnitten: Boolean) = KieselNachricht(
        zahlen = buildMap {
            put(Kieselsport.SP_ART, art)
            put(Kieselsport.SP_BEGINN, b)
            put(Kieselsport.SP_DAUER, dauer)
            put(Kieselsport.SP_ENDE, gespeichert)
            if (mitAbschnitten) {
                put(Kieselsport.SP_SAETZE, 2L)
                put(Kieselsport.SP_REPS, 18L)
            }
        },
        texte = if (mitAbschnitten) mapOf(Kieselsport.SP_ABSCHNITTE to "0:10:60;700:8:60") else emptyMap(),
        bytes = mapOf(Kieselsport.SP_PAUSEN to pausenliste),
    )

    /** Ein Stueck der Pulskurve: ab dem [ab]-ten Wert (alle 10 s aktive Zeit), [werte] Schlaege. */
    private fun kurve(ab: Int, anzahl: Int, werte: List<Int>) = KieselNachricht(
        zahlen = mapOf(Kieselsport.SP_BEGINN to b, Kieselsport.SP_KURVE_AB to ab.toLong(), Kieselsport.SP_KURVE_ANZAHL to anzahl.toLong()),
        bytes = mapOf(Kieselsport.SP_KURVE to ByteArray(werte.size) { werte[it].toByte() }),
    )

    private fun verarbeite(n: KieselNachricht, empfangen: Long) = runBlocking { Kieselsport.verarbeite(context, n, empfangen) }

    /** Start, Pause, Weiter, Pause zum Speichern - die Zustandsmeldungen vor der Zusammenfassung. */
    private fun bisZurZusammenfassung(art: Long) {
        verarbeite(zustand(art, 1, b), b + 1)
        verarbeite(zustand(art, 2, pauseVon), pauseVon + 1)
        verarbeite(zustand(art, 3, pauseBis), pauseBis + 1)
        verarbeite(zustand(art, 2, gespeichert), gespeichert + 1)
    }

    private fun sitzung(): ExerciseSessionRecord = akte.saetze<ExerciseSessionRecord>().single()

    private fun spanne(s: ExerciseSegment) = (s.startTime.epochSecond - b) to (s.endTime.epochSecond - b)

    @Test
    fun saetze_und_pause_stehen_an_ihrer_uhrzeit() {
        bisZurZusammenfassung(art = 3)
        verarbeite(zusammenfassung(art = 3, mitAbschnitten = true), gespeichert + 5)

        val s = sitzung()
        assertEquals(b, s.startTime.epochSecond)
        // Ohne Spur endet die Sitzung, wo die aktive Zeit endet: 1200 s aktiv + 300 s Pause.
        assertEquals(gespeichert, s.endTime.epochSecond)
        val saetze = s.segments.filter { it.segmentType == ExerciseSegment.EXERCISE_SEGMENT_TYPE_OTHER_WORKOUT }.map(::spanne)
        // Der zweite Satz begann bei der aktiven Sekunde 700 - hinter der Pause also 300 s spaeter.
        assertEquals(listOf(0L to 60L, 1000L to 1060L), saetze)
        val pausen = s.segments.filter { it.segmentType == ExerciseSegment.EXERCISE_SEGMENT_TYPE_PAUSE }.map(::spanne)
        assertEquals(listOf(600L to 900L), pausen)
        // Die Ruhe zwischen den Saetzen ohne die Pause: die gehoert nicht zur Satzpause.
        val ruhe = s.segments.filter { it.segmentType == ExerciseSegment.EXERCISE_SEGMENT_TYPE_REST }.map(::spanne)
        assertEquals(listOf(60L to 600L, 900L to 1000L), ruhe)
        // Die Liste rechnet aktive Zeit: dieselbe wie auf der Uhr.
        assertEquals(dauer, TrainingEintrag(s, Sport.Kraft, true, 0.0).sekunden)
    }

    @Test
    fun bahnen_stehen_an_ihrer_uhrzeit() {
        // Schwimmen: zwei Bahnen im 25-m-Becken, die zweite nach der Pause (aktive Sekunde 700).
        bisZurZusammenfassung(art = 6)
        val n = zusammenfassung(art = 6, mitAbschnitten = false)
        verarbeite(
            n.copy(
                zahlen = n.zahlen + mapOf(Kieselsport.SP_BAHNEN to 2L, Kieselsport.SP_METER to 50L),
                texte = mapOf(Kieselsport.SP_ABSCHNITTE to "0:1:60;700:1:60"),
            ),
            gespeichert + 5,
        )
        val s = sitzung()
        // Auch die Bahn hinter der Pause steht 300 s spaeter - wie die Saetze.
        assertEquals(listOf(0L to 60L, 1000L to 1060L), s.laps.map { (it.startTime.epochSecond - b) to (it.endTime.epochSecond - b) })
        assertEquals(listOf(25.0, 25.0), s.laps.map { it.length!!.inMeters })
        val pausen = s.segments.filter { it.segmentType == ExerciseSegment.EXERCISE_SEGMENT_TYPE_PAUSE }.map(::spanne)
        assertEquals(listOf(600L to 900L), pausen)
        assertEquals(dauer, TrainingEintrag(s, Sport.Schwimmen, true, 0.0).sekunden)
    }

    @Test
    fun die_kurve_rueckt_hinter_die_pause_und_rohpuls_laesst_sie_aus() {
        bisZurZusammenfassung(art = 3)
        verarbeite(zusammenfassung(art = 3, mitAbschnitten = true), gespeichert + 5)
        // 120 Werte, je 10 s aktive Zeit, in zwei Stuecken. Die Uhr nimmt einen Wert beim Takt, der
        // ihre Sekunde erreicht (dienst.c): Sekunde 600 fiel vor der Pause, 610 erst nach dem Weiter.
        val schlaege = List(120) { if (it <= 60) 110 else 140 }
        verarbeite(kurve(0, 120, schlaege.take(60)), gespeichert + 20)
        verarbeite(kurve(60, 120, schlaege.drop(60)), gespeichert + 25)

        val proben = akte.saetze<HeartRateRecord>().flatMap { it.samples }.map { it.time.epochSecond - b to it.beatsPerMinute }
        assertEquals(120, proben.size)
        // Nichts liegt in der Pause; die aktive Sekunde 610 steht bei 910, nicht bei 610.
        assertTrue(proben.none { (t, _) -> t in 601 until 900 }, "Kurve in der Pause: ${proben.filter { it.first in 601 until 900 }}")
        assertEquals(600L to 110L, proben.last { it.second == 110L })
        assertEquals(910L to 140L, proben.first { it.second == 140L })
        assertEquals(1490L to 140L, proben.last())

        // Der Minutenpuls der Uhr misst in der Pause weiter (Boulders eigener Satz, nicht die Kurve).
        akte.legeAb(
            HeartRateRecord(
                Instant.ofEpochSecond(b + 700), ZoneOffset.UTC, Instant.ofEpochSecond(b + 761), ZoneOffset.UTC,
                listOf(HeartRateRecord.Sample(Instant.ofEpochSecond(b + 700), 72), HeartRateRecord.Sample(Instant.ofEpochSecond(b + 760), 70)),
                Metadata.autoRecorded(Device(type = Device.TYPE_WATCH), "minutenpuls-test"),
            ),
            context.packageName,
        )
        val roh = runBlocking { TrainingsDaten.rohpuls(akte, context, sitzung()) }
        // Zonen, Schnitt und Kalorien sehen die Pause nicht - wie die Uhr. Die Pause der Akte ist
        // [600, 900): der Wert genau beim Druck auf Pause faellt mit hinaus, alle anderen 119 bleiben.
        assertTrue(roh.none { it.sekunde in 600 until 900 }, "rohpuls nimmt die Pause mit: ${roh.filter { it.sekunde in 600 until 900 }}")
        assertEquals(119, roh.size)
    }

    @Test
    fun ein_stueck_das_waehrend_des_eintragens_ankommt_geht_nicht_verloren() {
        // Vom letzten Mal liegt das erste Stueck einer Kurve ohne Pausen; der Start traegt es ein
        // (alleEintragen), und gerade dann schickt die Uhr das zweite - die Schlange verarbeitet es.
        verarbeite(kurve(0, 120, List(60) { 110 }), b + 2000)
        var zweites: Job? = null
        akte.waehrendDesSchreibens = { saetze ->
            if (zweites == null && saetze.any { it is HeartRateRecord }) {
                zweites = CoroutineScope(Dispatchers.IO).launch { Kieselsport.verarbeite(context, kurve(60, 120, List(60) { 140 }), b + 2010) }
                // Bis das Stueck in der Datei steht - und bis sein eigener Eintrag durch ist oder
                // wartet (hoechstens 1 s): ohne Riegel schriebe er jetzt dieselbe Kennung.
                withTimeout(10_000) { while (File(context.filesDir, "puls/$b.csv").readLines().size < 120) delay(10) }
                withTimeoutOrNull(1_000) { zweites!!.join() }
            }
        }
        runBlocking { Pulskurve.eintragen(context, b) }
        runBlocking { withTimeout(10_000) { zweites!!.join() } }

        // Alle 120 Werte stehen in der Akte: keiner mit der Datei geloescht, keiner von
        // einem spaeter fertigen, unvollstaendigeren Eintrag derselben Kennung ersetzt.
        val proben = akte.saetze<HeartRateRecord>().flatMap { it.samples }
        assertEquals(120, proben.map { it.time }.toSet().size, "${akte.saetze<HeartRateRecord>().map { it.metadata.clientRecordId to it.samples.size }}")
        assertEquals(setOf(110L, 140L), proben.map { it.beatsPerMinute }.toSet())
        assertTrue(!File(context.filesDir, "puls/$b.csv").exists(), "Kurvendatei liegt noch")
    }

    @Test
    fun der_gps_nachlauf_nach_der_letzten_pause_ist_pause() {
        // Laufen: die Spur laeuft, bis die Meldung der letzten Pause am Telefon ist - hier 40 s darueber.
        bisZurZusammenfassung(art = 0)
        val zeilen = ((b until pauseVon step 10) + (pauseBis..gespeichert + 40 step 10)).joinToString("") { t ->
            "{\"t\":$t,\"lat\":${47.0 + (t - b) * 1e-5},\"lon\":8.0,\"g\":5.0}\n"
        }
        assertTrue(Spur.schreibeRoh(context, b, zeilen))
        verarbeite(zusammenfassung(art = 0, mitAbschnitten = false), gespeichert + 100)

        val s = sitzung()
        // Die Route reicht bis zum letzten Punkt - die Akte nimmt nur Punkte in der Sitzung.
        assertEquals(gespeichert + 41, s.endTime.epochSecond)
        val pausen = s.segments.filter { it.segmentType == ExerciseSegment.EXERCISE_SEGMENT_TYPE_PAUSE }.map(::spanne)
        assertEquals(listOf(600L to 900L, 1500L to 1541L), pausen)
        // Die Dauer in Liste, Woche und Widget ist die der Uhr, nicht 41 s mehr.
        assertEquals(dauer, TrainingEintrag(s, Sport.Laufen, true, 0.0).sekunden)
    }

    @Test
    fun auch_ein_nachgetragenes_training_zaehlt_den_nachlauf_als_pause() {
        // Die Zusammenfassung kam nie, nur die Spur - nachgetragen aus der Sportseite (Laufen).
        bisZurZusammenfassung(art = 0)
        val zeilen = ((b until pauseVon step 10) + (pauseBis..gespeichert + 40 step 10)).joinToString("") { t ->
            "{\"t\":$t,\"lat\":${47.0 + (t - b) * 1e-5},\"lon\":8.0,\"g\":5.0}\n"
        }
        assertTrue(Spur.schreibeRoh(context, b, zeilen))
        runBlocking { Kieselsport.nachtragen(context, b, 0) }

        val s = sitzung()
        assertEquals(gespeichert + 41, s.endTime.epochSecond)
        val pausen = s.segments.filter { it.segmentType == ExerciseSegment.EXERCISE_SEGMENT_TYPE_PAUSE }.map(::spanne)
        assertEquals(listOf(600L to 900L, 1500L to 1541L), pausen)
        assertEquals(dauer, TrainingEintrag(s, Sport.Laufen, true, 0.0).sekunden)
    }
}
