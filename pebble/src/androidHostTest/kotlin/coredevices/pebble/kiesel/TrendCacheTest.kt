package coredevices.pebble.kiesel

import kotlinx.coroutines.test.runTest
import java.time.LocalDate
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Boulder: die Zwischenstaende der Trends - sie halten nur bei sichtbarer Seite, [Tagesspeicher.ergaenze]
 * und [Tagesspeicher.halte] halten sie richtig, eine gescheiterte Akte uebernimmt kein Schub, und erst
 * ein fertiges volles Laden gibt die Schuebe frei.
 */
class TrendCacheTest {
    private val t1 = LocalDate.of(2026, 9, 1)
    private val t2 = LocalDate.of(2026, 9, 2)
    private val t3 = LocalDate.of(2026, 9, 3)

    /** Ein Fach im Speicher, das zaehlt, wie oft es ganz geparst und tageweise gelesen wird. */
    private class Speicherfach(override val name: String) : Fach {
        val tage = mutableMapOf<LocalDate, Map<String, Double>>()
        var geparst = 0
        var einzeln = 0
        override fun alle(): Map<LocalDate, Map<String, Double>> = tage.toMap().also { geparst++ }
        override fun tag(tag: LocalDate): Map<String, Double>? = tage[tag].also { einzeln++ }
        override fun schreibe(tage: Map<LocalDate, Map<String, Double>>) = this.tage.putAll(tage)
        override fun ergaenze(tag: LocalDate, werte: Map<String, Double>) {
            val alt = tage[tag].orEmpty()
            tage[tag] = alt + werte.filterKeys { it !in alt }
        }
    }

    private fun fach(name: String) = Speicherfach("test-$name").apply {
        tage[t1] = mapOf("schritte" to 8000.0, "schlaf" to 420.0)
        tage[t2] = mapOf("schritte" to 5000.0)
    }

    private inline fun sichtbar(block: () -> Unit) {
        Tagesspeicher.gelesen.oeffne()
        try {
            block()
        } finally {
            Tagesspeicher.gelesen.schliesse()
        }
    }

    @Test
    fun ohne_sichtbare_seite_merkt_sich_der_tagesspeicher_nichts() {
        val f = fach("ohne")
        Tagesspeicher.tage(f, merken = true)
        Tagesspeicher.tage(f, merken = true)
        assertEquals(2, f.geparst)
    }

    @Test
    fun sichtbar_wird_ein_fach_einmal_geparst_und_danach_vergessen() {
        val f = fach("sichtbar")
        sichtbar {
            Tagesspeicher.tage(f, merken = true)
            assertEquals(f.tage, Tagesspeicher.tage(f, merken = true))
            assertEquals(1, f.geparst)
        }
        Tagesspeicher.tage(f, merken = true)
        assertEquals(2, f.geparst)
    }

    @Test
    fun ergaenze_macht_das_fach_ungueltig() {
        val f = fach("ergaenze")
        sichtbar {
            Tagesspeicher.tage(f, merken = true)
            // Vorhandenes bleibt (schritte), Fehlendes kommt dazu (hrv) - und das naechste Lesen sieht es.
            Tagesspeicher.ergaenze(f, t2, mapOf("schritte" to 1.0, "hrv" to 40.0))
            val danach = Tagesspeicher.tage(f, merken = true)
            assertEquals(mapOf("schritte" to 5000.0, "hrv" to 40.0), danach[t2])
            assertEquals(2, f.geparst)
        }
    }

    @Test
    fun halte_traegt_seine_tage_in_den_cache_nach() {
        val f = fach("halte")
        sichtbar {
            Tagesspeicher.tage(f, merken = true)
            Tagesspeicher.halte(f, mapOf("schritte" to listOf(t1 to 9100.0, t3 to 400.0)), merken = true)
            assertEquals(mapOf("schritte" to 9100.0, "schlaf" to 420.0), f.tage[t1])
            // Cache und Fach stimmen ueberein, ohne zweites Parsen und ohne Lesen je Tag.
            assertEquals(f.tage, Tagesspeicher.tage(f, merken = true))
            assertEquals(1, f.geparst)
            assertEquals(0, f.einzeln)
        }
    }

    @Test
    fun halte_im_hintergrund_liest_nur_die_tage_und_legt_keinen_cache_an() {
        val f = fach("hintergrund")
        Tagesspeicher.halte(f, mapOf("schritte" to listOf(t1 to 8000.0, t2 to 6000.0)), merken = false)
        assertEquals(0, f.geparst)
        assertEquals(2, f.einzeln)
        assertEquals(mapOf("schritte" to 6000.0), f.tage[t2])
        sichtbar {
            Tagesspeicher.tage(f, merken = true)
            assertEquals(1, f.geparst)
        }
    }

    @Test
    fun halte_im_hintergrund_haelt_einen_offenen_cache_aktuell() {
        val f = fach("offen")
        sichtbar {
            Tagesspeicher.tage(f, merken = true)
            Tagesspeicher.halte(f, mapOf("hrv" to listOf(t3 to 41.0)), merken = false)
            assertEquals(f.tage, Tagesspeicher.tage(f, merken = true))
            assertEquals(1, f.geparst)
        }
    }

