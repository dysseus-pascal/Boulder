package coredevices.pebble.kiesel

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Was die Seiten Stack und Water rechnen: die Kurwoche ("week 3 of 8") wie
 * die Uhr, die Einnahmezeit aus der Akte, Versaeumtes wie auf Health, die
 * Koffeinkurve von morgens bis zur Schlafenszeit und wie oft die Woche ihr
 * Wasserziel erreichte.
 */
class StackrechnungTest {
    private val zone = ZoneId.of("Europe/Zurich")
    private val tag = LocalDate.of(2026, 10, 6)
    private fun um(h: Int, m: Int, t: LocalDate = tag): Instant = t.atTime(h, m).atZone(zone).toInstant()

    private val kal = SupCycle.PLANFASSUNG_KALENDER
    private val kur = SupCycle.Platz("Creatine", LocalTime.of(8, 0), true, 1, wochenAn = 8, wochenAus = 4, anker = 20_000)

    @Test
    fun dieKurwocheZaehltDieEinnahmewochenAbDemAnker() {
        assertEquals(SupCycle.Kurwoche(1, 8), SupCycle.kurwoche(kur, 20_000, kal))
        assertEquals(SupCycle.Kurwoche(1, 8), SupCycle.kurwoche(kur, 20_006, kal))
        assertEquals(SupCycle.Kurwoche(2, 8), SupCycle.kurwoche(kur, 20_007, kal))
        assertEquals(SupCycle.Kurwoche(3, 8), SupCycle.kurwoche(kur, 20_016, kal))
        assertEquals(SupCycle.Kurwoche(8, 8), SupCycle.kurwoche(kur, 20_055, kal))
        // Vier Wochen Pause, dann beginnt die Kur von vorn.
        assertNull(SupCycle.kurwoche(kur, 20_056, kal))
        assertNull(SupCycle.kurwoche(kur, 20_083, kal))
        assertEquals(SupCycle.Kurwoche(1, 8), SupCycle.kurwoche(kur, 20_084, kal))
    }

    /**
     * DIESELBE KUR WIE AUF DER UHR: eine Kurwoche gibt es genau an den Tagen,
     * an denen die Uhr das Praeparat faellig rechnet ([SupCycle.faelligAm]) -
     * auch alle zwei Tage und in beiden Fassungen des Plans.
     */
    @Test
    fun eineKurwocheGibtEsGenauAnDenFaelligenTagen() {
        for (platz in listOf(kur, kur.copy(alle = 2), kur.copy(wochenAus = 0), kur.copy(wochenAn = 3, wochenAus = 1))) {
            for (fassung in listOf(SupCycle.PLANFASSUNG_ALT, kal)) {
                // Einnahmewochen ohne Pause liefen in der alten Fassung endlos - dort keine Kurwoche.
                if (platz.wochenAus == 0 && fassung == SupCycle.PLANFASSUNG_ALT) continue
                for (t in 20_000L..20_200L) {
                    val faellig = SupCycle.faelligAm(platz, t, fassung)
                    val woche = SupCycle.kurwoche(platz, t, fassung)
                    if (faellig) assertTrue(woche != null, "$platz, Fassung $fassung, Tag $t")
                    if (platz.alle == 1) assertEquals(faellig, woche != null, "$platz, Fassung $fassung, Tag $t")
                }
            }
        }
    }

    @Test
    fun ohneKurUndOhneEndeKeineKurwoche() {
        assertNull(SupCycle.kurwoche(kur.copy(wochenAn = 0), 20_016, kal))
        assertNull(SupCycle.kurwoche(kur.copy(benutzt = false), 20_016, kal))
        // Ohne Pause: seit Fassung 2 eine Kur, die nach acht Wochen endet; vorher endlos.
        val ohnePause = kur.copy(wochenAus = 0)
        assertEquals(SupCycle.Kurwoche(8, 8), SupCycle.kurwoche(ohnePause, 20_055, kal))
        assertNull(SupCycle.kurwoche(ohnePause, 20_056, kal))
        assertNull(SupCycle.kurwoche(ohnePause, 20_010, SupCycle.PLANFASSUNG_ALT))
        assertEquals(SupCycle.Kurwoche(3, 8), SupCycle.kurwoche(kur, 20_016, SupCycle.PLANFASSUNG_ALT))
    }

