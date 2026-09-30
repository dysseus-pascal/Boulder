package coredevices.pebble.kiesel

import io.rebble.libpebblecommon.database.entity.NachtAbschnittEntity
import io.rebble.libpebblecommon.database.entity.NachtEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GesundheitsrechnungTest {
    private val beginn = 1_790_000_000L

    @Test
    fun phasen_werden_je_art_summiert() {
        val a = listOf(
            NachtAbschnittEntity(beginn, 60, 120, 1),
            NachtAbschnittEntity(beginn, 120, 150, 2),
            NachtAbschnittEntity(beginn, 150, 155, 0),
            NachtAbschnittEntity(beginn, 155, 200, 3),
            NachtAbschnittEntity(beginn, 200, 260, 1),
        )
        assertEquals(listOf(5, 120, 30, 45, 0), Gesundheitsrechnung.phasenMinuten(a).toList())
    }

    @Test
    fun nacht_in_zeitpunkten_und_ohne_messung_null() {
        val e = NachtEntity(beginn, 7200, einschlafen = 60, aufwachen = 540, schlafMinuten = 460, ruhepuls = 0, hrv = 42)
        val n = Gesundheitsrechnung.nacht(e, listOf(NachtAbschnittEntity(beginn, 60, 540, 4)))
        assertEquals(Instant.ofEpochSecond(beginn + 3600), n.einschlafen)
        assertEquals(Instant.ofEpochSecond(beginn + 540 * 60), n.aufwachen)
        assertNull(n.ruhepuls)
        assertEquals(42, n.hrv)
        assertTrue(!n.mitPhasen)
    }

    @Test
    fun koffein_halbiert_sich_in_fuenf_stunden() {
        val t = Instant.ofEpochSecond(beginn)
        val dosen = listOf(t to 80.0, t.plusSeconds(5 * 3600) to 40.0, t.plusSeconds(20 * 3600) to 100.0)
        assertEquals(80.0, Gesundheitsrechnung.koffeinAktiv(dosen, t.plusSeconds(5 * 3600)), 0.01)
    }

    @Test
    fun die_nacht_gehoert_zum_tag_des_aufwachens() {
        // Fensterbeginn 20:00 UTC am 28.09., aufgewacht 07:00 -> Nacht zum 29.09.
        val b = LocalDate.of(2026, 9, 28).atTime(20, 0).toEpochSecond(ZoneOffset.UTC)
        assertEquals(LocalDate.of(2026, 9, 29), Gesundheitsrechnung.nachtTag(b, 11 * 60, ZoneOffset.UTC))
        // Ein Nickerchen, das noch vor Mitternacht endet, gehoert zum 28.09.
        assertEquals(LocalDate.of(2026, 9, 28), Gesundheitsrechnung.nachtTag(b, 3 * 60, ZoneOffset.UTC))
    }

    @Test
    fun je_tag_bleibt_die_laengste_nacht() {
        // Aeltere Firmware: zwei Naechte fuer denselben Morgen (22-01 und 01:40-06:30).
        val b = LocalDate.of(2026, 9, 28).atTime(20, 0).toEpochSecond(ZoneOffset.UTC)
        val erste = NachtEntity(b, 7200, 120, 300, 175, 52, 60)
        val zweite = NachtEntity(b + 300 * 60, 7200, 40, 330, 285, 50, 70)
        val andere = NachtEntity(b - 86400, 7200, 130, 660, 500, 51, 65)
        val je = Gesundheitsrechnung.jeTag(listOf(andere, erste, zweite), ZoneOffset.UTC)
        assertEquals(listOf(andere, zweite), je)
    }
}

class GesundheitsbildRechnungTest {
    private val t0 = 1_790_000_100L

    @Test
    fun fuenf_minuten_bins_mit_median_und_spanne() {
        val punkte = listOf(t0 to 60, t0 + 60 to 90, t0 + 120 to 62, t0 + 180 to 61, t0 + 900 to 70)
        val bins = Gesundheitsrechnung.fuenfMinuten(punkte)
        assertEquals(2, bins.size)
        assertEquals(Pulsbin(t0 / 300 * 300, 62, 60, 90), bins[0])
        assertEquals(Pulsbin((t0 + 900) / 300 * 300, 70, 70, 70), bins[1])
        assertTrue(Gesundheitsrechnung.fuenfMinuten(emptyList()).isEmpty())
    }

