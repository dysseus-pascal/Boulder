package coredevices.pebble.kiesel.ablauf

import android.Manifest
import android.content.Context
import android.content.Intent
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseRoute
import androidx.health.connect.client.records.ExerciseRouteResult
import androidx.health.connect.client.records.ExerciseSegment
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Length
import coredevices.pebble.R
import coredevices.pebble.kiesel.Akte
import coredevices.pebble.kiesel.KieselNachricht
import coredevices.pebble.kiesel.KieselEinstellungen
import coredevices.pebble.kiesel.Kieselsport
import coredevices.pebble.kiesel.Pulskurve
import coredevices.pebble.kiesel.Spur
import coredevices.pebble.kiesel.SpurDienst
import coredevices.pebble.kiesel.TrainingEintrag
import coredevices.pebble.kiesel.TrainingsDaten
import coredevices.pebble.kiesel.Sport
import coredevices.pebble.kiesel.ansicht.NACHTRAGBARE_ARTEN
import coredevices.pebble.kiesel.ansicht.PIN_ARTEN
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    @Test
    fun ein_kaputter_spurpunkt_kostet_nur_die_route() {
        // Ein Punkt mit Breite 95 (etwa aus einer alten Sicherung): die Akte weist schon die Route
        // ab. Er steht bei jedem Versuch wieder da - liegen bleiben hiesse nie eintragen.
        bisZurZusammenfassung(art = 0)
        val zeilen = ((b until pauseVon step 10) + (pauseBis..gespeichert step 10)).joinToString("") { t ->
            val lat = if (t == b + 300) 95.0 else 47.0 + (t - b) * 1e-5
            "{\"t\":$t,\"lat\":$lat,\"lon\":8.0,\"g\":5.0}\n"
        }
        assertTrue(Spur.schreibeRoh(context, b, zeilen))
        val meldung = verarbeite(zusammenfassung(art = 0, mitAbschnitten = false), gespeichert + 100)

        assertFalse(Kieselsport.gescheitert(context, meldung), "Meldung $meldung")
        val s = sitzung()
        assertTrue(s.exerciseRouteResult is ExerciseRouteResult.NoData, "Route ${s.exerciseRouteResult}")
        // Der Rest bleibt: die Pause steht an ihrer Uhrzeit, die Dauer ist die der Uhr.
        val pausen = s.segments.filter { it.segmentType == ExerciseSegment.EXERCISE_SEGMENT_TYPE_PAUSE }.map(::spanne)
        assertEquals(600L to 900L, pausen.first())
        assertEquals(dauer, TrainingEintrag(s, Sport.Laufen, true, 0.0).sekunden)
    }

    @Test
    fun eine_atemuebung_steht_als_gefuehrtes_atmen_mit_rhythmus_in_der_akte() {
        // Zuletzt meldete die Uhr 4-7-8-0 in zwoelf Runden (19 s je Runde).
        val rhythmus = mapOf(
            Kieselsport.SP_ATEM_EIN to 4L, Kieselsport.SP_ATEM_HALTEN to 7L, Kieselsport.SP_ATEM_AUS to 8L,
            Kieselsport.SP_ATEM_LEER to 0L, Kieselsport.SP_ATEM_RUNDEN to 12L,
        )
        KieselEinstellungen.vonDerUhr(context, mapOf(Kieselsport.SP_MAXPULS to 190L) + rhythmus, emptyMap())
        bisZurZusammenfassung(art = Kieselsport.ART_ATEM)
        val meldung = verarbeite(zusammenfassung(art = Kieselsport.ART_ATEM, mitAbschnitten = false), gespeichert + 5)
        assertFalse(Kieselsport.gescheitert(context, meldung), "Meldung $meldung")
        val s = sitzung()
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_GUIDED_BREATHING, s.exerciseType)
        assertEquals(Sport.Atem, Sport.von(s))
        // 1200 s aktiv waeren 63 Runden zu 19 s; die Uhr hoert nach der zwoelften auf, mehr zaehlt nicht.
        assertEquals("12 rounds of 4-7-8-0 s (inhale, hold, exhale, hold)", s.notes)
    }

    @Test
    fun der_rhythmus_kommt_von_der_uhr_und_bleibt_bei_aelterer_firmware() {
        val rhythmus = mapOf(
            Kieselsport.SP_ATEM_EIN to 5L, Kieselsport.SP_ATEM_HALTEN to 0L, Kieselsport.SP_ATEM_AUS to 7L,
            Kieselsport.SP_ATEM_LEER to 0L, Kieselsport.SP_ATEM_RUNDEN to 20L,
        )
        KieselEinstellungen.vonDerUhr(context, mapOf(Kieselsport.SP_MAXPULS to 190L) + rhythmus, emptyMap())
        val stand = KieselEinstellungen.stand(context)
        assertEquals(KieselEinstellungen.Atemrhythmus(5, 0, 7, 0, 20), stand.atem)
        assertTrue(stand.uhrKenntAtem)
        // Eine Uhr vor b36 meldet keinen Rhythmus: der zuletzt gemeldete bleibt, die Uebung kennt sie aber nicht.
        KieselEinstellungen.vonDerUhr(context, mapOf(Kieselsport.SP_MAXPULS to 190L), emptyMap())
        val alt = KieselEinstellungen.stand(context)
        assertEquals(stand.atem, alt.atem)
        assertFalse(alt.uhrKenntAtem)
        // Ob die Uhr die Uebung kennt, ist kein Unterschied in den Einstellungen.
        assertTrue(stand.gleich(alt.copy(gemeldetUm = stand.gemeldetUm)))
        assertFalse(stand.gleich(stand.copy(atem = stand.atem.copy(runden = 21))))
    }

    @Test
    fun eine_unmoegliche_bahnlaenge_kostet_nur_die_bahnen() {
        // Zwei Bahnen ueber 2000 km: die Akte nimmt hoechstens 1000 km je Bahn und wirft schon beim Bauen.
        bisZurZusammenfassung(art = 6)
        val n = zusammenfassung(art = 6, mitAbschnitten = false)
        val meldung = verarbeite(
            n.copy(
                zahlen = n.zahlen + mapOf(Kieselsport.SP_BAHNEN to 2L, Kieselsport.SP_METER to 4_000_000L),
                texte = mapOf(Kieselsport.SP_ABSCHNITTE to "0:1:60;700:1:60"),
            ),
            gespeichert + 5,
        )
        assertFalse(Kieselsport.gescheitert(context, meldung), "Meldung $meldung")
        val s = sitzung()
        assertEquals(emptyList(), s.laps)
        // Die Pause bleibt: sie haengt nicht an den Bahnen.
        val pausen = s.segments.filter { it.segmentType == ExerciseSegment.EXERCISE_SEGMENT_TYPE_PAUSE }.map(::spanne)
        assertEquals(listOf(600L to 900L), pausen)
    }

    /** Was den Spurdienst startete - ein Stopp traegt eine Aktion, ein Start nicht. */
    private fun spurStarts(): List<Intent> = shadowOf(RuntimeEnvironment.getApplication()).allStartedServices
        .filter { it.component?.className == SpurDienst::class.java.name && it.action == null }

    @Test
    fun nur_die_arten_mit_strecke_orten_beim_start_und_nach_der_pause() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        // Je Art, wie oft der Spurdienst startete: beim Start und nach der Pause - oder nie. 13 kennt noch keine Uhr.
        val arten = 0L..13L
        val starts = arten.associateWith { art ->
            val vorher = spurStarts().size
            bisZurZusammenfassung(art)
            spurStarts().size - vorher
        }
        val ortend = setOf(0L, 1L, 2L, 4L, 10L, 12L)
        assertEquals(arten.associateWith { if (it in ortend) 2 else 0 }, starts)
    }

    @Test
    fun jede_art_steht_mit_ihrer_satzart_und_ihrem_titel_in_der_akte() {
        // Die GPS-Fassungen (10, 12) wie ihre Art; 13 kennt noch keine Uhr.
        val erwartet = listOf(
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING to "Running",
            ExerciseSessionRecord.EXERCISE_TYPE_BIKING to "Bike Road/Gravel",
            ExerciseSessionRecord.EXERCISE_TYPE_HIKING to "Hiking",
            ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING to "Strength",
            ExerciseSessionRecord.EXERCISE_TYPE_BIKING to "Bike MTB",
            ExerciseSessionRecord.EXERCISE_TYPE_YOGA to "Yoga",
            ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL to "Swimming Pool",
            ExerciseSessionRecord.EXERCISE_TYPE_GUIDED_BREATHING to "Breathing",
            ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY to "Bike Indoor",
            ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER to "Swimming Open water",
            ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER to "Swimming Open water",
            ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT to "Custom",
            ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT to "Custom",
            ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT to "Workout",
        )
        assertEquals(erwartet, erwartet.indices.map { Kieselsport.artAlsSatzart(context, it.toLong()) })
    }

    /** Eine Spur bis zum Speichern, ohne Punkte in der Pause, 11 m je 10 s nach Norden; [hoehe] je Sekunde ab Beginn. */
    private fun spur(hoehe: (Long) -> Double): String =
        ((b until pauseVon step 10) + (pauseBis..gespeichert step 10)).joinToString("") { t ->
            "{\"t\":$t,\"lat\":${47.0 + (t - b) * 1e-5},\"lon\":8.0,\"g\":5.0,\"h\":${hoehe(t - b)}}\n"
        }

    /** Die Hoehe einer Boje im Wellengang des GPS: 30 m hinauf, dann zurueck, alle zehn Minuten. */
    private val rauschen: (Long) -> Double = { s -> 400.0 + s % 600 / 20.0 }

    @Test
    fun freiwasser_ohne_gps_steht_ohne_strecke_als_schwimmen_im_offenen_wasser_in_der_akte() {
        bisZurZusammenfassung(art = 9)
        val meldung = verarbeite(zusammenfassung(art = 9, mitAbschnitten = false), gespeichert + 5)
        assertFalse(Kieselsport.gescheitert(context, meldung), "Meldung $meldung")
        val s = sitzung()
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER, s.exerciseType)
        assertEquals(Sport.Freiwasser, Sport.von(s))
        // Die Akte nimmt nur Stuecke, die zur Sportart passen: die Pause muss auch im See bleiben.
        val pausen = s.segments.filter { it.segmentType == ExerciseSegment.EXERCISE_SEGMENT_TYPE_PAUSE }.map(::spanne)
        assertEquals(listOf(600L to 900L), pausen)
        assertTrue(s.exerciseRouteResult is ExerciseRouteResult.NoData, "Route ${s.exerciseRouteResult}")
        assertEquals(0.0, runBlocking { TrainingsDaten.uebersicht(context) }.trainings.single().meter)
    }

    @Test
    fun freiwasser_mit_gps_traegt_strecke_und_route_aber_keinen_aufstieg() {
        bisZurZusammenfassung(art = 10)
        assertTrue(Spur.schreibeRoh(context, b, spur(rauschen)))
        val meldung = verarbeite(zusammenfassung(art = 10, mitAbschnitten = false), gespeichert + 5)
        assertFalse(Kieselsport.gescheitert(context, meldung), "Meldung $meldung")
        val s = sitzung()
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER, s.exerciseType)
        assertEquals(Sport.Freiwasser, Sport.von(s))
        assertTrue(s.exerciseRouteResult is ExerciseRouteResult.Data, "Route ${s.exerciseRouteResult}")
        assertTrue(runBlocking { TrainingsDaten.uebersicht(context) }.trainings.single().meter > 0)
        // Auf dem See waere jeder Hoehenmeter erfunden - die Akte bekommt keinen.
        assertEquals(emptyList(), akte.saetze<ElevationGainedRecord>())
    }

    @Test
    fun ein_nachgetragenes_freiwasser_traegt_ebenso_keinen_aufstieg() {
        bisZurZusammenfassung(art = 10)
        assertTrue(Spur.schreibeRoh(context, b, spur(rauschen)))
        runBlocking { Kieselsport.nachtragen(context, b, 10) }
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER, sitzung().exerciseType)
        assertTrue(akte.saetze<DistanceRecord>().single().distance.inMeters > 0)
        assertEquals(emptyList(), akte.saetze<ElevationGainedRecord>())
    }

    @Test
    fun custom_mit_gps_traegt_strecke_route_und_aufstieg() {
        // Gegenprobe zum Freiwasser mit derselben Spur: an Land zaehlt, was bergauf ging.
        bisZurZusammenfassung(art = 12)
        assertTrue(Spur.schreibeRoh(context, b, spur(rauschen)))
        val meldung = verarbeite(zusammenfassung(art = 12, mitAbschnitten = false), gespeichert + 5)
        assertFalse(Kieselsport.gescheitert(context, meldung), "Meldung $meldung")
        val s = sitzung()
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT, s.exerciseType)
        assertEquals(context.getString(R.string.ks_a_titel_offen), s.title)
        assertEquals(Sport.Offen, Sport.von(s))
        val pausen = s.segments.filter { it.segmentType == ExerciseSegment.EXERCISE_SEGMENT_TYPE_PAUSE }.map(::spanne)
        assertEquals(600L to 900L, pausen.first())
        assertTrue(s.exerciseRouteResult is ExerciseRouteResult.Data, "Route ${s.exerciseRouteResult}")
        assertTrue(runBlocking { TrainingsDaten.uebersicht(context) }.trainings.single().meter > 0)
        assertTrue(akte.saetze<ElevationGainedRecord>().single().elevation.inMeters > 0)
    }

    @Test
    fun indoor_steht_als_stationaeres_radfahren_in_der_akte() {
        bisZurZusammenfassung(art = 8)
        val meldung = verarbeite(zusammenfassung(art = 8, mitAbschnitten = false), gespeichert + 5)
        assertFalse(Kieselsport.gescheitert(context, meldung), "Meldung $meldung")
        val s = sitzung()
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY, s.exerciseType)
        assertEquals(context.getString(R.string.ks_a_titel_indoor), s.title)
        assertEquals(Sport.Indoor, Sport.von(s))
        // Die Akte nimmt nur Stuecke, die zur Sportart passen: die Pause muss auch auf der Rolle bleiben.
        val pausen = s.segments.filter { it.segmentType == ExerciseSegment.EXERCISE_SEGMENT_TYPE_PAUSE }.map(::spanne)
        assertEquals(listOf(600L to 900L), pausen)
        assertEquals(dauer, TrainingEintrag(s, Sport.Indoor, true, 0.0).sekunden)
    }

    /** Ein Training einer fremden App mit Distanz und Route - aus dem GPS oder, auf der Rolle, aus einer virtuellen Welt. */
    private fun fremdesTraining(paket: String, von: Instant, art: Int) {
        val bis = von.plusSeconds(dauer)
        val route = ExerciseRoute(listOf(
            ExerciseRoute.Location(time = von.plusSeconds(60), latitude = 47.0, longitude = 8.0),
            ExerciseRoute.Location(time = von.plusSeconds(120), latitude = 47.01, longitude = 8.0),
        ))
        akte.legeAb(
            ExerciseSessionRecord(
                startTime = von, startZoneOffset = null, endTime = bis, endZoneOffset = null,
                metadata = Metadata.manualEntry(), exerciseType = art, exerciseRoute = route,
            ),
            paket,
        )
        akte.legeAb(
            DistanceRecord(
                startTime = von, startZoneOffset = null, endTime = bis, endZoneOffset = null,
                distance = Length.meters(12_000.0), metadata = Metadata.manualEntry(),
            ),
            paket,
        )
    }

    @Test
    fun eine_fremde_rolle_zeigt_weder_strecke_noch_route() {
        val paket = "com.andere.rolle"
        fremdesTraining(paket, Instant.ofEpochSecond(b), ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY)
        // Gegenprobe: dieselbe App am Tag davor draussen - dort zaehlen Strecke und Route.
        fremdesTraining(paket, Instant.ofEpochSecond(b - 86_400), ExerciseSessionRecord.EXERCISE_TYPE_BIKING)

        val trainings = runBlocking { TrainingsDaten.uebersicht(context) }.trainings
        assertEquals(0.0, trainings.single { it.sport == Sport.Indoor }.meter)
        assertTrue(trainings.single { it.sport == Sport.Bike }.meter > 0)
        val sitzungen = akte.saetze<ExerciseSessionRecord>()
        val rolle = sitzungen.single { it.exerciseType == ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY }
        val draussen = sitzungen.single { it.exerciseType == ExerciseSessionRecord.EXERCISE_TYPE_BIKING }
        assertEquals(emptyList(), TrainingsDaten.punkte(context, rolle))
        assertEquals(2, TrainingsDaten.punkte(context, draussen).size)
    }

    @Test
    fun fremdes_freiwasser_und_fremdes_custom_behalten_strecke_und_route() {
        // Anders als die Rolle: im See und bei einem Training ohne Sportart kann die Strecke echt sein.
        val paket = "com.andere.uhr"
        fremdesTraining(paket, Instant.ofEpochSecond(b), ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER)
        fremdesTraining(paket, Instant.ofEpochSecond(b - 86_400), ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT)

        val trainings = runBlocking { TrainingsDaten.uebersicht(context) }.trainings
        assertTrue(trainings.single { it.sport == Sport.Freiwasser }.meter > 0)
        assertTrue(trainings.single { it.sport == Sport.Offen }.meter > 0)
        akte.saetze<ExerciseSessionRecord>().forEach { s ->
            assertEquals(2, TrainingsDaten.punkte(context, s).size, "Route von Art ${s.exerciseType}")
        }
    }

    @Test
    fun jede_art_hat_ihren_pin_an_ihrer_nummer_plus_eins() {
        // 0 ist kein Pin, dann jede Art der Uhr unter ihrer Nummer plus eins (einstellungen.c der Firmware).
        // Bekannt ist eine Art, solange sie nicht als blosses "Workout" in die Akte geht.
        val arten = generateSequence(0L) { it + 1 }
            .takeWhile { Kieselsport.artAlsSatzart(context, it).second != context.getString(R.string.ks_training) }
            .toList()
        assertEquals(R.string.ks_n_kein_pin, PIN_ARTEN.first())
        assertEquals(arten.size + 1, PIN_ARTEN.size)
        arten.forEach { art ->
            val satz = Kieselsport.artAlsSatzart(context, art)
            val sport = context.getString(Sport.von(satz.first, satz.second).nameId)
            // Eine GPS-Fassung (10, 12) steht wie die Art vor ihr in der Akte; ihr Pin heisst wie auf der Uhr.
            val gps = art > 0 && Kieselsport.artAlsSatzart(context, art - 1) == satz
            assertEquals(if (gps) "$sport GPS" else sport, context.getString(PIN_ARTEN[art.toInt() + 1]), "Pin fuer Art $art")
        }
    }

    @Test
    fun nachtragen_bietet_genau_die_arten_an_die_orten() {
        // Nur zu ihnen gibt es Spuren - und zu jeder kann eine ohne Zusammenfassung liegen bleiben.
        assertEquals((-1L..20L).filter(Kieselsport::ortet).toSet(), NACHTRAGBARE_ARTEN.toSet())
        assertEquals(NACHTRAGBARE_ARTEN.toSet().size, NACHTRAGBARE_ARTEN.size)
    }
}
