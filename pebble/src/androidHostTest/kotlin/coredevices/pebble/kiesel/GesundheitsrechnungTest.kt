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
        // Nach Mitternacht eingeschlafen: die Bettzeit liegt in der kommenden Nacht, nicht heute frueh.
        val spaetEin = LocalDate.of(2026, 9, 30).atTime(0, 30).toInstant(zone)
        assertEquals(LocalDate.of(2026, 10, 1).atTime(0, 30).toInstant(zone), Gesundheitsrechnung.bettzeit(spaetEin, mittag, zone))
        // Um 01:00 ist 00:30 dieser Nacht vorbei.
        assertNull(Gesundheitsrechnung.bettzeit(spaetEin, LocalDate.of(2026, 10, 1).atTime(1, 0).toInstant(zone), zone))
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
    fun pulsspanne_aus_den_medianen_der_linie() {
        // Ein einzelner Ausreisser von 150 bpm zwischen Werten um 80 setzt weder Spitze noch Zone:
        // die Linie zeigt den Median 82, und der hoechste Wert im Satz ist derselbe.
        val punkte = listOf(t0 to 80, t0 + 60 to 150, t0 + 120 to 82, t0 + 600 to 60, t0 + 660 to 62, t0 + 720 to 61)
        assertEquals(61 to 82, Gesundheitsrechnung.pulsSpanne(punkte))
        assertNull(Gesundheitsrechnung.pulsSpanne(emptyList()))
    }

    private val zoneUtc = ZoneOffset.UTC
    private fun sek(tag: LocalDate, stunde: Int, minute: Int) = tag.atTime(stunde, minute).toEpochSecond(ZoneOffset.UTC)

    @Test
    fun tagesherz_laesst_schlaf_und_spaeteres_weg() {
        val tag = LocalDate.of(2026, 9, 30)
        // Jede fuenfte Minute von 00:00 bis 23:55: die Nacht bis 06:00 schlaeft
        val puls = (0 until 288).map { sek(tag, 0, 0) + it * 300L to (if (it < 72) 50 else 70) }
        val schlaf = listOf(sek(tag, 0, 0) to sek(tag, 6, 0))
        val r = Gesundheitsrechnung.tagesherz(puls, schlaf, Gesundheitsrechnung.pulsZonen(55, 185), java.time.LocalTime.of(12, 0), zoneUtc).single()
        assertEquals(70, r.wachpuls)
        assertEquals(73, r.messungen)   // 06:00 bis 12:00
        assertEquals(0, r.belastung)
    }

    @Test
    fun belastung_zaehlt_doppelt_ab_sechzig_prozent() {
        val tag = LocalDate.of(2026, 9, 30)
        // Reserve 130: ab 107 bpm einfach, ab 133 doppelt
        val ruhig = (0 until 30).map { sek(tag, 8, it) to 70 }
        val mittel = (0 until 20).map { sek(tag, 9, it) to 115 }
        val hart = (0 until 10).map { sek(tag, 10, it) to 150 }
        val grenzen = Gesundheitsrechnung.pulsZonen(55, 185)
        assertEquals(listOf(107, 133, 159), grenzen)
        val r = Gesundheitsrechnung.tagesherz(ruhig + mittel + hart, emptyList(), grenzen, java.time.LocalTime.of(23, 0), zoneUtc).single()
        // Die letzte Minute vor einer Luecke zaehlt hoechstens zehn
        assertEquals(10 + 19 + 2 * 10, r.belastung)
    }

    @Test
    fun tagesherz_braucht_genug_messungen() {
        val tag = LocalDate.of(2026, 9, 30)
        val puls = (0 until 10).map { sek(tag, 9, it) to 80 }
        assertNull(Gesundheitsrechnung.tagesherz(puls, emptyList(), Gesundheitsrechnung.pulsZonen(55, 185), java.time.LocalTime.MAX, zoneUtc).single().wachpuls)
    }

    @Test
    fun belastung_folgt_den_gerundeten_zonengrenzen() {
        val tag = LocalDate.of(2026, 9, 30)
        val grenzen = Gesundheitsrechnung.pulsZonen(53, 187)
        fun belastung(bpm: Int) = Gesundheitsrechnung.tagesherz(
            (0 until 5).map { sek(tag, 10, it) to bpm }, emptyList(), grenzen, java.time.LocalTime.MAX, zoneUtc,
        ).single().belastung
        // 133 bpm sind 59.7 % der Reserve, aber die Kurve faerbt sie schon kraeftig - also doppelt.
        assertEquals(2 * 5, belastung(133))
        assertEquals(5, belastung(132))
        // Spitze zaehlt wie kraeftig, unter der ersten Grenze zaehlt nichts.
        assertEquals(2 * 5, belastung(170))
        assertEquals(0, belastung(106))
    }

    @Test
    fun tagesurteil_nach_belastung() {
        val heute = LocalDate.of(2026, 9, 30)
        val frueher = (1..7).map { Gesundheitsrechnung.Tagesherz(heute.minusDays(it.toLong()), 72, 30, 100) }
        fun urteil(puls: Int, last: Int) = Gesundheitsrechnung.tagesurteil(frueher + Gesundheitsrechnung.Tagesherz(heute, puls, last, 100), heute)!!
        assertEquals(Vergleich.HOEHER, urteil(77, 50).haerte)
        assertEquals(5, urteil(77, 50).abstand)
        assertEquals(Vergleich.WIE_IMMER, urteil(80, 35).haerte)
        assertEquals(Vergleich.TIEFER, urteil(70, 10).haerte)
    }

    @Test
    fun tagesurteil_ohne_belastung_nach_puls_und_erst_nach_lerntagen() {
        val heute = LocalDate.of(2026, 9, 30)
        val ruhig = (1..5).map { Gesundheitsrechnung.Tagesherz(heute.minusDays(it.toLong()), 70, 0, 100) }
        assertEquals(Vergleich.HOEHER, Gesundheitsrechnung.tagesurteil(ruhig + Gesundheitsrechnung.Tagesherz(heute, 76, 0, 100), heute)!!.haerte)
        val wenig = ruhig.take(4) + Gesundheitsrechnung.Tagesherz(heute, 76, 0, 100)
        assertNull(Gesundheitsrechnung.tagesurteil(wenig, heute)!!.haerte)
    }

    // Ein beliebiger fester Zeitpunkt - es zaehlen nur die Abstaende der Messungen.
    private val bdBeginn = 1_790_000_000L
    private fun messung(sekunde: Long, oben: Int, unten: Int) = Blutdruck(Instant.ofEpochSecond(bdBeginn + sekunde), oben, unten)

    @Test
    fun blutdruck_zeigt_die_letzte_messung_und_ab_drei_den_schnitt() {
        assertNull(Gesundheitsrechnung.blutdruckstand(emptyList()))
        val zwei = listOf(messung(86_400, 130, 85), messung(0, 120, 80))
        val z = Gesundheitsrechnung.blutdruckstand(zwei)!!
        assertEquals(130, z.letzte.oben)
        assertNull(z.schnitt)
        val drei = zwei + messung(43_200, 125, 81)
        assertEquals(125 to 82, Gesundheitsrechnung.blutdruckstand(drei)!!.schnitt)
    }

    @Test
    fun blutdruck_mittelt_eine_sitzung() {
        // Drei Messungen hintereinander an der Manschette: eine Sitzung, noch kein Schnitt.
        val sitzung = listOf(messung(120, 128, 80), messung(0, 130, 84), messung(60, 126, 82))
        val z = Gesundheitsrechnung.blutdruckstand(sitzung)!!
        assertEquals(messung(0, 128, 82), z.letzte)
        assertNull(z.schnitt)
        // Zwei Sitzungen mehr: der Schnitt ueber die Sitzungen 128/82, 120/80 und 112/72.
        val mehr = sitzung + messung(86_400, 120, 80) + messung(172_800, 110, 70) + messung(173_100, 114, 74)
        val m = Gesundheitsrechnung.blutdruckstand(mehr)!!
        assertEquals(120 to 78, m.schnitt)
        assertEquals(messung(172_800, 112, 72), m.letzte)
        // Fuer das Bild jede Sitzung als ein Strich, aelteste zuerst.
        assertEquals(listOf(messung(0, 128, 82), messung(86_400, 120, 80), messung(172_800, 112, 72)), m.sitzungen)
    }

    @Test
    fun herzzeile_nennt_den_blutdruck_nur_aus_den_letzten_sieben_tagen() {
        val zone = ZoneOffset.UTC
        val jetzt = LocalDate.of(2026, 10, 2).atTime(9, 0).toInstant(zone)
        fun am(tag: LocalDate, stunde: Int) = Blutdruck(tag.atTime(stunde, 0).toInstant(zone), 128, 82)
        assertNull(Gesundheitsrechnung.blutdruckFuerHerzzeile(emptyList(), jetzt, zone))
        // Kalendertage, nicht 7 x 24 Stunden: der 26. Sept. zaehlt noch, der 25. auch am Abend nicht mehr, obwohl keine 7 x 24 Stunden vergangen sind.
        assertEquals(am(LocalDate.of(2026, 9, 26), 7), Gesundheitsrechnung.blutdruckFuerHerzzeile(listOf(am(LocalDate.of(2026, 9, 26), 7)), jetzt, zone))
        assertNull(Gesundheitsrechnung.blutdruckFuerHerzzeile(listOf(am(LocalDate.of(2026, 9, 25), 22)), jetzt, zone))
        // Es zaehlt die letzte Sitzung - eine aeltere davor aendert nichts.
        val beide = listOf(am(LocalDate.of(2026, 9, 10), 8), am(LocalDate.of(2026, 10, 1), 20))
        assertEquals(am(LocalDate.of(2026, 10, 1), 20), Gesundheitsrechnung.blutdruckFuerHerzzeile(beide, jetzt, zone))
    }

    @Test
    fun blutdruckachse_mit_luft_auf_zehner_gerundet() {
        assertNull(Gesundheitsrechnung.blutdruckachse(emptyList()))
        assertEquals(70 to 130, Gesundheitsrechnung.blutdruckachse(listOf(messung(0, 120, 80))))
        // 76 - 5 = 71 nach unten auf 70, 135 + 5 = 140 bleibt, 136 + 5 = 141 geht auf 150.
        assertEquals(70 to 140, Gesundheitsrechnung.blutdruckachse(listOf(messung(0, 118, 76), messung(86_400, 135, 88))))
        assertEquals(70 to 150, Gesundheitsrechnung.blutdruckachse(listOf(messung(0, 118, 76), messung(86_400, 136, 88))))
        // Mit Zehnerschritten waeren das acht Linien; das Raster nimmt Zwanziger.
        assertEquals(listOf(80, 100, 120, 140), Gesundheitsrechnung.raster(70, 140, 10))
    }

    @Test
    fun blutdrucksitzung_endet_ab_einer_viertelstunde() {
        // Gezaehlt wird der Abstand zur vorigen Messung: 14:59 haelt zusammen, 15:00 trennt.
        fun groessen(vararg sekunden: Long) = Gesundheitsrechnung.blutdrucksitzungen(sekunden.map { messung(it, 120, 80) }).map { it.size }
        assertEquals(listOf(2), groessen(0, 899))
        assertEquals(listOf(1, 1), groessen(0, 900))
        assertEquals(listOf(3), groessen(0, 600, 1200))
        assertTrue(Gesundheitsrechnung.blutdrucksitzungen(emptyList()).isEmpty())
    }

    @Test
    fun pulszonenAusDerReserve() {
        // Ruhepuls 53, Maximalpuls 187: 40/60/80 % der Reserve von 134.
        val z = Gesundheitsrechnung.pulsZonen(53, 187)
        assertEquals(listOf(107, 133, 160), z)
        assertEquals(0, Gesundheitsrechnung.pulsZone(80, z))
        assertEquals(1, Gesundheitsrechnung.pulsZone(107, z))
        assertEquals(2, Gesundheitsrechnung.pulsZone(150, z))
        assertEquals(3, Gesundheitsrechnung.pulsZone(170, z))
    }

    @Test
    fun hrvBandAusDenVortagen() {
        val heute = LocalDate.of(2026, 10, 2)
        // 30 Naechte vor der Woche abwechselnd 40 und 50 ms: Schnitt 45, Abweichung gut 5.
        val werte = (1..40).associate { heute.minusDays(it.toLong()) to if (it % 2 == 0) 40 else 50 } + (heute to 70)
        val woche = Gesundheitsrechnung.hrvWoche(werte, heute)
        assertEquals(7, woche.size)
        assertEquals(heute, woche.last().tag)
        assertEquals(40 to 50, woche.last().band)
        assertEquals(false, woche.last().imBand)
        assertEquals(true, woche.first().imBand)
    }

    @Test
    fun hrvOhneBandBeiZuWenigNaechten() {
        val heute = LocalDate.of(2026, 10, 2)
        val werte = (0..5).associate { heute.minusDays(it.toLong()) to 45 }
        val woche = Gesundheitsrechnung.hrvWoche(werte, heute)
        assertNull(woche.last().band)
        assertNull(woche.last().imBand)
        // Ein ganz gleichmaessiges Band ist mindestens 3 ms breit nach jeder Seite.
        val gleich = (1..10).associate { heute.minusDays(it.toLong()) to 45 }
        assertEquals(42 to 48, Gesundheitsrechnung.hrvWoche(gleich, heute).last().band)
    }

    @Test
    fun trendlinieMedianUndLuecken() {
        // Ein Ausreisser zieht den Median nicht; zwei Stunden ohne Messung brechen die Linie.
        val punkte = listOf(0L to 50, 300L to 52, 600L to 90, 900L to 51, 1200L to 50, 9000L to 60, 9300L to 62)
        val trend = Gesundheitsrechnung.trend(punkte, 900)
        assertEquals(2, trend.size)
        assertEquals(51.0, trend[0].first { it.first == 600L }.second)
        assertEquals(9000L, trend[1].first().first)
    }
}
