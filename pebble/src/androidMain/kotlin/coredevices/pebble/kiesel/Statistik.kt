package coredevices.pebble.kiesel

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Eine runde y-Achse: Linien von [von] bis [bis] im Abstand [schritt]. */
internal data class Achse(val von: Double, val bis: Double, val schritt: Double) {
    // Gerundet auf Zehntel: 3 * 0.1 ist in Double 0.30000000000000004.
    val linien: List<Double>
        get() = (0..((bis - von) / schritt).roundToInt()).map { Math.round((von + it * schritt) * 10) / 10.0 }
}

/**
 * Die Rechenbausteine, die mehrere Seiten teilen: Median, Mittel, Streuung
 * und runde Achsen. Vorher rechnete jede Stelle ihren eigenen Median - in
 * drei Bedeutungen, ohne dass der Name es sagte. Hier hat jede Bedeutung
 * ihren Namen, und wer rechnet, waehlt sie sichtbar.
 *
 * Reine Rechnung ohne Android; die Pruefungen stehen in `StatistikTest`.
 */
internal object Statistik {

    // --- Median ---

    /**
     * Der Median: der mittlere Wert, BEI GERADER ANZAHL DAS MITTEL DER BEIDEN
     * MITTLEREN. Fuer Linien und Vergleiche - [10, 20, 30, 40] ergibt 25.
     * Wirft bei einer leeren Liste.
     */
    fun median(werte: List<Double>): Double {
        val s = werte.sorted()
        val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2
    }

    /** [median] fuer ganze Zahlen; das Mittel der beiden mittleren bleibt ein Bruch (52.5). */
    @JvmName("medianGanz")
    fun median(werte: List<Int>): Double {
        val s = werte.sorted()
        val m = s.size / 2
        return if (s.size % 2 == 1) s[m].toDouble() else (s[m - 1] + s[m]) / 2.0
    }

    /**
     * Der obere Median: BEI GERADER ANZAHL DER OBERE DER BEIDEN MITTLEREN -
     * [10, 20, 30, 40] ergibt 30. Er ist immer ein tatsaechlich gemessener
     * Wert; das zaehlt, wo die Zahl als Messung dasteht (Puls je fuenf Minuten).
     * Wirft bei einer leeren Liste.
     */
    fun obererMedian(werte: List<Int>): Int {
        val s = werte.sorted()
        return s[s.size / 2]
    }

    // --- Mittel und Streuung ---

    /** Das arithmetische Mittel, oder null ohne Werte - [Iterable.average] gaebe NaN. */
    fun mittel(werte: List<Double>): Double? = werte.takeIf { it.isNotEmpty() }?.average()

    @JvmName("mittelGanz")
    fun mittel(werte: List<Int>): Double? = werte.takeIf { it.isNotEmpty() }?.average()

    /**
     * Die Varianz DER STICHPROBE (geteilt durch n - 1): die Tage sind eine
     * Auswahl aus allen, nicht alle. Null unter zwei Werten - eine Zahl
     * allein streut nicht messbar.
     */
    fun varianz(werte: List<Double>): Double? {
        if (werte.size < 2) return null
        val m = werte.average()
        return werte.sumOf { (it - m) * (it - m) } / (werte.size - 1)
    }

    /** Die Standardabweichung der Stichprobe (siehe [varianz]). */
    fun streuung(werte: List<Double>): Double? = varianz(werte)?.let { sqrt(it) }

    /**
     * Die Standardabweichung ALLER WERTE (geteilt durch n): wo die Werte
     * nicht fuer mehr stehen als sich selbst - etwa die Runden eines
     * Trainings. Null ohne Werte.
     */
    fun streuungAllerWerte(werte: List<Double>): Double? {
        if (werte.isEmpty()) return null
        val m = werte.average()
        return sqrt(werte.sumOf { (it - m) * (it - m) } / werte.size)
    }

    // --- Runde Achsen ---

    /**
     * Die runden Schritte 1, 2, 5 mal Zehnerpotenz von [ab] bis [bis]. Unter
     * 1 geteilt statt mal 0.1 gerechnet: 2.0 / 10 ist genau die Zahl 0.2,
     * 2.0 * 0.1 ist es nicht immer.
     */
    fun rundeSchritte(ab: Double, bis: Double): List<Double> = (-6..9).flatMap { k ->
        val zehn = Math.pow(10.0, kotlin.math.abs(k).toDouble())
        listOf(1.0, 2.0, 5.0).map { if (k >= 0) it * zehn else it / zehn }
    }.filter { it >= ab && it <= bis }

    /** Die Vielfachen von [schritt] zwischen [von] und [bis], beide eingeschlossen. */
    fun vielfache(von: Double, bis: Double, schritt: Double): List<Double> {
        val erster = Math.ceil(von / schritt).toLong()
        val letzter = Math.floor(bis / schritt).toLong()
        return (erster..letzter).map { it * schritt }
    }

