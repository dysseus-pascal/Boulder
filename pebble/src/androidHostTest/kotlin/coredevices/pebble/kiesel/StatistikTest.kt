package coredevices.pebble.kiesel

import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Die Rechenbausteine. Jede Median-Bedeutung mit einer geraden Anzahl
 * geprueft - genau dort unterscheiden sie sich, und genau das sah man den
 * frueheren Kopien nicht an.
 */
class StatistikTest {

    @Test
    fun median_nimmt_bei_gerader_anzahl_das_mittel_der_beiden_mittleren() {
        assertEquals(25.0, Statistik.median(listOf(40.0, 10.0, 30.0, 20.0)))
        assertEquals(20.0, Statistik.median(listOf(30.0, 10.0, 20.0)))
        assertEquals(7.0, Statistik.median(listOf(7.0)))
        // Ganze Zahlen bleiben im Mittel ein Bruch.
        assertEquals(52.5, Statistik.median(listOf(53, 52)))
        assertEquals(52.0, Statistik.median(listOf(54, 50, 52)))
    }

    @Test
    fun median_ohne_werte_wirft() {
        assertFailsWith<IndexOutOfBoundsException> { Statistik.median(emptyList<Double>()) }
    }

    @Test
    fun oberer_median_ist_ein_gemessener_wert() {
        assertEquals(30, Statistik.obererMedian(listOf(40, 10, 30, 20)))
        assertEquals(20, Statistik.obererMedian(listOf(30, 10, 20)))
        // Belastung der Vortage: der obere Median 40, der echte waere 35.
        assertEquals(40, Statistik.obererMedian(listOf(10, 20, 30, 40, 50, 60)))
    }

    @Test
    fun mittel_ohne_werte_ist_null_statt_nan() {
        assertNull(Statistik.mittel(emptyList<Double>()))
        assertNull(Statistik.mittel(emptyList<Int>()))
        assertEquals(2.0, Statistik.mittel(listOf(1.0, 2.0, 3.0)))
        assertEquals(52.5, Statistik.mittel(listOf(52, 53)))
    }

    @Test
    fun streuung_der_stichprobe_und_aller_werte() {
        assertNull(Statistik.varianz(listOf(5.0)))
        assertNull(Statistik.streuung(listOf(5.0)))
        assertEquals(1.0, Statistik.varianz(listOf(1.0, 2.0, 3.0))!!, 1e-12)
        assertEquals(1.0, Statistik.streuung(listOf(1.0, 2.0, 3.0))!!, 1e-12)
        // Durch n statt n - 1: dieselben drei Werte streuen weniger.
        assertEquals(sqrt(2.0 / 3.0), Statistik.streuungAllerWerte(listOf(1.0, 2.0, 3.0))!!, 1e-12)
        assertEquals(0.0, Statistik.streuungAllerWerte(listOf(4.0))!!)
        assertNull(Statistik.streuungAllerWerte(emptyList()))
    }

    @Test
    fun runde_schritte_sind_genau_die_dezimalzahlen() {
        // Gleich als Double: 0.2 und 0.5 ohne Rundungsrest, sonst laege die Koerperachse daneben.
        assertEquals(
            listOf(0.1, 0.2, 0.5, 1.0, 2.0, 5.0, 10.0, 20.0, 50.0, 100.0),
            Statistik.rundeSchritte(0.1, 100.0),
        )
        assertEquals(listOf(10.0, 20.0, 50.0, 100.0, 200.0, 500.0, 1000.0), Statistik.rundeSchritte(10.0, 1000.0))
        assertEquals(5_000_000.0, Statistik.rundeSchritte(1.0, 5_000_000.0).last())
    }

    @Test
    fun vielfache_mit_beiden_raendern() {
        assertEquals(listOf(50.0, 55.0, 60.0), Statistik.vielfache(50.0, 60.0, 5.0))
        assertEquals(listOf(-5.0, 0.0), Statistik.vielfache(-7.0, 4.0, 5.0))
        assertTrue(Statistik.vielfache(61.0, 64.0, 5.0).isEmpty())
    }

    @Test
    fun raster_innen_in_runden_schritten() {
        assertEquals(listOf(60, 80, 100, 120, 140), Statistik.rasterInnen(47, 147, 10))
        assertEquals(listOf(50, 60, 70, 80, 90), Statistik.rasterInnen(50, 90, 10))
        assertEquals(listOf(90, 95, 100), Statistik.rasterInnen(88, 100, 5))
        assertEquals(listOf(55), Statistik.rasterInnen(55, 55))
        assertTrue(Statistik.rasterInnen(5, 4).isEmpty())
        // Mit Zehnerschritten waeren das acht Linien; das Raster nimmt Zwanziger.
        assertEquals(listOf(80, 100, 120, 140), Statistik.rasterInnen(70, 140, 10))
        // Ueber Schritt 1000 hinaus gibt es kein Raster.
        assertTrue(Statistik.rasterInnen(0, 10_000).isEmpty())
    }

    @Test
    fun raster_mit_rand_ist_rund_und_hoechstens_fuenf() {
        assertEquals(listOf(50.0, 60.0, 70.0), Statistik.rasterMitRand(45.0, 70.0))
        assertEquals(listOf(46.0, 48.0, 50.0, 52.0), Statistik.rasterMitRand(45.0, 52.0))
        assertEquals(listOf(50.0, 55.0, 60.0), Statistik.rasterMitRand(50.0, 60.0))
        assertEquals(listOf(55.0), Statistik.rasterMitRand(55.0, 55.0))
        assertEquals(listOf(4000.0, 6000.0, 8000.0, 10000.0), Statistik.rasterMitRand(3500.0, 11000.0))
    }

    @Test
    fun raster_mit_rand_hat_nie_weniger_als_drei() {
        assertEquals(listOf(50.0, 55.0, 60.0, 65.0), Statistik.rasterMitRand(52.0, 62.0))
        assertEquals(listOf(45.0, 50.0, 55.0, 60.0), Statistik.rasterMitRand(46.0, 58.0))
        assertEquals(listOf(45.0, 50.0, 55.0, 60.0), Statistik.rasterMitRand(46.0, 59.0))
    }

    @Test
    fun raster_aussen_umschliesst_die_werte() {
        assertEquals(Achse(77.0, 80.0, 1.0), Statistik.rasterAussen(77.6, 79.6, 3))
        assertEquals(Achse(77.5, 79.0, 0.5), Statistik.rasterAussen(77.9, 78.9, 3))
        // Der Zuschlag gegen Rundungsfehler: 77.5 liegt genau auf der Linie.
        assertEquals(Achse(77.5, 79.0, 0.5), Statistik.rasterAussen(77.5, 79.0, 3))
        // Kein runder Schritt bis 100 passt: der Bereich als ein Feld.
        assertEquals(Achse(0.0, 1000.0, 1000.0), Statistik.rasterAussen(0.0, 1000.0, 3))
    }
}
