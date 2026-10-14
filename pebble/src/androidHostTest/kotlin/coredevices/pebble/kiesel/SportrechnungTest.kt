package coredevices.pebble.kiesel

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SportrechnungTest {
    // Mittwoch, 30.09.2026 - die Beispielwoche aus dem Entwurf.
    private val heute = LocalDate.of(2026, 9, 30)
    private val mo = LocalDate.of(2026, 9, 28)
    private val di = LocalDate.of(2026, 9, 29)

    private val beispiel = listOf(
        Einheit(di, Sport.Bike, 22),
        Einheit(di, Sport.Laufen, 1),
        Einheit(di, Sport.Yoga, 60),
        Einheit(mo, Sport.Kraft, 17),
        Einheit(LocalDate.of(2026, 9, 27), Sport.Bike, 50),
        Einheit(LocalDate.of(2026, 9, 26), Sport.Wandern, 120),
        Einheit(LocalDate.of(2026, 9, 24), Sport.Schwimmen, 22),
    )

    @Test
    fun reihe_zeigt_sieben_tage_mit_laengster_art_vorn() {
        val r = Sportrechnung.reihe(beispiel, heute)
        assertEquals(7, r.size)
        assertEquals(mo, r.first().tag)
        assertEquals(Sport.Kraft, r[0].haupt)
        assertEquals(17, r[0].minuten)
        assertEquals(Sport.Yoga, r[1].haupt)
        assertEquals(listOf(Sport.Bike, Sport.Laufen), r[1].weitere)
        assertEquals(83, r[1].minuten)
        assertNull(r[2].haupt)
        assertEquals(0, r[6].minuten)
    }

    @Test
    fun lage_vergleicht_mit_der_vorwoche() {
        val l = Sportrechnung.lage(beispiel, heute)
        assertEquals(4, l.anzahl)
        assertEquals(100, l.minuten)
        assertEquals(192, l.vorwoche)
    }

    @Test
    fun lage_ohne_training() {
        val l = Sportrechnung.lage(emptyList(), heute)
        assertEquals(0, l.anzahl)
    }

    @Test
    fun liste_nach_wochen_juengste_zuerst() {
        val gruppen = Sportrechnung.nachWoche(beispiel) { it.tag }
        assertEquals(listOf(mo, LocalDate.of(2026, 9, 21)), gruppen.map { it.first })
        assertEquals(4, gruppen[0].second.size)
        assertEquals(Sport.Bike, gruppen[0].second.first().sport)
        // Der Sonntag gehoert zur Vorwoche.
        assertEquals(Sport.Bike, gruppen[1].second.first().sport)
    }

    @Test
    fun chips_haeufigste_zuerst_dann_laengste() {
        val z = Sportrechnung.zaehle(beispiel)
        assertEquals(Sport.Bike to 2, z[0])
        assertEquals(listOf(Sport.Wandern, Sport.Yoga, Sport.Schwimmen, Sport.Kraft, Sport.Laufen), z.drop(1).map { it.first })
    }

    @Test
    fun tempo_und_geschwindigkeit() {
        assertEquals(16.4, Sportrechnung.kmh(6000.0, 22 * 60 - 3)!!, 0.05)
        assertNull(Sportrechnung.kmh(0.0, 600))
        assertEquals(300L, Sportrechnung.tempo(1000.0, 300))
        assertNull(Sportrechnung.tempo(10.0, 600))
    }

    @Test
    fun deutsche_titel_werden_arten() {
        assertEquals(3L, Sportrechnung.artAusTitel("Kraft"))
        assertEquals(1L, Sportrechnung.artAusTitel("Bike Strasse/Gravel"))
        assertNull(Sportrechnung.artAusTitel("Bike Road/Gravel"))
        assertNull(Sportrechnung.artAusTitel(null))
    }

    @Test
    fun montag_ohne_training_nach_dem_sonntag() {
        val montag = LocalDate.of(2026, 10, 5)
        val sonntag = Einheit(LocalDate.of(2026, 10, 4), Sport.Bike, 50)
        val l = Sportrechnung.lage(listOf(sonntag), montag)
        assertEquals(0, l.anzahl)
        assertEquals(0, l.minuten)
        assertEquals(50, l.vorwoche)
        assertEquals(montag, Sportrechnung.reihe(listOf(sonntag), montag).first().tag)
    }

    @Test
    fun am_sonntag_beginnt_die_reihe_am_montag_derselben_woche() {
        val sonntag = LocalDate.of(2026, 10, 4)
        val r = Sportrechnung.reihe(listOf(Einheit(sonntag, Sport.Yoga, 30)), sonntag)
        assertEquals(mo, r.first().tag)
        assertEquals(sonntag, r.last().tag)
        assertEquals(Sport.Yoga, r.last().haupt)
    }

    @Test
    fun leere_liste_ohne_wochen() {
        assertEquals(emptyList(), Sportrechnung.nachWoche(emptyList<Einheit>()) { it.tag })
    }

    /** Eine Sitzung fuer [Sportrechnung.waehle]: Name, eigen, Minuten ab 10:00, Art. */
    private data class S(val name: String, val eigen: Boolean, val von: Long, val bis: Long, val art: Sport = Sport.Laufen)
    private val zehn = java.time.Instant.parse("2026-09-30T10:00:00Z")
    private fun waehle(vararg s: S) = Sportrechnung.waehle(
        s.toList(), { it.eigen }, { zehn.plusSeconds(it.von * 60) }, { zehn.plusSeconds(it.bis * 60) }, { it.art },
    ).map { it.name }

    @Test
    fun eigene_gewinnt_gegen_fremde_zur_selben_zeit() {
        assertEquals(listOf("kiesel"), waehle(S("kiesel", true, 0, 60), S("strava", false, 1, 61)))
    }

    @Test
    fun zwei_fremde_mit_demselben_lauf_zaehlen_einmal() {
        // Hersteller-App und Strava: derselbe Lauf, die laengere Fassung bleibt.
        assertEquals(listOf("strava"), waehle(S("samsung", false, 2, 58), S("strava", false, 0, 60)))
        // Zwei Trainings hintereinander, die sich kaum beruehren, bleiben beide.
        assertEquals(listOf("lauf", "rad"), waehle(S("lauf", false, 0, 60), S("rad", false, 55, 180)))
    }

    @Test
    fun ein_anderes_training_in_derselben_zeit_bleibt() {
        // Eine Stunde Kraft aus der Fitness-App, darin 20 min Laufband aus Strava.
        assertEquals(
            listOf("kraft", "band"),
            waehle(S("kraft", false, 0, 60, Sport.Kraft), S("band", false, 5, 25, Sport.Laufen)),
        )
    }

    @Test
    fun ein_erkannter_gang_ist_kein_training() {
        val gehen = androidx.health.connect.client.records.ExerciseSessionRecord.EXERCISE_TYPE_WALKING
        val wandern = androidx.health.connect.client.records.ExerciseSessionRecord.EXERCISE_TYPE_HIKING
        val auto = androidx.health.connect.client.records.metadata.Metadata.RECORDING_METHOD_AUTOMATICALLY_RECORDED
        val selbst = androidx.health.connect.client.records.metadata.Metadata.RECORDING_METHOD_ACTIVELY_RECORDED
        assertEquals(true, Sportrechnung.erkannterGang(gehen, auto))
        assertEquals(false, Sportrechnung.erkannterGang(gehen, selbst))
        assertEquals(false, Sportrechnung.erkannterGang(wandern, auto))
    }

    @Test
    fun dauer_ist_die_aktive_zeit_ohne_pausen() {
        // 80 min Sitzung mit 20 min Pause: in Liste und Woche zaehlen 60 min, wie auf der Uhr.
        val von = java.time.Instant.ofEpochSecond(1_000_000)
        val sitzung = androidx.health.connect.client.records.ExerciseSessionRecord(
            startTime = von, startZoneOffset = null, endTime = von.plusSeconds(4800), endZoneOffset = null,
            metadata = Kieselsport.vonDerUhr("probe"),
            exerciseType = androidx.health.connect.client.records.ExerciseSessionRecord.EXERCISE_TYPE_BIKING,
            segments = listOf(
                androidx.health.connect.client.records.ExerciseSegment(
                    von.plusSeconds(1800), von.plusSeconds(3000),
                    androidx.health.connect.client.records.ExerciseSegment.EXERCISE_SEGMENT_TYPE_PAUSE,
                ),
            ),
        )
        val t = TrainingEintrag(sitzung, Sport.Bike, true, 20_000.0)
        assertEquals(listOf(1800L to 3000L), t.pausen)
        assertEquals(3600L, t.sekunden)
        assertEquals(60L, t.minuten)
        // Das Tempo aus derselben Zeit: 20 km in 60 min.
        assertEquals(20.0, Sportrechnung.kmh(t.meter, t.sekunden)!!, 0.001)
    }

    @Test
    fun fremde_strecke_nur_von_derselben_app_und_im_zeitraum() {
        val start = java.time.Instant.ofEpochSecond(1_790_000_000)
        val ende = start.plusSeconds(1800)
        val d = listOf(
            Sportrechnung.FremdeDistanz("com.strava", start.plusSeconds(10), ende.plusSeconds(30), 5000.0),
            // Der Schrittzaehler einer anderen App zaehlt nicht mit
            Sportrechnung.FremdeDistanz("com.google.android.apps.fitness", start, ende, 4200.0),
            // Eine Distanz, die weit ueber die Sitzung hinausreicht, gehoert nicht zu ihr
            Sportrechnung.FremdeDistanz("com.strava", start.minusSeconds(3600), ende, 900.0),
        )
        kotlin.test.assertEquals(5000.0, Sportrechnung.fremdeStrecke("com.strava", start, ende, d))
        kotlin.test.assertEquals(0.0, Sportrechnung.fremdeStrecke("com.other", start, ende, d))
    }
}
