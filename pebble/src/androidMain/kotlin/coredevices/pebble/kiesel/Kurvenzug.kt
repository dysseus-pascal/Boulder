package coredevices.pebble.kiesel

import kotlin.math.sqrt

/**
 * Eine weiche Linie DURCH jeden Punkt, die nie ueber oder unter ihre Punkte
 * schwingt (monotone kubische Interpolation nach Fritsch und Carlson): das
 * Hoechste im Bild ist der hoechste Punkt. Ohne Android, fuer PulsbildTest.
 */
internal object Kurvenzug {
    /** Ein Stueck der Linie: von ([x0], [y0]) mit den Stuetzen c1 und c2 nach ([x1], [y1]) - eine kubische Bezierkurve. */
    data class Stueck(
        val x0: Float, val y0: Float,
        val c1x: Float, val c1y: Float,
        val c2x: Float, val c2y: Float,
        val x1: Float, val y1: Float,
    ) {
        fun bei(t: Float): Pair<Float, Float> {
            val u = 1 - t
            fun b(a: Float, b: Float, c: Float, d: Float) = u * u * u * a + 3 * u * u * t * b + 3 * u * t * t * c + t * t * t * d
            return b(x0, c1x, c2x, x1) to b(y0, c1y, c2y, y1)
        }
    }

    /** Die Stuecke durch die Punkte ([x] streng steigend); leer unter zwei Punkten. */
    fun durch(x: List<Float>, y: List<Float>): List<Stueck> {
        val n = minOf(x.size, y.size)
        if (n < 2) return emptyList()
        val h = FloatArray(n - 1) { x[it + 1] - x[it] }
        val steigung = FloatArray(n - 1) { if (h[it] != 0f) (y[it + 1] - y[it]) / h[it] else 0f }
        val m = FloatArray(n)
        m[0] = steigung[0]
        m[n - 1] = steigung[n - 2]
        // An einem Gipfel oder Tal (die Steigung wechselt das Vorzeichen) waagrecht - sonst schwaenge die Linie darueber.
        for (k in 1 until n - 1) m[k] = if (steigung[k - 1] * steigung[k] <= 0f) 0f else (steigung[k - 1] + steigung[k]) / 2f
        for (k in 0 until n - 1) {
            if (steigung[k] == 0f) {
                m[k] = 0f
                m[k + 1] = 0f
                continue
            }
            // Zu steile Tangenten stutzen: mit ihnen ueberschiesse das Stueck seine Enden (Fritsch-Carlson, Kreis vom Radius 3).
            val a = m[k] / steigung[k]
            val b = m[k + 1] / steigung[k]
            val s = a * a + b * b
            if (s > 9f) {
                val t = 3f / sqrt(s)
                m[k] = t * a * steigung[k]
                m[k + 1] = t * b * steigung[k]
            }
        }
        return (0 until n - 1).map { k ->
            Stueck(
                x[k], y[k],
                x[k] + h[k] / 3, y[k] + m[k] * h[k] / 3,
                x[k + 1] - h[k] / 3, y[k + 1] - m[k + 1] * h[k] / 3,
                x[k + 1], y[k + 1],
            )
        }
    }
}
