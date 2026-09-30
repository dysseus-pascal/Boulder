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
        val l = Sportrechnung.lage(beispiel, beispiel.first(), heute)
        assertEquals(Sport.Bike, l.letzte)
        assertEquals(1L, l.tageHer)
        assertEquals(4, l.anzahl)
        assertEquals(100, l.minuten)
        assertEquals(192, l.vorwoche)
    }

    @Test
    fun lage_ohne_training() {
        val l = Sportrechnung.lage(emptyList(), null, heute)
        assertNull(l.letzte)
        assertNull(l.tageHer)
        assertEquals(0, l.anzahl)
    }

    @Test
    fun acht_wochen_mit_den_leeren() {
        assertEquals(listOf(0L, 0, 0, 0, 0, 0, 192, 100), Sportrechnung.achtWochen(beispiel, heute))
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
        val l = Sportrechnung.lage(listOf(sonntag), sonntag, montag)
        assertEquals(1L, l.tageHer)
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
}
