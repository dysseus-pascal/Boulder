package coredevices.pebble.kiesel

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Die Bausteine des Pulsbilds, jeder fuer sich: [Kurvenzug] (die Linie durch
 * Punkte), [Gesundheitsrechnung.pulslinie] (welche Punkte, wo sie bricht) und
 * Spanne und Spitze des Satzes. Ob die gezeichnete Karte daraus das Richtige
 * macht, misst PulsKarteBildTest am Bild nach - hier wird die Zeichnung nicht
 * nachgebaut.
 */
class PulsbildTest {
    private val t0 = 1_790_000_000L / 300 * 300

    /** Minutenwerte: je [werte]-Eintrag fuenf Minuten mit diesem Puls. */
    private fun minuten(vararg werte: Int) = werte.flatMapIndexed { i, w -> (0 until 5).map { m -> t0 + i * 300L + m * 60L to w } }

    /** Die Stuecke an vielen Stellen ausgewertet: (x, y) je Stelle. */
    private fun abgetastet(stuecke: List<Kurvenzug.Stueck>) = stuecke.flatMap { k -> (0..40).map { k.bei(it / 40f) } }

    @Test
    fun ein_einzelner_spitzenwert_wird_erreicht_und_nicht_ueberschossen() {
        // Fuenf Minuten bei 150 zwischen 70ern: die Linie erreicht genau 150.
        val y = listOf(70f, 70f, 150f, 70f, 70f)
        val linie = abgetastet(Kurvenzug.durch(listOf(0f, 300f, 600f, 900f, 1200f), y))
        assertEquals(150f, linie.maxOf { it.second }, 0.01f)
        assertEquals(70f, linie.minOf { it.second }, 0.01f)
    }

    @Test
    fun die_linie_geht_durch_jeden_punkt_und_schwingt_nie_darueber() {
        val r = Random(41)
        repeat(300) { lauf ->
            var p = 60f + r.nextInt(40)
            val n = 3 + r.nextInt(80)
            val x = (0 until n).map { it * 300f }
            val y = (0 until n).map { p = (p + r.nextInt(-30, 31)).coerceIn(40f, 190f); p }
            val stuecke = Kurvenzug.durch(x, y)
            assertEquals(n - 1, stuecke.size, "Lauf $lauf")
            // Jedes Stueck beginnt und endet auf seinen Punkten.
            stuecke.forEachIndexed { i, k ->
                assertEquals(x[i] to y[i], k.bei(0f), "Lauf $lauf, Anfang $i")
                assertEquals(x[i + 1].toDouble(), k.bei(1f).first.toDouble(), 0.01, "Lauf $lauf, Ende $i")
                assertEquals(y[i + 1].toDouble(), k.bei(1f).second.toDouble(), 0.01, "Lauf $lauf, Ende $i")
            }
            // Zwischen zwei Punkten nur steigen oder fallen: nie ueber den hoeheren, nie unter den tieferen.
            stuecke.forEachIndexed { i, k ->
                val werte = (0..40).map { k.bei(it / 40f).second }
                assertTrue(werte.max() <= maxOf(y[i], y[i + 1]) + 0.01f, "Lauf $lauf, Stueck $i ueberschiesst")
                assertTrue(werte.min() >= minOf(y[i], y[i + 1]) - 0.01f, "Lauf $lauf, Stueck $i unterschiesst")
            }
        }
    }

    @Test
    fun unter_zwei_punkten_gibt_es_keine_linie() {
        assertEquals(emptyList(), Kurvenzug.durch(listOf(0f), listOf(70f)))
    }

    @Test
    fun satz_und_linie_nehmen_die_mediane_nicht_den_ausreisser() {
        // Eine Minute 175 in zehn Minuten bei 150: sie steht in der Saeule (hoechster), nicht im Satz.
        val bins = Gesundheitsrechnung.fuenfMinuten(minuten(70, 70, 150, 150, 70, 70).map { if (it.first == t0 + 660) it.first to 175 else it })
        assertEquals(175, bins.maxOf { it.hoechster })
        assertEquals(70 to 150, Gesundheitsrechnung.spanneDerBins(bins))
        // Die Spitze im Satz ist die juengste der beiden 150er - und ein Punkt der Linie.
        assertEquals(t0 + 900 to 150, Gesundheitsrechnung.spitzeDerBins(bins))
        assertEquals(listOf(70, 70, 150, 150, 70, 70), Gesundheitsrechnung.pulslinie(bins).single().map { it.second })
    }

    @Test
    fun luecken_brechen_die_linie_und_ein_einzelner_bin_ist_ein_punkt() {
        // 20 Minuten ohne Messung (Uhr nicht am Arm) trennen, ein einzelner Wert danach steht fuer sich.
        val punkte = minuten(80, 90) + (t0 + 1800L to 100)
        val linie = Gesundheitsrechnung.pulslinie(Gesundheitsrechnung.fuenfMinuten(punkte))
        assertEquals(listOf(listOf(t0 to 80, t0 + 300 to 90), listOf(t0 + 1800 to 100)), linie)
        // Genau 15 Minuten Abstand trennen noch nicht, 15 Minuten und 5 schon.
        assertEquals(1, Gesundheitsrechnung.pulslinie(Gesundheitsrechnung.fuenfMinuten(minuten(80) + (t0 + 900L to 90))).size)
        assertEquals(2, Gesundheitsrechnung.pulslinie(Gesundheitsrechnung.fuenfMinuten(minuten(80) + (t0 + 1200L to 90))).size)
    }
}