    @Test
    fun dieListeMitPlatzIstDieListeVonHeute() {
        val s = SupCycle.Stand(tag, listOf("A", "", "C", "D"), faellig = 0b1101, genommen = 0b0100)
        assertEquals(listOf(0, 2, 3), s.heuteJePlatz { "Slot $it" }.map { it.first })
        assertEquals(s.heute { "Slot $it" }, s.heuteJePlatz { "Slot $it" }.map { it.second })
    }

    // --- Praeparate ---

    private val stand = SupCycle.Stand(
        tag, listOf("Vitamin D", "Omega-3", "Creatine", "Magnesium", "Zinc"), faellig = 0b11111, genommen = 0b10111,
        plan = listOf(
            SupCycle.Platz("Vitamin D", LocalTime.of(8, 0), true, 1, 0, 0, 0),
            SupCycle.Platz("Omega-3", LocalTime.of(8, 0), true, 1, 0, 0, 0),
            SupCycle.Platz("Creatine", LocalTime.of(8, 0), true, 1, 8, 4, tag.toEpochDay() - 16),
            SupCycle.Platz("Magnesium", LocalTime.of(20, 0), true, 1, 0, 0, 0),
            SupCycle.Platz("Zinc", LocalTime.of(12, 0), true, 1, 0, 0, 0),
        ),
        fassung = SupCycle.PLANFASSUNG_KALENDER,
    )

    private val eintraege = listOf(
        Tageseintrag(um(8, 2), true, "Vitamin D", null, "a", eigen = true, kennung = SupCycle.kennung(tag, 0, "Vitamin D")),
        Tageseintrag(um(8, 3), true, "Omega-3", null, "b", eigen = true, kennung = SupCycle.kennung(tag, 1, "Omega-3")),
        // Ein Eintrag bis Boulder 0.33, ohne Kennung: am Namen gefunden.
        Tageseintrag(um(8, 5), true, "Creatine", null, "c", eigen = true, kennung = null),
        // Der Haken von Magnesium ist zurueckgenommen, sein Eintrag steht noch: offen ist offen.
        Tageseintrag(um(9, 0), true, "Magnesium", null, "m", eigen = true, kennung = SupCycle.kennung(tag, 3, "Magnesium")),
        // Zink aus einer anderen App: die Zeit ja, das Loeschen nicht.
        Tageseintrag(um(12, 30), true, "Zinc", null, "z", eigen = false, kennung = null),
        Tageseintrag(um(6, 44), false, "Espresso", 63.0, "k", eigen = true, kennung = "koffein-1"),
    )

    @Test
    fun dieEinnahmezeitKommtAusDerAkte() {
        val p = Stackrechnung.praeparate(stand, eintraege, um(13, 40), zone) { "Slot $it" }
        assertEquals(listOf("Vitamin D", "Omega-3", "Creatine", "Magnesium", "Zinc"), p.map { it.name })
        assertEquals(listOf(true, true, true, false, true), p.map { it.genommen })
        assertEquals(listOf(um(8, 2), um(8, 3), um(8, 5), null, um(12, 30)), p.map { it.um })
        // Aendern darf ein langer Druck nur Boulders eigene Eintraege.
        assertEquals(listOf("a", "b", "c", null, null), p.map { it.eintrag?.id })
        assertEquals(listOf(null, null, SupCycle.Kurwoche(3, 8), null, null), p.map { it.kur })
        assertEquals(LocalTime.of(20, 0), p[3].plan)
    }

    @Test
    fun versaeumtIstErstNachDerPlanzeit() {
        fun magnesium(h: Int, m: Int) = Stackrechnung.praeparate(stand, eintraege, um(h, m), zone) { "" }[3]
        assertFalse(magnesium(13, 40).versaeumt)
        assertFalse(magnesium(19, 59).versaeumt)
        assertTrue(magnesium(20, 0).versaeumt)
        assertTrue(magnesium(20, 30).versaeumt)
        // Genommenes ist nie versaeumt, auch lange nach seiner Planzeit.
        assertTrue(Stackrechnung.praeparate(stand, eintraege, um(23, 0), zone) { "" }.filter { it.genommen }.none { it.versaeumt })
    }