    /** Der kleinste der [schritte], mit dem hoechstens [hoechstens] Vielfache zwischen [von] und [bis] liegen. */
    private fun kleinsterSchritt(von: Double, bis: Double, hoechstens: Int, schritte: List<Double>): Double? =
        schritte.firstOrNull { vielfache(von, bis, it).size <= hoechstens }

    /**
     * RASTER INNEN: runde Linien nur zwischen [tief] und [hoch], hoechstens
     * fuenf, der kleinste Schritt ab [mindestSchritt] (bis 1000). Die Achse
     * reicht nie ueber die Werte hinaus - die Bilder von Puls, SpO2 und
     * Blutdruck haben ihren Bereich schon. Ein Puls will Zehner, eine
     * SpO2-Achse Fuenfer.
     */
    fun rasterInnen(tief: Int, hoch: Int, mindestSchritt: Int = 1): List<Int> {
        if (hoch < tief) return emptyList()
        val schritt = kleinsterSchritt(tief.toDouble(), hoch.toDouble(), 5, rundeSchritte(maxOf(mindestSchritt, 1).toDouble(), 1000.0))
            ?: return emptyList()
        return vielfache(tief.toDouble(), hoch.toDouble(), schritt).map { it.toInt() }
    }

    /**
     * RASTER MIT RAND: wie [rasterInnen] (ab Schritt 1, hoechstens fuenf
     * Striche), aber BLEIBEN WENIGER ALS DREI, wird der Bereich auf den
     * Schritt nach aussen gerundet und die Randstriche kommen dazu - das
     * Bild nimmt die aeusseren Striche dann als Grenzen (Trends, Verlauf).
     *
     * Striche bei min, Mitte und max sind Zahlen wie 53, 61 und 69 - niemand
     * kann daran ablesen, wo 60 liegt. Runde Schritte kann man lesen; zwei
     * Striche allein (55 und 60 bei 52..62) zeigen aber keinen Massstab.
     */
    fun rasterMitRand(von: Double, bis: Double): List<Double> {
        if (bis <= von) return listOf(von)
        val schritte = rundeSchritte(1.0, 5_000_000.0)
        val schritt = kleinsterSchritt(von, bis, 5, schritte) ?: schritte.last()
        val innen = vielfache(von, bis, schritt)
        if (innen.size >= 3) return innen
        return vielfache(Math.floor(von / schritt) * schritt, Math.ceil(bis / schritt) * schritt, schritt)
    }

    /**
     * RASTER NACH PLATZ: runde Linien STRIKT zwischen [tief] und [hoch], der
     * kleinste Schritt von 1 bis 100, bei dem zwei Zahlen der Achse
     * mindestens gut eine Zeile ([zeile], Pixel) auseinander stehen und
     * hoechstens vier Linien entstehen. [platz]: die Hoehe der Achse in
     * Pixeln. Fuer Bilder, deren Hoehe die Dichte der Linien bestimmt
     * (Tageskurve der Nacht); ohne passenden Schritt keine Linien.
     */
    fun rasterNachPlatz(tief: Int, hoch: Int, platz: Float, zeile: Float): List<Int> {
        val spanne = (hoch - tief).coerceAtLeast(1)
        // Ganzzahlig wie zuvor: mit Double statt Float kippte der Vergleich an der Grenze anders.
        for (schritt in rundeSchritte(1.0, 100.0).map { it.toInt() }) {
            if (platz * schritt / spanne < zeile * 1.2f) continue
            // Strikt dazwischen heisst bei ganzen Zahlen: von tief + 1 bis hoch - 1.
            val linien = vielfache((tief + 1).toDouble(), (hoch - 1).toDouble(), schritt.toDouble()).map { it.toInt() }
            if (linien.size <= 4) return linien
        }
        return emptyList()
    }

    /**
     * RASTER AUSSEN: [unten] bis [oben] auf runde Schritte ab 0.1 nach aussen
     * gerundet, hoechstens [felder] Felder - die Achse umschliesst die Werte
     * immer (Koerper). Ab Schritt 100 ohne Treffer: der Bereich auf ganze
     * Zahlen als ein Feld.
     */
    fun rasterAussen(unten: Double, oben: Double, felder: Int): Achse {
        for (schritt in rundeSchritte(0.1, 100.0)) {
            // Der kleine Zuschlag faengt Rundungsfehler: 77.5 / 0.5 ist nicht immer genau 155.
            val von = floor(unten / schritt + 1e-9) * schritt
            val bis = ceil(oben / schritt - 1e-9) * schritt
            if ((bis - von) / schritt <= felder + 1e-9) return Achse(von, bis, schritt)
        }
        return Achse(floor(unten), ceil(oben), ceil(oben) - floor(unten))
    }
}
