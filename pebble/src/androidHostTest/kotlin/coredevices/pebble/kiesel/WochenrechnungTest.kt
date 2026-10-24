package coredevices.pebble.kiesel

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class WochenrechnungTest {
    // Ein Mittwoch - die laufende Woche beginnt am 28.09.
    private val heute = LocalDate.of(2026, 9, 30)

    @Test
    fun tag_mit_zwei_arten_nennt_die_laengere_zuerst() {
        val tage = Wochenrechnung.tage(listOf(
            Einheit(heute, Sport.Yoga, 20),
            Einheit(heute, Sport.Bike, 90),
        ))
        assertEquals(listOf(Sport.Bike, Sport.Yoga), tage.getValue(heute).arten)
        assertEquals(110, tage.getValue(heute).minuten)
    }

    @Test
    fun acht_wochen_aelteste_zuerst_bis_zur_laufenden() {
        val wochen = Wochenrechnung.wochen(emptyList(), heute)
        assertEquals(8, wochen.size)
        assertEquals(LocalDate.of(2026, 9, 28), wochen.last().montag)
        assertEquals(LocalDate.of(2026, 8, 10), wochen.first().montag)
        assertEquals(0, wochen.sumOf { it.minuten })
    }

    @Test
    fun woche_stapelt_in_fester_reihenfolge_und_zaehlt_sonntag_mit() {
        val wochen = Wochenrechnung.wochen(listOf(
            Einheit(LocalDate.of(2026, 9, 27), Sport.Kraft, 45),   // Sonntag der Vorwoche
            Einheit(LocalDate.of(2026, 9, 21), Sport.Laufen, 30),  // Montag der Vorwoche
            Einheit(LocalDate.of(2026, 9, 28), Sport.Yoga, 60),
            Einheit(LocalDate.of(2026, 7, 1), Sport.Laufen, 99),   // ausserhalb
        ), heute)
        assertEquals(listOf(Sport.Laufen to 30L, Sport.Kraft to 45L), wochen[6].teile)
        assertEquals(listOf(Sport.Yoga to 60L), wochen[7].teile)
        assertEquals(135, wochen.sumOf { it.minuten })
    }

    @Test
    fun die_drei_raeder_liegen_im_stapel_beieinander() {
        // Wie im Bike-Menue der Uhr: Strasse/Gravel, MTB, Indoor - kein Wandern dazwischen.
        val woche = Wochenrechnung.wochen(listOf(
            Einheit(heute, Sport.Wandern, 40),
            Einheit(heute, Sport.Indoor, 45),
            Einheit(heute, Sport.Bike, 60),
            Einheit(heute, Sport.Mtb, 50),
        ), heute).last()
        assertEquals(listOf(Sport.Bike, Sport.Mtb, Sport.Indoor, Sport.Wandern), woche.teile.map { it.first })
    }

    @Test
    fun becken_und_freiwasser_liegen_beieinander_und_custom_zuletzt() {
        // Wie auf der Uhr: Becken und Freiwasser im Schwimm-Menue, Custom am Ende - es gehoert zu keiner Art.
        val woche = Wochenrechnung.wochen(listOf(
            Einheit(heute, Sport.Offen, 30),
            Einheit(heute, Sport.Atem, 10),
            Einheit(heute, Sport.Freiwasser, 45),
            Einheit(heute, Sport.Anderes, 20),
            Einheit(heute, Sport.Schwimmen, 40),
            Einheit(heute, Sport.Laufen, 25),
        ), heute).last()
        assertEquals(
            listOf(Sport.Laufen, Sport.Schwimmen, Sport.Freiwasser, Sport.Atem, Sport.Anderes, Sport.Offen),
            woche.teile.map { it.first },
        )
    }

    @Test
    fun verteilung_nach_zeit_mit_anzahl_und_prozent() {
        val anteile = Wochenrechnung.verteilung(listOf(
            Einheit(heute, Sport.Laufen, 30),
            Einheit(heute, Sport.Laufen, 30),
            Einheit(heute, Sport.Bike, 180),
            Einheit(heute, Sport.Yoga, 0),
        ))
        assertEquals(listOf(Sport.Bike, Sport.Laufen), anteile.map { it.sport })
        assertEquals(Anteil(Sport.Laufen, 2, 60, 25), anteile[1])
        assertEquals(75, anteile[0].prozent)
    }

    @Test
    fun verteilung_ohne_zeit_ist_leer() {
        assertEquals(emptyList(), Wochenrechnung.verteilung(listOf(Einheit(heute, Sport.Kraft, 0))))
    }
}
