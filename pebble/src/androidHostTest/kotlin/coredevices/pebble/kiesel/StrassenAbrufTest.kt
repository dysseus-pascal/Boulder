package coredevices.pebble.kiesel

import coredevices.pebble.kiesel.StrassenAbruf.Companion.ABFRAGE_MS
import coredevices.pebble.kiesel.StrassenAbruf.Companion.LAUERN_MS
import coredevices.pebble.kiesel.StrassenAbruf.Soll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StrassenAbrufTest {
    private fun abruf() = StrassenAbruf(karenzMs = 30_000, pruefDauerMs = 15_000)

    @Test
    fun ohne_offene_uhr_app_wird_nicht_gebunden() {
        val a = abruf()
        assertEquals(Soll(binden = false, taktMs = null), a.soll(0))
        assertNull(a.naechsteWende(0))
    }

    @Test
    fun offene_uhr_app_bindet_und_lauert_ohne_navigation() {
        val a = abruf()
        assertTrue(a.uhrApp(true, 0))
        assertEquals(Soll(binden = true, taktMs = LAUERN_MS), a.soll(0))
    }

    @Test
    fun zweimal_offen_ist_kein_neues_oeffnen() {
        val a = abruf()
        assertTrue(a.uhrApp(true, 0))
        assertFalse(a.uhrApp(true, 10))
    }

    @Test
    fun zwei_sekunden_takt_nur_waehrend_navigation() {
        val a = abruf()
        a.uhrApp(true, 0)
        assertTrue(a.navigation(true))
        assertFalse(a.navigation(true))
        assertEquals(ABFRAGE_MS, a.soll(0).taktMs)
        assertTrue(a.navigation(false))
        assertEquals(LAUERN_MS, a.soll(0).taktMs)
    }

    @Test
    fun nach_dem_schliessen_bleibt_die_bindung_bis_zum_ende_der_karenz() {
        val a = abruf()
        a.uhrApp(true, 0)
        a.navigation(true)
        assertFalse(a.uhrApp(false, 1_000))
        // Geschlossen wird nicht mehr gefragt, auch mitten in einer Navigation.
        assertEquals(Soll(binden = true, taktMs = null), a.soll(1_000))
        assertEquals(31_000, a.naechsteWende(1_000))
        assertEquals(Soll(binden = true, taktMs = null), a.soll(30_999))
        assertEquals(Soll(binden = false, taktMs = null), a.soll(31_000))
        assertNull(a.naechsteWende(31_000))
    }

    @Test
    fun wiederoeffnen_in_der_karenz_haelt_die_bindung() {
        val a = abruf()
        a.uhrApp(true, 0)
        a.uhrApp(false, 1_000)
        assertTrue(a.uhrApp(true, 20_000))
        assertTrue(a.soll(60_000).binden)
        assertNull(a.naechsteWende(60_000))
    }

    @Test
    fun pruefung_bindet_kurz_und_loest_nach_dem_ergebnis() {
        val a = abruf()
        a.pruefe(0)
        assertEquals(Soll(binden = true, taktMs = null), a.soll(0))
        assertEquals(15_000, a.naechsteWende(0))
        a.geprueft()
        assertFalse(a.soll(1).binden)
    }

    @Test
    fun pruefung_ohne_antwort_gibt_nach_der_frist_auf() {
        val a = abruf()
        a.pruefe(0)
        assertTrue(a.soll(14_999).binden)
        assertFalse(a.soll(15_000).binden)
    }

    @Test
    fun pruefung_loest_nicht_waehrend_die_uhr_app_offen_ist() {
        val a = abruf()
        a.uhrApp(true, 0)
        a.pruefe(0)
        a.geprueft()
        assertTrue(a.soll(1).binden)
    }

    @Test
    fun ankommender_rueckruf_ersetzt_das_lauern() {
        val a = abruf()
        a.uhrApp(true, 0)
        assertTrue(a.rueckruf())
        assertFalse(a.rueckruf())
        assertEquals(2, a.anzahlRueckrufe)
        assertNull(a.soll(0).taktMs)
        // Waehrend der Navigation fragt Boulder trotzdem selbst im Takt.
        a.navigation(true)
        assertEquals(ABFRAGE_MS, a.soll(0).taktMs)
    }

    @Test
    fun loesen_vergisst_navigation_und_rueckrufe() {
        val a = abruf()
        a.uhrApp(true, 0)
        a.rueckruf()
        a.navigation(true)
        a.geloest()
        assertEquals(0, a.anzahlRueckrufe)
        assertEquals(LAUERN_MS, a.soll(0).taktMs)
        assertTrue(a.rueckruf())
    }

    @Test
    fun die_naechste_wende_ist_die_fruehere_frist() {
        val a = abruf()
        a.uhrApp(true, 0)
        a.uhrApp(false, 0)
        a.pruefe(10_000)
        assertEquals(25_000, a.naechsteWende(10_000))
        assertEquals(30_000, a.naechsteWende(25_000))
    }

    @Test
    fun einzelne_leere_antwort_beendet_die_navigation_nicht() {
        val a = abruf()
        a.uhrApp(true, 0)
        a.navigation(true)
        assertFalse(a.leereAntwort())
        assertFalse(a.leereAntwort())
        assertEquals(ABFRAGE_MS, a.soll(0).taktMs)
        // Eine Abzweigung dazwischen faengt die Zaehlung von vorn an.
        a.navigation(true)
        assertFalse(a.leereAntwort())
        assertFalse(a.leereAntwort())
        assertTrue(a.leereAntwort())
        assertEquals(LAUERN_MS, a.soll(0).taktMs)
    }

    @Test
    fun leere_antworten_ohne_navigation_melden_kein_ende() {
        val a = abruf()
        a.uhrApp(true, 0)
        repeat(5) { assertFalse(a.leereAntwort()) }
    }
}