    @Test
    fun seitencache_haelt_nur_bei_sichtbarer_seite() {
        val c = Seitencache<String, Int>()
        c["a"] = 1
        assertNull(c["a"])
        // Beim Seitenwechsel ueberlappen zwei Seiten: erst die letzte leert.
        c.oeffne()
        c.oeffne()
        c["a"] = 1
        c.schliesse()
        assertEquals(1, c["a"])
        c.schliesse()
        assertNull(c["a"])
        // Ein Lesen, das erst nach dem Schliessen endet, bleibt nicht liegen.
        c["a"] = 2
        assertNull(c["a"])
        // Ein ueberzaehliges Schliessen macht den Zaehler nicht negativ.
        c.schliesse()
        c.oeffne()
        c["a"] = 3
        assertEquals(3, c["a"])
        c.vergiss("a")
        assertNull(c["a"])
    }

    private class Teil(val wert: String, val heil: Boolean = true)

    @Test
    fun ein_schub_nimmt_die_akte_nur_vom_selben_tag() = runTest {
        val c = Seitencache<LocalDate, Teil>().apply { oeffne() }
        var gelesen = 0
        suspend fun hole(heute: LocalDate, nurUhr: Boolean) =
            Trenddaten.akteFuer(c, heute, nurUhr, merken = true, heil = { it.heil }) { gelesen++; Teil("akte$gelesen") }.wert
        assertEquals("akte1", hole(t1, nurUhr = false))
        assertEquals("akte1", hole(t1, nurUhr = true))
        assertEquals(1, gelesen)
        // Aufbau und Rueckkehr lesen neu, ein neuer Tag auch.
        assertEquals("akte2", hole(t1, nurUhr = false))
        assertEquals("akte3", hole(t2, nurUhr = true))
    }

    @Test
    fun eine_gescheiterte_akte_uebernimmt_kein_schub() = runTest {
        val c = Seitencache<LocalDate, Teil>().apply { oeffne() }
        Trenddaten.akteFuer(c, t1, nurUhr = false, merken = true, heil = { it.heil }) { Teil("gut") }
        // Ein Rate-Limit: leer und nicht heil. Es verdraengt auch das alte Lesen.
        Trenddaten.akteFuer(c, t1, nurUhr = false, merken = true, heil = { it.heil }) { Teil("", heil = false) }
        assertNull(c[t1])
        // Der naechste Schub fragt die Akte wieder.
        val schub = Trenddaten.akteFuer(c, t1, nurUhr = true, merken = true, heil = { it.heil }) { Teil("wieder") }
        assertEquals("wieder", schub.wert)
        assertEquals("wieder", c[t1]?.wert)
    }

    @Test
    fun die_sicherung_laesst_den_akte_cache_wie_er_ist() = runTest {
        val c = Seitencache<LocalDate, Teil>().apply { oeffne() }
        Trenddaten.akteFuer(c, t1, nurUhr = false, merken = true, heil = { it.heil }) { Teil("seite") }
        Trenddaten.akteFuer(c, t1, nurUhr = false, merken = false, heil = { it.heil }) { Teil("sicherung") }
        Trenddaten.akteFuer(c, t1, nurUhr = false, merken = false, heil = { it.heil }) { Teil("", heil = false) }
        assertEquals("seite", c[t1]?.wert)
    }

    @Test
    fun erst_alles_dann_nur_die_uhr_und_nach_der_rueckkehr_wieder_alles() = runTest {
        val l = Trendladen()
        val nurUhr = mutableListOf<Boolean>()
        l.lade { nurUhr += it }
        l.lade { nurUhr += it }
        l.rueckkehr()
        l.lade { nurUhr += it }
        l.lade { nurUhr += it }
        assertEquals(listOf(false, true, false, true), nurUhr)
    }

    @Test
    fun bricht_ein_schub_das_volle_laden_ab_liest_der_naechste_alles() = runTest {
        val l = Trendladen()
        assertFailsWith<CancellationException> { l.lade<Unit> { throw CancellationException("neuer Schub") } }
        var nurUhr: Boolean? = null
        l.lade { nurUhr = it }
        assertEquals(false, nurUhr)
    }

    @Test
    fun ein_eigener_eintrag_laesst_den_naechsten_schub_alles_lesen() = runTest {
        var eintraege = 0
        val l = Trendladen { eintraege }
        val nurUhr = mutableListOf<Boolean>()
        l.lade { nurUhr += it }
        l.lade { nurUhr += it }
        eintraege++
        l.lade { nurUhr += it }
        l.lade { nurUhr += it }
        assertEquals(listOf(false, true, false, true), nurUhr)
    }

    @Test
    fun ein_eintrag_waehrend_des_vollen_ladens_gilt_fuer_das_naechste() = runTest {
        var eintraege = 0
        val l = Trendladen { eintraege }
        val nurUhr = mutableListOf<Boolean>()
        l.lade { nurUhr += it; eintraege++ }
        l.lade { nurUhr += it }
        assertEquals(listOf(false, false), nurUhr)
    }
}
