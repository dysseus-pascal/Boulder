package coredevices.pebble.kiesel

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Boulder: was [Tagesspeicher.halte] aus dem Prozess-Cache schreibt, und wie ein Fach zu Reihen wird. */
class TagesspeicherTest {
    private val t1 = LocalDate.of(2026, 9, 1)
    private val t2 = LocalDate.of(2026, 9, 2)
    private val t3 = LocalDate.of(2026, 9, 3)

    /** Die Rechnung vor dem Cache, nachgebaut: je Tag geparst, gleiche Regel wie JSONObject.has/optDouble. */
    private fun bisher(reihen: Reihen, gespeichert: Map<LocalDate, Map<String, Double>>): Map<LocalDate, Map<String, Double>> {
        val aus = mutableMapOf<LocalDate, Map<String, Double>>()
        reihen.flatMap { (k, l) -> l.map { (t, w) -> Triple(t, k, w) } }.groupBy { it.first }.forEach { (tag, l) ->
            val alt = gespeichert[tag].orEmpty().toMutableMap()
            val neu = l.filter { (_, k, w) -> !alt.containsKey(k) || alt.getValue(k) != w }
            if (neu.isEmpty()) return@forEach
            neu.forEach { (_, k, w) -> alt[k] = w }
            aus[tag] = alt
        }
        return aus
    }

    private val gespeichert = mapOf(
        t1 to mapOf("schritte" to 8000.0, "schlaf" to 420.0),
        t2 to mapOf("schritte" to 5000.0),
    )

    @Test
    fun nichts_geaendert_nichts_geschrieben() {
        val reihen = mapOf("schritte" to listOf(t1 to 8000.0, t2 to 5000.0), "schlaf" to listOf(t1 to 420.0))
        assertTrue(Tagesspeicher.zuSchreiben(reihen) { gespeichert[it] }.isEmpty())
    }

    @Test
    fun ein_geaenderter_wert_schreibt_den_ganzen_tag() {
        // Ein spaeter Sync: t1 hat mehr Schritte, die Nacht bleibt im Tag stehen.
        val reihen = mapOf("schritte" to listOf(t1 to 9100.0, t2 to 5000.0), "hrv" to listOf(t3 to 41.0))
        val neu = Tagesspeicher.zuSchreiben(reihen) { gespeichert[it] }
        assertEquals(mapOf(t1 to mapOf("schritte" to 9100.0, "schlaf" to 420.0), t3 to mapOf("hrv" to 41.0)), neu)
        assertEquals(bisher(reihen, gespeichert), neu)
    }

    @Test
    fun gleich_wie_bisher_ueber_viele_tage() {
        val r = java.util.Random(7)
        val tage = (0L until 120L).map { LocalDate.of(2026, 6, 1).plusDays(it) }
        val spalten = listOf("schritte", "aktiv", "schlaf", "tief", "ruhepuls", "hrv")
        val alt = tage.filter { r.nextInt(4) > 0 }.associateWith { spalten.filter { r.nextBoolean() }.associateWith { r.nextInt(5).toDouble() } }
        val reihen = spalten.associateWith { tage.filter { r.nextInt(3) > 0 }.map { it to r.nextInt(5).toDouble() } }
        assertEquals(bisher(reihen, alt), Tagesspeicher.zuSchreiben(reihen) { alt[it] })
    }

    @Test
    fun ein_fach_wird_zu_aufsteigenden_reihen() {
        val r = Tagesspeicher.alsReihen(mapOf(t3 to mapOf("schritte" to 3.0), t1 to mapOf("schritte" to 1.0, "hrv" to 40.0)))
        assertEquals(listOf(t1 to 1.0, t3 to 3.0), r["schritte"])
        assertEquals(listOf(t1 to 40.0), r["hrv"])
    }

    @Test
    fun reihenJeTagUndZurueck() {
        val tag = LocalDate.of(2026, 10, 15)
        val reihen = mapOf("schritte" to listOf(tag to 9000.0), "schlaf" to listOf(tag to 431.0, tag.plusDays(1) to 400.0))
        val tage = Tagesspeicher.jeTag(reihen)
        assertEquals(mapOf(tag to mapOf("schritte" to 9000.0, "schlaf" to 431.0), tag.plusDays(1) to mapOf("schlaf" to 400.0)), tage)
        assertEquals(reihen, Tagesspeicher.alsReihen(tage))
    }
}