    // --- Koffeinkurve ---

    private val espresso = listOf(um(6, 44) to 63.0)

    @Test
    fun dieKurveReichtVonSechsBisNachDerSchlafenszeit() {
        val k = Stackrechnung.koffeinkurve(espresso, um(13, 40), um(21, 40, tag.minusDays(1)), zone)
        assertEquals(um(6, 0), k.von)
        assertEquals(um(22, 0), k.bis)
        assertEquals(um(21, 40), k.bett)
        // Aufsteigend, ohne Doppel, das Jetzt dabei, keine Luecke ueber fuenf Minuten.
        assertEquals(k.punkte.map { it.first }.sorted().distinct(), k.punkte.map { it.first })
        assertTrue(k.punkte.any { it.first == um(13, 40) })
        assertEquals(k.von, k.punkte.first().first)
        assertEquals(k.bis, k.punkte.last().first)
        assertTrue(k.punkte.zipWithNext().all { (a, b) -> Duration.between(a.first, b.first) <= Duration.ofMinutes(5) })
        // Die Werte sind die des Koffeinmodells der App, nicht eine eigene Rechnung.
        k.punkte.forEach { (t, mg) -> assertEquals(Gesundheitsrechnung.koffeinAktiv(espresso, t), mg, "$t") }
        // Der Sprung beim Trinken: eine Sekunde davor nichts, darauf der ganze Espresso.
        val i = k.punkte.indexOfFirst { it.first == um(6, 44) }
        assertEquals(um(6, 44).minusSeconds(1) to 0.0, k.punkte[i - 1])
        assertEquals(63.0, k.punkte[i].second)
        assertEquals(63.0, k.spitze)
    }

    @Test
    fun einKaffeeVorSechsZiehtDenAnfangVor() {
        val k = Stackrechnung.koffeinkurve(listOf(um(5, 30) to 80.0), um(9, 0), null, zone)
        assertEquals(um(5, 0), k.von)
        // Ohne Nacht ist die Schlafenszeit 22:00 - die Kurve endet eine Stunde danach, nicht auf ihr.
        assertEquals(um(22, 0), k.bett)
        assertEquals(um(23, 0), k.bis)
        // Ein Kaffee von gestern Abend zieht nichts vor: er wirkt nur noch nach.
        assertEquals(um(6, 0), Stackrechnung.koffeinkurve(listOf(um(22, 30, tag.minusDays(1)) to 80.0), um(9, 0), null, zone).von)
    }

    @Test
    fun nachMitternachtGehoertDieKurveZumTagDavor() {
        // Um 0:30 begann der laufende Tag gestern um sechs; die Schlafenszeit 23:30 ist vorbei.
        val k = Stackrechnung.koffeinkurve(espresso, um(0, 30, tag.plusDays(1)), um(23, 30, tag.minusDays(1)), zone)
        assertEquals(um(6, 0), k.von)
        assertNull(k.bett)
        assertEquals(um(1, 0, tag.plusDays(1)), k.bis)
        // Eine Schlafenszeit nach Mitternacht liegt in der kommenden Nacht.
        val spaet = Stackrechnung.koffeinkurve(espresso, um(13, 40), um(1, 15, tag), zone)
        assertEquals(um(1, 15, tag.plusDays(1)), spaet.bett)
        assertEquals(um(2, 0, tag.plusDays(1)), spaet.bis)
    }

    // --- Wasser ---

    @Test
    fun einTagMitGenauDemZielZaehltAlsErreicht() {
        val woche = listOf(2600, 2100, 2700, 2500, 1800, 2900, 500).mapIndexed { i, ml -> tag.minusDays(6L - i) to ml }
        assertEquals(4, Stackrechnung.zielErreicht(woche, 2500))
        assertEquals(0, Stackrechnung.zielErreicht(woche, 3000))
        assertEquals(7, Stackrechnung.zielErreicht(woche, 500))
    }
}
