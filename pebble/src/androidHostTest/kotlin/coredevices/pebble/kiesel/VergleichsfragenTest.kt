package coredevices.pebble.kiesel

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Die Fragen von "What makes a difference" ohne Telefon pruefen. Ein Versatz
 * um einen Tag oder eine verschobene Schwelle sieht am Geraet plausibel aus -
 * hier faellt er auf.
 */
class VergleichsfragenTest {
    private val heute = LocalDate.of(2026, 10, 2)
    private val gestern = heute.minusDays(1)

    private fun frage(art: Vergleichsfragen.Art) = Vergleichsfragen.FRAGEN.single { it.art == art }

    @Test
    fun jede_frage_steht_auf_der_seite_ihrer_zielgroesse() {
        // Keine Frage mehr auf zwei Seiten: die Seite ist die ihrer Zielgroesse.
        Vergleichsfragen.FRAGEN.forEach { f -> assertEquals(Vergleichsfragen.gruppeVon(f.ziel), f.gruppe) }
        assertEquals(Vergleichsfragen.Art.entries.toSet(), Vergleichsfragen.FRAGEN.map { it.art }.toSet())
        val alle = listOf("herz", "schlaf", "bewegung", "ernaehrung").flatMap { Vergleichsfragen.fuer(it, mitKoffein = true) }
        assertEquals(Vergleichsfragen.FRAGEN.size, alle.size)
        assertEquals(setOf(Vergleichsfragen.Art.TIEF_KOFFEIN, Vergleichsfragen.Art.SCHLAF_AKTIV), Vergleichsfragen.fuer("schlaf", true).map { it.art }.toSet())
    }

    @Test
    fun koffeinfragen_nur_mit_drinktervall() {
        assertTrue(Vergleichsfragen.fuer("ernaehrung", mitKoffein = false).isEmpty())
        assertFalse(Vergleichsfragen.fuer("schlaf", mitKoffein = false).any { it.mitKoffein })
    }

    @Test
    fun der_ruhepuls_haengt_vom_tag_davor_ab() {
        // Gestern aktiv, heute ruhig: die Nacht auf heute gehoert zu "nach aktivem Tag".
        val d = mapOf("aktiv" to listOf(gestern to 45.0, heute to 5.0))
        val zu = frage(Vergleichsfragen.Art.RUHE_AKTIV).zuordnung(d)
        assertEquals(true, zu(heute))
        assertEquals(false, zu(heute.plusDays(1)))
        // Ueber den Tag vor gestern ist nichts bekannt.
        assertNull(zu(gestern))
    }

    @Test
    fun der_tiefschlaf_haengt_vom_kaffee_des_vortags_ab() {
        val d = mapOf("koffein_mg" to listOf(gestern to 240.0, heute to 0.0))
        val zu = frage(Vergleichsfragen.Art.TIEF_KOFFEIN).zuordnung(d)
        assertEquals(true, zu(heute))
        assertEquals(false, zu(heute.plusDays(1)))
    }

    @Test
    fun schritte_haengen_von_der_nacht_desselben_tags_ab() {
        // Die Nacht ist auf den Tag des Aufwachens gebucht - sie liegt vor den Schritten dieses Tags.
        val d = mapOf("schlaf" to listOf(heute to 400.0))
        assertEquals(true, frage(Vergleichsfragen.Art.SCHRITTE_KURZ).zuordnung(d)(heute))
        assertNull(frage(Vergleichsfragen.Art.SCHRITTE_KURZ).zuordnung(d)(gestern))
    }

    @Test
    fun sehr_kurz_ist_unter_sieben_stunden_wie_auf_health() {
        assertEquals(7 * 60, Vergleichsfragen.SEHR_KURZ_MIN)
        val zu = frage(Vergleichsfragen.Art.HRV_KURZ).zuordnung(mapOf("schlaf" to listOf(gestern to 419.0, heute to 420.0)))
        assertEquals(true, zu(gestern))
        // 7:00 h heisst auf Health "Short night", nicht "Very short" - also Gruppe B.
        assertEquals(false, zu(heute))
    }

    @Test
    fun schwellen_fuer_schritte_bettzeit_und_aktivminuten() {
        val schritte = frage(Vergleichsfragen.Art.HRV_SCHRITTE).zuordnung(mapOf("schritte" to listOf(gestern to 10_000.0, heute to 9_999.0)))
        assertEquals(true, schritte(heute))
        assertEquals(false, schritte(heute.plusDays(1)))
        // 23:00 ist 300 min nach 18 Uhr: genau 23 Uhr ist noch nicht spaet.
        val spaet = frage(Vergleichsfragen.Art.RUHE_SPAET).zuordnung(mapOf("einschlafen" to listOf(gestern to 300.0, heute to 301.0)))
        assertEquals(false, spaet(gestern))
        assertEquals(true, spaet(heute))
        val aktiv = frage(Vergleichsfragen.Art.SCHLAF_AKTIV).zuordnung(mapOf("aktiv" to listOf(gestern to 29.0)))
        assertEquals(false, aktiv(heute))
    }

    @Test
    fun nachtziele_zaehlen_bis_heute_tagesziele_bis_gestern() {
        assertEquals(heute.plusDays(1), Vergleichsfragen.grenze("hrv", heute))
        assertEquals(heute, Vergleichsfragen.grenze("schritte", heute))
        assertEquals(heute, Vergleichsfragen.grenze("koffein_mg", heute))
    }

    @Test
    fun rechne_vergleicht_die_letzten_dreissig_tage() {
        // Gerade Tage sehr kurz geschlafen und niedrige HRV, ungerade lang und hohe HRV.
        val tage = (0L..29L).map { heute.minusDays(it) }
        val d = mapOf(
            "schlaf" to tage.map { it to if (it.dayOfMonth % 2 == 0) 360.0 else 480.0 },
            "hrv" to tage.map { it to if (it.dayOfMonth % 2 == 0) 38.0 + it.dayOfMonth % 3 else 50.0 + it.dayOfMonth % 3 },
        )
        val (f, v) = Vergleichsfragen.rechne(d, "herz", heute, mitKoffein = false).first { it.first.art == Vergleichsfragen.Art.HRV_KURZ }
        assertEquals("hrv", f.ziel)
        assertTrue(v.genug)
        assertTrue(v.klar)
        assertTrue(v.unterschied < -10)
    }

    @Test
    fun wochenende_vergleicht_nur_den_ruhepuls() {
        assertTrue(Vergleichsfragen.vergleichtWochenende("ruhepuls"))
        assertFalse(Vergleichsfragen.vergleichtWochenende("hrv"))
        assertFalse(Vergleichsfragen.vergleichtWochenende("schritte"))
    }
}
