package coredevices.pebble.kiesel.ansicht

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import coredevices.pebble.kiesel.Sport
import theme.blackScheme
import theme.greyScheme
import theme.lightScheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Jede Farbe, die Schrift oder Linien traegt, muss auf ihrer Karte 3:1
 * erreichen. Vorher lagen "Rested" bei 2,28:1, die rote Akkuzahl bei 2,48:1
 * und die Pegellinien der Trends bei 1,5:1.
 */
class FarbenTest {
    private fun kontrast(a: Color, b: Color): Float {
        val hell = maxOf(a.luminance(), b.luminance())
        val dunkel = minOf(a.luminance(), b.luminance())
        return (hell + 0.05f) / (dunkel + 0.05f)
    }

    // Die Kartenflaechen, wie Farben.karte sie waehlt: Grau und Schwarz dunkel, Light hell.
    private val dunkleKarten = mapOf("grau" to greyScheme.surfaceContainerLow, "schwarz" to blackScheme.surfaceContainerLow)
    private val helleKarten = mapOf("hell" to lightScheme.surfaceContainerHigh, "weiss" to lightScheme.surface)

    private val groessen: Map<String, Farbe> = mapOf(
        "bewegung" to Farben.bewegung, "schlaf" to Farben.schlaf, "puls" to Farben.puls, "blutdruck" to Farben.blutdruck,
        "hrv" to Farben.hrv, "spo2" to Farben.spo2, "wasser" to Farben.wasser, "koffein" to Farben.koffein,
        "praeparat" to Farben.praeparat, "koerper" to Farben.koerper, "akku" to Farben.akku,
        "gut" to Farben.gut, "warnung" to Farben.warnung, "schlecht" to Farben.schlecht,
        "imBand" to Farben.imBand, "ausserBand" to Farben.ausserBand, "aktion" to Farben.aktion,
    ) + Farben.pulszonen.mapIndexed { i, f -> "pulszone$i" to f } +
        Farben.trainingszonen.mapIndexed { i, f -> "trainingszone$i" to f } +
        Sport.entries.associate { "sport.${it.name}" to Farben.sport(it) } +
        // Die Tempo-Skala faerbt Kurven und Bahnpunkte: jede Stufe muss tragen, nicht nur die Enden.
        (0..20).associate { "tempo${it * 5}%" to Farben.tempo(it / 20f) }

    private fun pruefe(name: String, ton: Color, karten: Map<String, Color>) {
        karten.forEach { (k, grund) ->
            val v = kontrast(ton, grund)
            assertTrue(v >= 3f, "$name auf $k: ${"%.2f".format(v)}:1 unter 3:1")
        }
    }

    @Test
    fun toeneTragenSchriftUndLinienAufJederKarte() {
        groessen.forEach { (name, f) ->
            pruefe("$name dunkel", f.dunkel, dunkleKarten)
            pruefe("$name hell", f.hell, helleKarten)
        }
    }

    @Test
    fun akkustufenTragenAuchAufDerSeite() {
        // Die Akkuzahl steht im Satz auf der Seite, nicht auf einer Karte: dunkel ist das Grau der Seite heller.
        listOf(10, 30, 80).map { Farben.akkuStufe(it) }.forEach { f ->
            pruefe("akku dunkel", f.dunkel, dunkleKarten + ("seite" to greyScheme.surface))
            pruefe("akku hell", f.hell, helleKarten)
        }
    }

    @Test
    fun akkustufenNachStand() {
        assertSame(Farben.bewegung, Farben.akkuStufe(50))
        assertSame(Farben.warnung, Farben.akkuStufe(49))
        assertSame(Farben.warnung, Farben.akkuStufe(20))
        assertSame(Farben.schlecht, Farben.akkuStufe(19))
    }

    @Test
    fun schlafsaeulenSindAufBeidenKartenZuSehen() {
        // Die Flaeche je Schema: das Dunkelblau kam auf der dunklen Karte auf 2:1.
        pruefe("schlaf flaeche dunkel", Farben.schlaf.flaecheDunkel, dunkleKarten)
        pruefe("schlaf flaeche hell", Farben.schlaf.flaecheHell, helleKarten)
    }

    @Test
    fun knopfschriftTraegtAufDemBlassenKnopf() {
        // Der Knopf der Knopfzeile: 22 % App-Gruen ueber der Karte, darauf die Schrift - Text, also 4,5:1.
        val d = Farben.aktion
        dunkleKarten.forEach { (k, grund) ->
            val v = kontrast(d.dunkel, d.flaecheDunkel.copy(alpha = 0.22f).compositeOver(grund))
            assertTrue(v >= 4.5f, "aktion dunkel auf Knopf/$k: ${"%.2f".format(v)}:1")
        }
        helleKarten.forEach { (k, grund) ->
            val v = kontrast(d.hell, d.flaecheHell.copy(alpha = 0.22f).compositeOver(grund))
            assertTrue(v >= 4.5f, "aktion hell auf Knopf/$k: ${"%.2f".format(v)}:1")
        }
        assertEquals(greyScheme.primary, d.flaecheDunkel)
        assertEquals(lightScheme.primary, d.flaecheHell)
    }

    @Test
    @Suppress("DEPRECATION")
    fun alteTempofarbeIstDieFlaeche() {
        // Die Karte (OSM) und ihre Legende zeigen weiter dieselbe Skala.
        assertEquals(Color.hsv(110f, 0.85f, 0.9f), tempofarbe(0.5f))
        assertEquals(Farben.tempo(0.5f).flaecheHell, Farben.tempo(0.5f).flaecheDunkel)
    }

    @Test
    fun gutIstDasGruenDesSchemas() {
        assertEquals(greyScheme.primary, Farben.gut.dunkel)
        assertEquals(lightScheme.primary, Farben.gut.hell)
    }

    @Test
    fun eineGroesseEineFarbe() {
        assertSame(Farben.puls, Farben.blutdruck)
        assertEquals(Farben.bewegung.tonIm(true), Farben.praeparat.tonIm(true))
        assertEquals(Farben.bewegung.tonIm(false), Farben.praeparat.tonIm(false))
        // Bike und MTB sind zwei Arten und brauchen zwei Farben.
        assertTrue(Farben.sport(Sport.Bike).flaecheDunkel != Farben.sport(Sport.Mtb).flaecheDunkel)
    }

    @Test
    @Suppress("DEPRECATION")
    fun alteSportfarbeBleibtGleich() {
        // Die Weiterleitung darf bis zur Umstellung nichts sichtbar aendern.
        assertEquals(0xFFE8622EL, Sport.Laufen.farbe)
        assertEquals(0xFFB8792AL, Sport.Mtb.farbe)
        assertEquals(0xFF9C8B7CL, Sport.Anderes.farbe)
        assertEquals(Color(0xFFFF0055), ZONENFARBEN[5])
    }
}
