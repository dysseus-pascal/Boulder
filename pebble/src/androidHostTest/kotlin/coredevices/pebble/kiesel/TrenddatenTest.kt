package coredevices.pebble.kiesel

import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

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
    fun schlafmitte_zaehlt_ab_18_uhr_des_vortags() {
        // 23:00 bis 07:00 -> Mitte 03:00 -> neun Stunden nach 18 Uhr.
        val ein = t1.atTime(23, 0).toInstant(ZoneOffset.UTC)
        val auf = t2.atTime(7, 0).toInstant(ZoneOffset.UTC)
        assertEquals(540.0, Trenddaten.schlafMitte(ein, auf, ZoneOffset.UTC))
    }
}
