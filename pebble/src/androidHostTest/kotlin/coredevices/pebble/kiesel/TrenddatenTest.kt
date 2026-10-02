package coredevices.pebble.kiesel

import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TrenddatenTest {
    private val t1 = LocalDate.of(2026, 9, 1)
    private val t2 = LocalDate.of(2026, 9, 2)
    private val t3 = LocalDate.of(2026, 9, 3)

    @Test
    fun neu_gewinnt_der_speicher_fuellt_nur_luecken() {
        val v = Trenddaten.vereine(
            mapOf("schritte" to listOf(t2 to 5000.0, t3 to 6000.0)),
            mapOf("schritte" to listOf(t1 to 100.0, t2 to 200.0), "wasser" to listOf(t1 to 3.0)),
        )
        assertEquals(listOf(t1 to 100.0, t2 to 5000.0, t3 to 6000.0), v["schritte"])
        assertEquals(listOf(t1 to 3.0), v["wasser"])
    }

    @Test
    fun vorgeschichte_nur_vor_dem_ersten_eigenen_wert() {
        val t4 = LocalDate.of(2026, 9, 4)
        // Kiesel-Helper kennt t1 bis t4, Boulder misst ab t2 - mit einer Luecke an t3 (Uhr am Ladekabel).
        val v = Trenddaten.mitVorgeschichte(
            mapOf("schritte" to listOf(t2 to 5000.0, t4 to 7000.0)),
            mapOf("schritte" to listOf(t1 to 3000.0, t2 to 3100.0, t3 to 4200.0, t4 to 3300.0)),
        )
        // Die 4'200 Telefonschritte fuellen die Luecke an t3 nicht.
        assertEquals(listOf(t1 to 3000.0, t2 to 5000.0, t4 to 7000.0), v["schritte"])
    }

    @Test
    fun ohne_eigene_werte_zaehlt_die_ganze_vorgeschichte_ausser_aktiv() {
        val v = Trenddaten.mitVorgeschichte(
            emptyMap(),
            mapOf("hrv" to listOf(t1 to 40.0, t3 to 42.0), "aktiv" to listOf(t1 to 90.0)),
        )
        assertEquals(listOf(t1 to 40.0, t3 to 42.0), v["hrv"])
        // "aktiv" war in Kiesel-Helper die Trainingsdauer.
        assertNull(v["aktiv"])
    }

    @Test
    fun trendstand_kennt_den_ersten_eigenen_tag() {
        val s = Trendstand(mapOf("schlaf" to listOf(t2 to 420.0, t3 to 480.0)), mapOf("schlaf" to listOf(t1 to 400.0)))
        assertEquals(t2, s.eigenAb["schlaf"])
        assertEquals(listOf(t1 to 400.0, t2 to 420.0, t3 to 480.0), s.reihen["schlaf"])
    }

    @Test
    fun festgeschrieben_werden_nur_sicher_ganze_tage() {
        val heute = LocalDate.of(2026, 10, 3)
        val rand = heute.minusDays(Trenddaten.SICHER_TAGE + 1)
        val sicher = heute.minusDays(Trenddaten.SICHER_TAGE)
        val f = Trenddaten.festzuschreiben(
            mapOf(
                // Am Rand hat das Housekeeping schon geschnitten: 2'100 statt 11'000.
                "schritte" to listOf(rand to 2100.0, sicher to 9000.0, heute.minusDays(1) to 8000.0, heute to 3000.0),
                // Naechte loescht das Housekeeping nicht - sie gelten auch am Rand.
                "schlaf" to listOf(rand to 420.0, heute to 450.0),
                "wasser" to listOf(sicher to 1500.0),
            ),
            heute,
        )
        assertEquals(listOf(sicher to 9000.0, heute.minusDays(1) to 8000.0), f["schritte"])
        assertEquals(listOf(rand to 420.0), f["schlaf"])
        // Wasser bleibt in der Akte und wird nicht festgeschrieben.
        assertNull(f["wasser"])
    }

    @Test
    fun am_rand_spricht_das_archiv_nicht_die_angeschnittene_datenbank() {
        val heute = LocalDate.of(2026, 10, 3)
        val rand = heute.minusDays(Trenddaten.SICHER_TAGE + 1)
        val sicher = heute.minusDays(Trenddaten.SICHER_TAGE)
        val v = Trenddaten.mitArchiv(
            mapOf(
                "schritte" to listOf(rand to 2100.0, sicher to 9100.0),
                "schlaf" to listOf(rand to 430.0),
                "wasser" to listOf(rand to 1500.0),
            ),
            mapOf(
                "schritte" to listOf(rand.minusDays(1) to 7000.0, rand to 11000.0, sicher to 9000.0),
                "schlaf" to listOf(rand to 420.0),
                "wasser" to listOf(rand to 9.0),
            ),
            heute,
        )
        // Am Rand das volle Archiv, im sicheren Fenster die Datenbank (spaeter Sync).
        assertEquals(listOf(rand.minusDays(1) to 7000.0, rand to 11000.0, sicher to 9100.0), v["schritte"])
        // Naechte bleiben in der Datenbank ganz: sie gewinnt auch am Rand.
        assertEquals(listOf(rand to 430.0), v["schlaf"])
        // Das Archiv kennt nur Uhrreihen.
        assertEquals(listOf(rand to 1500.0), v["wasser"])
    }

    @Test
    fun einschlafen_aus_frueher_zurueckgeholtem_kiesel_helper() {
        val v = Trenddaten.mitEinschlafen(
            mapOf("schlaf_von" to listOf(t1 to 300.0, t2 to 330.0), "einschlafen" to listOf(t2 to 320.0)),
        )
        // Wo "einschlafen" schon steht, bleibt es.
        assertEquals(listOf(t1 to 300.0, t2 to 320.0), v["einschlafen"])
        val ohne = mapOf("hrv" to listOf(t1 to 40.0))
        assertEquals(ohne, Trenddaten.mitEinschlafen(ohne))
    }

    @Test
    fun ein_tag_zaehlt_erst_nach_dem_naechsten_sync() {
        // Letzte Daten gestern Mittag: gestern ist noch nicht fertig.
        assertEquals(t2, Trenddaten.uhrGrenze(t3, t2))
        assertEquals(t3, Trenddaten.uhrGrenze(t3, t3))
        assertEquals(t3, Trenddaten.uhrGrenze(t3, null))
    }

    @Test
    fun koffeinfreie_tage_mit_wasser_zaehlen_als_null() {
        val t4 = LocalDate.of(2026, 9, 4)
        val koffein = listOf(t2 to 160.0)
        val wasser = listOf(t1 to 1500.0, t2 to 2000.0, t3 to 1800.0, t4 to 900.0)
        // Ab t2 protokolliert Drinktervall; t4 ist heute und noch nicht fertig.
        assertEquals(listOf(t2 to 160.0, t3 to 0.0), Trenddaten.koffeinMitNull(koffein, wasser, t2, t4))
        // Ohne eigenes Glas weiss Boulder nicht, ob protokolliert wurde.
        assertEquals(koffein, Trenddaten.koffeinMitNull(koffein, wasser, null, t4))
    }

    @Test
    fun schlafmitte_zaehlt_ab_18_uhr_des_vortags() {
        // 23:00 bis 07:00 -> Mitte 03:00 -> neun Stunden nach 18 Uhr.
        val ein = t1.atTime(23, 0).toInstant(ZoneOffset.UTC)
        val auf = t2.atTime(7, 0).toInstant(ZoneOffset.UTC)
        assertEquals(540.0, Trenddaten.schlafMitte(ein, auf, ZoneOffset.UTC))
    }
}
