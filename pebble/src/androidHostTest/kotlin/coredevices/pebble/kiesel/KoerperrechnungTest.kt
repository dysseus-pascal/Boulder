package coredevices.pebble.kiesel

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KoerperrechnungTest {
    private val k = Koerperrechnung
    private val zone: ZoneId = ZoneOffset.UTC
    private val heute: LocalDate = LocalDate.of(2026, 10, 2)

    private fun um(tag: LocalDate, stunde: Int = 7, minute: Int = 0): Instant = tag.atTime(stunde, minute).toInstant(ZoneOffset.UTC)
    private fun m(tageZurueck: Long, wert: Double, stunde: Int = 7, minute: Int = 0) = Koerpermessung(um(heute.minusDays(tageZurueck), stunde, minute), wert)
    private fun tag(tageZurueck: Long, wert: Double) = Koerpertag(heute.minusDays(tageZurueck), um(heute.minusDays(tageZurueck)), wert)

    @Test
    fun je_tag_gilt_der_letzte_satz_der_waage() {
        val tage = k.tage(listOf(m(0, 78.9, 7, 1), m(0, 78.4, 7, 2), m(1, 79.0), m(0, 78.6, 6, 59)), zone)
        assertEquals(listOf(heute.minusDays(1), heute), tage.map { it.tag })
        assertEquals(78.4, tage.last().wert)
    }

    @Test
    fun tage_folgen_der_ortszeit_nicht_utc() {
        // 23:30 UTC ist in Zuerich (Sommerzeit) schon der naechste Tag.
        val tage = k.tage(listOf(Koerpermessung(um(heute.minusDays(1), 23, 30), 78.0)), ZoneId.of("Europe/Zurich"))
        assertEquals(heute, tage.single().tag)
    }

    @Test
    fun veraenderung_gegen_den_tag_vor_dreissig_tagen() {
        val tage = listOf(tag(30, 80.0), tag(10, 79.5), tag(0, 79.4))
        assertEquals(-0.6, k.veraenderung(tage, heute)!!, 1e-9)
    }

    @Test
    fun veraenderung_nimmt_den_naechsten_tag_davor() {
        // 33 und 36 Tage zurueck liegen im Spielraum - der naechste am Ziel gewinnt.
        val tage = listOf(tag(36, 82.0), tag(33, 81.0), tag(0, 80.0))
        assertEquals(-1.0, k.veraenderung(tage, heute)!!, 1e-9)
    }

    @Test
    fun veraenderung_davor_geht_vor_danach() {
        // 32 zurueck (2 vor dem Ziel) schlaegt 29 zurueck (1 danach): erst davor suchen.
        val tage = listOf(tag(32, 81.0), tag(29, 85.0), tag(0, 80.0))
        assertEquals(-1.0, k.veraenderung(tage, heute)!!, 1e-9)
    }

    @Test
    fun veraenderung_sonst_der_erste_tag_danach() {
        val tage = listOf(tag(45, 90.0), tag(26, 81.0), tag(24, 70.0), tag(0, 80.0))
        assertEquals(-1.0, k.veraenderung(tage, heute)!!, 1e-9)
    }

    @Test
    fun keine_veraenderung_ausserhalb_des_spielraums() {
        assertNull(k.veraenderung(listOf(tag(38, 82.0), tag(0, 80.0)), heute))
        assertNull(k.veraenderung(listOf(tag(22, 82.0), tag(0, 80.0)), heute))
        assertNull(k.veraenderung(listOf(tag(0, 80.0)), heute))
        assertNull(k.veraenderung(emptyList(), heute))
    }

    @Test
    fun keine_veraenderung_wenn_die_letzte_messung_alt_ist() {
        // Zuletzt vor acht Tagen gewogen: "in 30 days" waeren dann nur 22.
        assertNull(k.veraenderung(listOf(tag(30, 82.0), tag(8, 80.0)), heute))
        assertNotNull(k.veraenderung(listOf(tag(30, 82.0), tag(7, 80.0)), heute))
    }

    @Test
    fun bmi_mit_einer_nachkommastelle() {
        assertEquals(24.2, k.bmi(78.4, 1.80))
        assertNull(k.bmi(78.4, 0.0))
        assertNull(k.bmi(0.0, 1.8))
    }

    @Test
    fun zusammensetzung_mit_bmi_und_wasseranteil_vom_selben_tag() {
        val stand = Koerperstand(
            gewicht = listOf(m(2, 80.0), m(0, 78.4)),
            wasser = listOf(m(0, 43.1)),
            fett = listOf(m(3, 18.2)),
            groesse = Koerpermessung(Instant.parse("2023-05-01T10:00:00Z"), 1.80),
        )
        val z = k.zusammensetzung(stand, zone)
        assertEquals(55, z.wasserProzent)
        assertEquals(24.2, z.bmi!!.wert)
        assertEquals(heute, z.bmi!!.tag)
        assertEquals(18.2, z.fett!!.wert)
        assertNull(z.magermasse)
        assertFalse(z.leer)
    }

    @Test
    fun wasser_ohne_gewicht_desselben_tages_bleibt_ohne_prozent() {
        val z = k.zusammensetzung(Koerperstand(gewicht = listOf(m(1, 80.0)), wasser = listOf(m(0, 43.1))), zone)
        assertEquals(43.1, z.wasser!!.wert)
        assertNull(z.wasserProzent)
    }

    @Test
    fun ohne_groesse_kein_bmi() {
        assertNull(k.zusammensetzung(Koerperstand(gewicht = listOf(m(0, 80.0))), zone).bmi)
        assertTrue(k.zusammensetzung(Koerperstand(), zone).leer)
        assertTrue(Koerperstand().leer)
    }

    @Test
    fun zeile_zeigt_gewicht_mit_fett_und_veraenderung() {
        val stand = Koerperstand(gewicht = listOf(m(30, 79.0), m(0, 78.4)), fett = listOf(m(0, 18.2)))
        val z = k.zeile(stand, heute, zone)!!
        assertTrue(z.istGewicht)
        assertEquals(78.4, z.wert.wert)
        assertEquals(18.2, z.fett!!.wert)
        assertEquals(-0.6, z.veraenderung!!, 1e-9)
        assertFalse(z.alt)
    }

    @Test
    fun zeile_ohne_gewicht_zeigt_das_fett() {
        val z = k.zeile(Koerperstand(fett = listOf(m(31, 19.0), m(1, 18.2))), heute, zone)!!
        assertFalse(z.istGewicht)
        assertNull(z.fett)
        assertEquals(18.2, z.wert.wert)
        assertEquals(-0.8, z.veraenderung!!, 1e-9)
    }

    @Test
    fun zeile_nur_mit_werten_der_letzten_neunzig_tage() {
        assertNull(k.zeile(Koerperstand(gewicht = listOf(m(91, 80.0)), magermasse = listOf(m(0, 60.0))), heute, zone))
        val alt = k.zeile(Koerperstand(gewicht = listOf(m(90, 80.0))), heute, zone)!!
        assertTrue(alt.alt)
        assertNull(alt.veraenderung)
        // Fett aus dem Fenster neben einem Gewicht von heute zaehlt nicht.
        assertNull(k.zeile(Koerperstand(gewicht = listOf(m(0, 80.0)), fett = listOf(m(120, 20.0))), heute, zone)!!.fett)
    }

    @Test
    fun fett_neben_dem_gewicht_nur_aus_derselben_woche() {
        // Einmal vor 60 Tagen auf der Smart-Waage, seither nur Gewicht: kein Fett in der Zeile.
        assertNull(k.zeile(Koerperstand(gewicht = listOf(m(1, 79.0), m(0, 78.4)), fett = listOf(m(60, 18.2))), heute, zone)!!.fett)
        // Barfuss nur am Wochenende: das Fett von vor sieben Tagen gilt noch, das von vor acht nicht.
        assertEquals(18.2, k.zeile(Koerperstand(gewicht = listOf(m(0, 78.4)), fett = listOf(m(7, 18.2))), heute, zone)!!.fett!!.wert)
        assertNull(k.zeile(Koerperstand(gewicht = listOf(m(0, 78.4)), fett = listOf(m(8, 18.2))), heute, zone)!!.fett)
        // Altes Gewicht: das Fett aus seiner Woche, nicht das neueste.
        val alt = k.zeile(Koerperstand(gewicht = listOf(m(40, 80.0)), fett = listOf(m(41, 19.0), m(0, 18.0))), heute, zone)!!
        assertEquals(19.0, alt.fett!!.wert)
    }

    @Test
    fun medianlinie_schluckt_ausreisser_und_bricht_an_luecken() {
        val tage = listOf(tag(40, 80.0), tag(20, 79.0), tag(19, 95.0), tag(18, 79.2), tag(17, 79.1))
        val linie = k.medianlinie(tage)
        assertEquals(2, linie.size)
        assertEquals(listOf(heute.minusDays(40) to 80.0), linie[0])
        // Der Ausreisser 95 liegt in jedem Fenster mit drei normalen Werten - der Median bleibt unten.
        assertEquals(79.15, linie[1].first { it.first == heute.minusDays(19) }.second, 1e-9)
        assertTrue(linie[1].all { it.second < 80.0 })
    }

    @Test
    fun achse_rund_mit_hoechstens_drei_feldern() {
        val a = k.achse(77.8, 79.4)
        assertEquals(Achse(77.0, 80.0, 1.0), a)
        assertEquals(listOf(77.0, 78.0, 79.0, 80.0), a.linien)
        assertEquals(listOf(77.5, 78.0, 78.5, 79.0), k.achse(78.4, 78.4).linien)
        assertEquals(Achse(70.0, 85.0, 5.0), k.achse(71.0, 84.0))
        // Eng beieinander mit kleinerer Mindestspanne: Bruchteile ohne Rundungsreste.
        assertEquals(listOf(18.0, 18.2, 18.4), k.achse(18.05, 18.35, mindestSpanne = 0.2).linien)
    }

    @Test
    fun pfund_und_fuss() {
        assertEquals(172.8, Math.round(k.masse(78.4, true) * 10) / 10.0)
        assertEquals(78.4, k.masse(78.4, false))
        assertEquals(5 to 11, k.fussZoll(1.80))
        assertEquals(6 to 0, k.fussZoll(1.83))
    }

    @Test
    fun koerperrechte_sind_in_den_kiesel_rechten() {
        assertEquals(7, KOERPER_RECHTE.size)
        assertTrue(KIESEL_RECHTE.containsAll(KOERPER_RECHTE))
        assertTrue("android.permission.health.READ_WEIGHT" in KOERPER_RECHTE)
    }
}