    @Test
    fun raster_in_runden_schritten() {
        assertEquals(listOf(60, 80, 100, 120, 140), Gesundheitsrechnung.raster(47, 147, 10))
        assertEquals(listOf(50, 60, 70, 80, 90), Gesundheitsrechnung.raster(50, 90, 10))
        assertEquals(listOf(90, 95, 100), Gesundheitsrechnung.raster(88, 100, 5))
        assertEquals(listOf(55), Gesundheitsrechnung.raster(55, 55))
        assertTrue(Gesundheitsrechnung.raster(5, 4).isEmpty())
    }

    @Test
    fun kurzformen() {
        assertEquals("95", Gesundheitsrechnung.schritteKurz(95, Locale.ROOT))
        assertEquals("4.9k", Gesundheitsrechnung.schritteKurz(4_912, Locale.ROOT))
        assertEquals("10k", Gesundheitsrechnung.schritteKurz(10_000, Locale.ROOT))
        assertEquals("10k", Gesundheitsrechnung.schritteKurz(10_040, Locale.ROOT))
        assertEquals("4,9k", Gesundheitsrechnung.schritteKurz(4_912, Locale.GERMAN))
        assertEquals("10k", Gesundheitsrechnung.schritteKurz(10_040, Locale.GERMAN))
        assertEquals("1.2", Gesundheitsrechnung.literKurz(1_240, Locale.ROOT))
        assertEquals("0.0", Gesundheitsrechnung.literKurz(0, Locale.ROOT))
    }

    @Test
    fun schnitt_nur_ueber_tage_mit_wert() {
        assertEquals(2000.0, Gesundheitsrechnung.schnittDerVortage(listOf(0.0, 1000.0, 3000.0), 2)!!, 0.001)
        assertNull(Gesundheitsrechnung.schnittDerVortage(listOf(0.0, 1000.0), 2))
        assertNull(Gesundheitsrechnung.schnittDerVortage(emptyList()))
    }

    @Test
    fun bettzeit_heute_oder_keine() {
        val zone = ZoneOffset.UTC
        val mittag = LocalDate.of(2026, 9, 30).atTime(12, 0).toInstant(zone)
        val eingeschlafen = LocalDate.of(2026, 9, 29).atTime(22, 54, 30).toInstant(zone)
        assertEquals(LocalDate.of(2026, 9, 30).atTime(22, 54).toInstant(zone), Gesundheitsrechnung.bettzeit(eingeschlafen, mittag, zone))
        assertEquals(LocalDate.of(2026, 9, 30).atTime(22, 0).toInstant(zone), Gesundheitsrechnung.bettzeit(null, mittag, zone))
        // Um 23 Uhr ist die Bettzeit von 22 Uhr vorbei - nichts mehr vorherzusagen.
        val spaet = LocalDate.of(2026, 9, 30).atTime(23, 0).toInstant(zone)
        assertNull(Gesundheitsrechnung.bettzeit(null, spaet, zone))
        // Am Tag der Zeitumstellung gilt die Ortszeit: 22:00 CET am 25.10. ist 21:00 UTC.
        val zuerich = ZoneId.of("Europe/Zurich")
        val umstellung = LocalDate.of(2026, 10, 25).atTime(12, 0).atZone(zuerich).toInstant()
        assertEquals(LocalDate.of(2026, 10, 25).atTime(21, 0).toInstant(zone), Gesundheitsrechnung.bettzeit(null, umstellung, zuerich))
    }

    @Test
    fun stuecke_brechen_an_luecken() {
        fun bin(s: Long) = Pulsbin(s, 60, 58, 62)
        assertTrue(Gesundheitsrechnung.stuecke(emptyList()).isEmpty())
        assertEquals(listOf(listOf(bin(0))), Gesundheitsrechnung.stuecke(listOf(bin(0))))
        // Genau 900 s Abstand haelt zusammen, 901 s trennt.
        assertEquals(listOf(listOf(bin(0), bin(900))), Gesundheitsrechnung.stuecke(listOf(bin(0), bin(900))))
        assertEquals(listOf(listOf(bin(0)), listOf(bin(901), bin(1200))), Gesundheitsrechnung.stuecke(listOf(bin(0), bin(901), bin(1200))))
    }

    @Test
    fun ruhepuls_ersetzt_nahe_rasterlinien() {
        assertEquals(listOf(70), Gesundheitsrechnung.rasterOhne(listOf(50, 60, 70), 55, 5.5f))
        assertEquals(listOf(50, 60, 70), Gesundheitsrechnung.rasterOhne(listOf(50, 60, 70), 55, 5f))
        assertEquals(listOf(50, 60), Gesundheitsrechnung.rasterOhne(listOf(50, 60), null, 20f))
        assertTrue(Gesundheitsrechnung.rasterOhne(emptyList(), 55, 5f).isEmpty())
    }
}
