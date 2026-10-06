package coredevices.pebble.kiesel

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GesundheitsrechnungHerzTest {
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
    fun tagesurteil_ohne_belastung_allein_nach_belastung_und_erst_nach_lerntagen() {
        val heute = LocalDate.of(2026, 9, 30)
        val ruhig = (1..5).map { Gesundheitsrechnung.Tagesherz(heute.minusDays(it.toLong()), 70, 0, 100) }
        // Der Satz sagt "more load than usual": ein hoeherer Wachpuls ohne Belastung ist das nicht.
        val pulsHoeher = Gesundheitsrechnung.tagesurteil(ruhig + Gesundheitsrechnung.Tagesherz(heute, 76, 0, 100), heute)!!
        assertEquals(Vergleich.WIE_IMMER, pulsHoeher.haerte)
        assertEquals(6, pulsHoeher.abstand)
        assertEquals(Vergleich.HOEHER, Gesundheitsrechnung.tagesurteil(ruhig + Gesundheitsrechnung.Tagesherz(heute, 70, 20, 100), heute)!!.haerte)
        val wenig = ruhig.take(4) + Gesundheitsrechnung.Tagesherz(heute, 76, 0, 100)
        assertNull(Gesundheitsrechnung.tagesurteil(wenig, heute)!!.haerte)
    }

    @Test
    fun ohne_nacht_zaehlt_vor_sechs_nicht_als_wach() {
        val tag = LocalDate.of(2026, 9, 30)
        val zonen = Gesundheitsrechnung.pulsZonen(55, 185)
        // Alle fuenf Minuten von 00:00 bis 12:00, bis sechs Schlafpuls, ohne Nacht auf dem Telefon.
        val puls = (0 until 144).map { sek(tag, 0, 0) + it * 300L to (if (it < 72) 50 else 72) }
        fun wach(bis: java.time.LocalTime, heute: LocalDate?, schlaf: List<Pair<Long, Long>> = emptyList()) =
            Gesundheitsrechnung.tagesherz(puls, schlaf, zonen, bis, zoneUtc, heute).single().wachpuls
        // Ein frueherer Tag behaelt seinen Schnitt ab sechs - frueher fiel er ganz weg.
        assertEquals(72, wach(java.time.LocalTime.of(10, 0), tag.plusDays(1)))
        // Heute am Morgen kann die Nacht noch kommen: kein Urteil.
        assertNull(wach(java.time.LocalTime.of(10, 0), tag))
        // Ab Mittag hat die Uhr jede Nacht abgeschlossen.
        assertEquals(72, wach(java.time.LocalTime.of(12, 0), tag))
        // Ist die Nacht da, zaehlt der Tag wie immer.
        assertEquals(72, wach(java.time.LocalTime.of(10, 0), tag, listOf(sek(tag, 0, 0) to sek(tag, 6, 0))))
        // Uhr nachts abgelegt, erst ab sieben am Arm: nichts zu verwechseln, auch heute morgen.
        val abSieben = puls.filter { it.first >= sek(tag, 7, 0) }
        assertEquals(72, Gesundheitsrechnung.tagesherz(abSieben, emptyList(), zonen, java.time.LocalTime.of(10, 0), zoneUtc, tag).single().wachpuls)
    }

    @Test
    fun ohne_nacht_zaehlt_ein_lauf_vor_sechs_in_die_belastung() {
        val tag = LocalDate.of(2026, 9, 30)
        val grenzen = Gesundheitsrechnung.pulsZonen(55, 185)
        // 05:30 bis 05:49 kraeftig (zaehlt doppelt), ab 05:50 ruhig: nur der Wachpuls laesst die Zeit vor sechs weg.
        val lauf = (30 until 50).map { sek(tag, 5, it) to 150 }
        val ruhig = (0 until 40).map { sek(tag, 5, 50) + it * 60L to 70 }
        val r = Gesundheitsrechnung.tagesherz(lauf + ruhig, emptyList(), grenzen, java.time.LocalTime.of(12, 0), zoneUtc, tag.plusDays(1)).single()
        assertEquals(2 * 20, r.belastung)
        assertEquals(70, r.wachpuls)
    }

    @Test
    fun pulsspitze_mit_zeit_der_juengsten() {
        // Zweimal 120 als Median: die Zeit der juengsten, damit "yesterday" nur steht, wenn es keine heutige gibt.
        val punkte = listOf(t0 to 120, t0 + 600 to 80, t0 + 1200 to 120, t0 + 1800 to 90)
        assertEquals((t0 + 1200) / 300 * 300 to 120, Gesundheitsrechnung.pulsSpitze(punkte))
        assertNull(Gesundheitsrechnung.pulsSpitze(emptyList()))
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

    // --- Die Blutdruckwoche der Herzseite (0.51) ---

    private val bdZone = ZoneOffset.UTC
    private val bdHeute = LocalDate.of(2026, 10, 2)
    private fun am(tag: LocalDate, h: Int, m: Int, oben: Int, unten: Int) = Blutdruck(tag.atTime(h, m).toInstant(bdZone), oben, unten)

    /**
     * EIN WERT JE TAG, AUS SEINEN SITZUNGEN: drei Messungen am Morgen sind
     * eine Sitzung (122/82), dazu eine am Abend (130/90) - der Tag ist
     * 126/86, der Schnitt der beiden Sitzungen. Ueber die vier Messungen
     * gemittelt waere er 124/84, der Morgen zaehlte dreifach.
     */
    @Test
    fun blutdruckwoche_ein_wert_je_tag_aus_seinen_sitzungen() {
        val messungen = listOf(
            am(bdHeute, 7, 0, 120, 80), am(bdHeute, 7, 2, 124, 82), am(bdHeute, 7, 4, 122, 84),
            am(bdHeute, 20, 0, 130, 90),
            am(bdHeute.minusDays(2), 8, 0, 118, 76),
        )
        val w = Gesundheitsrechnung.blutdruckwoche(messungen, bdHeute, bdZone)
        assertEquals((6 downTo 0).map { bdHeute.minusDays(it.toLong()) }, w.oben.map { it.tag })
        assertEquals(w.oben.map { it.tag }, w.unten.map { it.tag })
        assertEquals(126, w.oben.last().wert)
        assertEquals(86, w.unten.last().wert)
        assertEquals(118 to 76, w.oben[4].wert to w.unten[4].wert)
        // Ein Tag ohne Messung hat keinen Wert, und ohne fruehere Tage gibt es kein Band (neutral).
        assertNull(w.oben[5].wert)
        assertTrue(w.oben.all { it.band == null } && w.unten.all { it.band == null })
        assertTrue(w.oben.all { it.imBand == null })
    }

    /**
     * DIE BAENDER GETRENNT FUER OBEN UND UNTEN, nach der Regel des HRV-Bands:
     * 30 Tage vor dem ersten Tag der Woche oben abwechselnd 116 und 129
     * (Schnitt 122,5, Streuung 6,6), unten 74 und 83 (78,5, Streuung 4,6) -
     * das gibt 116-129 und 74-83. Ein gemeinsames Band aus beiden Reihen
     * laege irgendwo dazwischen und passte zu keiner.
     */
    @Test
    fun blutdruckbaender_getrennt_fuer_oben_und_unten_wie_beim_hrv() {
        val vorher = (7..46).map { k -> bdHeute.minusDays(k.toLong()).let { if (k % 2 == 0) am(it, 8, 0, 116, 74) else am(it, 8, 0, 129, 83) } }
        // Die Woche: Mittwoch bis Montag im Band, heute oben drin und unten darueber.
        val woche = (6 downTo 1).map { am(bdHeute.minusDays(it.toLong()), 8, 0, 122, 78) } + am(bdHeute, 8, 0, 124, 86)
        val w = Gesundheitsrechnung.blutdruckwoche(vorher + woche, bdHeute, bdZone)
        assertEquals(116 to 129, w.oben.first().band)
        assertEquals(74 to 83, w.unten.first().band)
        assertEquals(true, w.oben.last().imBand)
        assertEquals(false, w.unten.last().imBand)
        assertTrue(w.oben.dropLast(1).all { it.imBand == true } && w.unten.dropLast(1).all { it.imBand == true })
    }

    /** Unter sieben frueheren Tagen mit Messung kein Band - wie bei der HRV; ab sieben eines. */
    @Test
    fun blutdruckband_erst_ab_sieben_frueheren_tagen() {
        fun mit(tage: Int) = Gesundheitsrechnung.blutdruckwoche(
            (1..tage).map { am(bdHeute.minusDays(6L + it), 8, 0, 120, 80) } + am(bdHeute.minusDays(6), 8, 0, 121, 81), bdHeute, bdZone,
        )
        assertNull(mit(6).oben.first().band)
        assertNull(mit(6).unten.first().band)
        // Sieben gleiche Tage: das Band ist mindestens 3 breit nach jeder Seite.
        assertEquals(117 to 123, mit(7).oben.first().band)
        assertEquals(77 to 83, mit(7).unten.first().band)
    }

    /**
     * DER SCHNITT IM KOPF IST DER DER WOCHE, die die Karte zeigt: drei
     * Sitzungen der letzten sieben Tage (120/80, 124/82, 128/84) geben
     * 124/82; eine hohe Sitzung vor zehn Tagen zaehlt nicht mit. Unter drei
     * Sitzungen kein Schnitt.
     */
    @Test
    fun blutdruckwoche_schnitt_aus_den_sitzungen_der_woche() {
        val alt = am(bdHeute.minusDays(10), 8, 0, 160, 100)
        val drei = listOf(am(bdHeute.minusDays(6), 8, 0, 120, 80), am(bdHeute.minusDays(3), 8, 0, 124, 82), am(bdHeute, 8, 0, 128, 84))
        assertEquals(124 to 82, Gesundheitsrechnung.blutdruckwoche(drei + alt, bdHeute, bdZone).schnitt)
        assertNull(Gesundheitsrechnung.blutdruckwoche(drei.drop(1) + alt, bdHeute, bdZone).schnitt)
    }

    /** Der Schnitt der HRV-Woche ueber die Naechte mit Wert, ab drei: 41..55 wie im Entwurf geben 49. */
    @Test
    fun wochenschnitt_ueber_die_naechte_mit_wert_ab_drei() {
        fun woche(vararg w: Int?) = w.mapIndexed { i, x -> Gesundheitsrechnung.Bandtag(bdHeute.minusDays(6L - i), x, null) }
        assertEquals(49, Gesundheitsrechnung.wochenschnitt(woche(48, 53, 46, 55, 52, 41, 50)))
        // Fehlende Naechte zaehlen nicht als 0: (40 + 50 + 61) / 3 = 50,3.
        assertEquals(50, Gesundheitsrechnung.wochenschnitt(woche(null, null, null, null, 40, 50, 61)))
        assertNull(Gesundheitsrechnung.wochenschnitt(woche(null, null, null, null, null, 50, 52)))
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
        val woche = Gesundheitsrechnung.bandwoche(werte, heute)
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
        val woche = Gesundheitsrechnung.bandwoche(werte, heute)
        assertNull(woche.last().band)
        assertNull(woche.last().imBand)
        // Ein ganz gleichmaessiges Band ist mindestens 3 ms breit nach jeder Seite.
        val gleich = (1..10).associate { heute.minusDays(it.toLong()) to 45 }
        assertEquals(42 to 48, Gesundheitsrechnung.bandwoche(gleich, heute).last().band)
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
