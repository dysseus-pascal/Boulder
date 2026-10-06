package coredevices.pebble.kiesel.ansicht

import coredevices.pebble.R
import coredevices.pebble.kiesel.Bandurteil
import coredevices.pebble.kiesel.Gesundheitsrechnung
import coredevices.pebble.kiesel.Herzurteil
import coredevices.pebble.kiesel.Vergleich
import coredevices.pebble.kiesel.bandurteil
import coredevices.pebble.kiesel.ruhepulsUrteil
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * WELCHER TEXT ZU WELCHEM URTEIL: ein vertauschtes above/below oder eine
 * negative Zahl ("-4 below") fiele am Geraet erst auf, wenn jemand genau
 * hinsieht - die Urteile selbst hatten Tests, die Wahl des Textes nicht.
 * Die Zahl im Text ist immer ein Betrag.
 */
class HerztexteTest {

    @Test
    fun tagespuls_ueber_unter_gleich() {
        assertEquals(Satzteil(R.string.ks_d_tagespuls), tagespulsTeil(null))
        assertEquals(Satzteil(R.string.ks_d_tagespuls_hoch, 5), tagespulsTeil(5))
        // Tiefer mit Betrag: "Day 5 below usual", nicht "-5 below".
        assertEquals(Satzteil(R.string.ks_d_tagespuls_tief, 5), tagespulsTeil(-5))
        // Die Schwelle 3, knapp darunter und darauf.
        assertEquals(Satzteil(R.string.ks_d_tagespuls_gleich), tagespulsTeil(2))
        assertEquals(Satzteil(R.string.ks_d_tagespuls_gleich), tagespulsTeil(-2))
        assertEquals(Satzteil(R.string.ks_d_tagespuls_hoch, 3), tagespulsTeil(3))
        assertEquals(Satzteil(R.string.ks_d_tagespuls_tief, 3), tagespulsTeil(-3))
    }

    @Test
    fun ruhepuls_und_hrv_ueber_unter_gleich() {
        assertEquals(Satzteil(R.string.ks_h_ruhe_hoeher, 4), ruheTeil(Herzurteil(5, 55.0, Vergleich.HOEHER, 4)))
        assertEquals(Satzteil(R.string.ks_h_ruhe_tiefer, 4), ruheTeil(Herzurteil(5, 55.0, Vergleich.TIEFER, 4)))
        assertEquals(Satzteil(R.string.ks_h_ruhe_wie_immer), ruheTeil(Herzurteil(5, 55.0, Vergleich.WIE_IMMER, 1)))
        assertNull(ruheTeil(Herzurteil(1, null, null, 0)))
        assertEquals(Satzteil(R.string.ks_h_hrv_hoeher, 6), hrvTeil(Bandurteil(Vergleich.HOEHER, 6)))
        assertEquals(Satzteil(R.string.ks_h_hrv_tiefer, 7), hrvTeil(Bandurteil(Vergleich.TIEFER, 7)))
        assertEquals(Satzteil(R.string.ks_h_hrv_wie_immer), hrvTeil(Bandurteil(Vergleich.WIE_IMMER, 0)))
    }

    @Test
    fun beide_wie_immer_ist_ein_satz() {
        val ruhe = Herzurteil(5, 55.0, Vergleich.WIE_IMMER, 0)
        assertEquals(listOf(Satzteil(R.string.ks_h_beide_wie_immer)), urteilTeile(ruhe, Bandurteil(Vergleich.WIE_IMMER, 0)))
        assertEquals(
            listOf(Satzteil(R.string.ks_h_ruhe_wie_immer), Satzteil(R.string.ks_h_hrv_tiefer, 3)),
            urteilTeile(ruhe, Bandurteil(Vergleich.TIEFER, 3)),
        )
        assertEquals(listOf(Satzteil(R.string.ks_h_ruhe_wie_immer)), urteilTeile(ruhe, null))
        assertEquals(emptyList(), urteilTeile(Herzurteil(0, null, null, 0), null))
    }

    @Test
    fun aus_den_echten_urteilen_bis_zum_text() {
        // Ruhepuls 50 nach Naechten um 55: tiefer, 5 bpm.
        val ruhe = Gesundheitsrechnung.ruhepulsUrteil(50, listOf(55, 55, 55, 55))
        assertEquals(Satzteil(R.string.ks_h_ruhe_tiefer, 5), ruheTeil(ruhe))
        // Und 61: hoeher, 6 bpm.
        assertEquals(Satzteil(R.string.ks_h_ruhe_hoeher, 6), ruheTeil(Gesundheitsrechnung.ruhepulsUrteil(61, listOf(55, 55, 55, 55))))
        // HRV 40 unter dem Band 45..60: 5 ms darunter.
        val tag = LocalDate.of(2026, 10, 3)
        val hrv = Gesundheitsrechnung.bandurteil(Gesundheitsrechnung.Bandtag(tag, 40, 45 to 60, frueher = 30))
        assertEquals(Satzteil(R.string.ks_h_hrv_tiefer, 5), hrvTeil(hrv!!))
        // Der Tagespuls: heute 64 nach fuenf Tagen um 70 - sechs darunter, mit Betrag.
        val tage = (1..5).map { Gesundheitsrechnung.Tagesherz(tag.minusDays(it.toLong()), 70, 0, 100) } + Gesundheitsrechnung.Tagesherz(tag, 64, 0, 100)
        val urteil = Gesundheitsrechnung.tagesurteil(tage, tag)!!
        assertEquals(Satzteil(R.string.ks_d_tagespuls_tief, 6), tagespulsTeil(urteil.abstand))
    }
}
