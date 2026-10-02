package coredevices.pebble.kiesel

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SicherungsformatTest {
    private val t1 = LocalDate.of(2026, 9, 1)
    private val t2 = LocalDate.of(2026, 9, 2)

    @Test
    fun hin_und_zurueck_ergibt_dasselbe() {
        val tage = listOf(t1 to mapOf("schritte" to 8000.0, "schlaf" to 420.0), t2 to mapOf("hrv" to 44.0))
        val s = Sicherungsformat.ausJson(Sicherungsformat.alsJson(tage, listOf(1_790_000_000L), "jetzt"))!!
        assertEquals(tage, s.tage)
        assertEquals(listOf(1_790_000_000L), s.spuren)
    }

    @Test
    fun kiesel_helper_sicherung_wird_gelesen_fremde_spalten_fallen_weg() {
        val text = """{"fassung":1,"erzeugt":"x","app":"Kiesel-Helper","tage":[
            {"datum":"2026-09-01","schritte":5000,"unbekannt":3},
            {"datum":"kaputt","schritte":1},
            {"datum":"2026-09-02","puls_min":49}],"einstellungen":{"schlaf_ziel_min":"480"},"spuren":[123,0]}"""
        val s = Sicherungsformat.ausJson(text)!!
        assertEquals(listOf(t1 to mapOf("schritte" to 5000.0), t2 to mapOf("puls_min" to 49.0)), s.tage)
        assertEquals(listOf(123L), s.spuren)
    }

    @Test
    fun eigene_werte_und_vorgeschichte_bleiben_getrennt() {
        val eigen = listOf(t2 to mapOf("schritte" to 8000.0, "einschlafen" to 290.0))
        val davor = listOf(t1 to mapOf("schritte" to 4200.0))
        val s = Sicherungsformat.ausJson(Sicherungsformat.alsJson(eigen, emptyList(), "jetzt", davor))!!
        assertEquals(Sicherungsformat.FASSUNG, s.fassung)
        // "einschlafen" kommt zurueck - sonst fehlte "Resting HR after a late bedtime" jede gesicherte Nacht.
        assertEquals(eigen, s.tage)
        assertEquals(davor, s.vorgeschichte)
    }

    @Test
    fun alte_sicherung_hat_keine_getrennte_vorgeschichte() {
        val s = Sicherungsformat.ausJson("""{"fassung":1,"erzeugt":"x","app":"Boulder","tage":[{"datum":"2026-09-01","schritte":5000}],"spuren":[]}""")!!
        assertEquals(1, s.fassung)
        assertEquals(emptyList(), s.vorgeschichte)
    }

    @Test
    fun kiesel_helper_spalten_fuer_boulder() {
        val w = Sicherungsformat.fuerBoulder(mapOf("schlaf_von" to 310.0, "aktiv" to 45.0, "schritte" to 4200.0))
        assertEquals(310.0, w["einschlafen"])
        // "aktiv" bleibt gespeichert: in einer Boulder-Sicherung der Fassung 1 sind es auch eigene Aktivminuten.
        assertEquals(45.0, w["aktiv"])
        assertEquals(4200.0, w["schritte"])
        assertEquals(300.0, Sicherungsformat.fuerBoulder(mapOf("schlaf_von" to 310.0, "einschlafen" to 300.0))["einschlafen"])
    }

    @Test
    fun keine_sicherung_ergibt_nichts() {
        assertNull(Sicherungsformat.ausJson("""{"hallo":1}"""))
        assertNull(Sicherungsformat.ausJson("kein json"))
        assertNull(Sicherungsformat.ausJson(""))
    }

    @Test
    fun geschaetzter_ruhepuls_nur_ohne_gemessenen() {
        assertEquals(49.0, Sicherungsformat.fuerBoulder(mapOf("puls_min" to 49.0))["ruhepuls"])
        assertEquals(52.0, Sicherungsformat.fuerBoulder(mapOf("puls_min" to 49.0, "ruhepuls" to 52.0))["ruhepuls"])
    }
}
