package coredevices.pebble.kiesel

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Die Spalten der Trends. Die Schluessel sind Teil von Tagesspeicher und
 * Sicherung - aendert sich einer, findet eine alte Sicherung ihre Werte
 * nicht mehr. Darum stehen sie hier noch einmal ausgeschrieben.
 */
class SpalteTest {
    private val heute = LocalDate.of(2026, 10, 3)

    @Test
    fun schluessel_bleiben_wie_gespeichert() {
        assertEquals(
            listOf(
                "schritte", "aktiv", "schlaf", "tief", "schlaf_mitte", "einschlafen",
                "ruhepuls", "hrv", "wasser", "koffein_mg", "supp_faellig", "supp_genommen",
            ),
            Spalte.entries.map { it.schluessel },
        )
    }

    @Test
    fun nachtspalten_zaehlen_bis_heute() {
        assertEquals(
            setOf(Spalte.SCHLAF, Spalte.TIEF, Spalte.SCHLAF_MITTE, Spalte.EINSCHLAFEN, Spalte.RUHEPULS, Spalte.HRV),
            Spalte.entries.filter { it.nacht }.toSet(),
        )
        assertEquals(heute.plusDays(1), Spalte.TIEF.grenze(heute))
        assertEquals(heute, Spalte.WASSER.grenze(heute))
    }

    @Test
    fun jede_spalte_hat_ihre_trendseite() {
        val seiten = Spalte.entries.groupBy({ it.gruppe }, { it.schluessel })
        assertEquals(listOf("schritte", "aktiv"), seiten["bewegung"])
        assertEquals(listOf("schlaf", "tief", "schlaf_mitte", "einschlafen"), seiten["schlaf"])
        assertEquals(listOf("ruhepuls", "hrv"), seiten["herz"])
        assertEquals(listOf("wasser", "koffein_mg", "supp_faellig", "supp_genommen"), seiten["ernaehrung"])
    }

    @Test
    fun reihen_lesen_und_schreiben_unter_dem_schluessel() {
        val reihen = mutableMapOf<String, List<Pair<LocalDate, Double>>>()
        reihen[Spalte.KOFFEIN] = listOf(heute to 80.0)
        assertEquals(listOf(heute to 80.0), reihen["koffein_mg"])
        assertEquals(reihen["koffein_mg"], reihen[Spalte.KOFFEIN])
        assertEquals(setOf("schritte", "aktiv"), Spalte.schluessel(Spalte.SCHRITTE, Spalte.AKTIV))
    }
}
