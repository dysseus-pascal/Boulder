package coredevices.pebble.kiesel.ansicht

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import coredevices.pebble.kiesel.Sport
import theme.blackScheme
import theme.greyScheme
import theme.lightScheme
import kotlin.math.abs
import kotlin.math.sqrt
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
    fun tempoflaecheIstDieKartenskala() {
        // Die Karte (OSM) und ihre Legende zeigen in beiden Schemata dieselbe Skala wie vor Etappe 2.
        assertEquals(Color.hsv(110f, 0.85f, 0.9f), Farben.tempo(0.5f).flaecheDunkel)
        assertEquals(Farben.tempo(0.5f).flaecheHell, Farben.tempo(0.5f).flaecheDunkel)
    }

    /** Der Farbton in Grad (0..360) - fuer den Vergleich von Ton und Flaeche. */
    private fun farbton(c: Color): Float {
        val max = maxOf(c.red, c.green, c.blue)
        val d = max - minOf(c.red, c.green, c.blue)
        val h = when (max) {
            c.red -> ((c.green - c.blue) / d).mod(6f)
            c.green -> (c.blue - c.red) / d + 2
            else -> (c.red - c.green) / d + 4
        }
        return h * 60f
    }

    @Test
    fun tempotonHellBehaeltDenFarbtonDerLegende() {
        // Nur dunkler, nicht verfaerbt: fest um 45 % zu Schwarz wurde die Kurve oliv, braun und
        // dunkelrot und passte nicht mehr zur Legende darunter. 4 Grad Luft fuer die 8-Bit-Kanaele.
        (0..20).forEach { k ->
            val f = Farben.tempo(k / 20f)
            assertTrue(abs(farbton(f.hell) - farbton(f.flaecheHell)) < 4f, "tempo${k * 5}%: Farbton ${farbton(f.hell)} statt ${farbton(f.flaecheHell)}")
        }
        // Wo die Flaeche schon 3:1 haelt, bleibt sie der Ton: Blau und Rot an den Enden.
        assertEquals(Farben.tempo(0f).flaecheHell, Farben.tempo(0f).hell)
        assertEquals(Farben.tempo(1f).flaecheHell, Farben.tempo(1f).hell)
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

    /** Wie weit zwei Farben fuer das Auge auseinanderliegen (Oklab); eben sichtbar ist etwa 0,02. */
    private fun abstand(a: Color, b: Color): Float {
        val x = a.convert(ColorSpaces.Oklab)
        val y = b.convert(ColorSpaces.Oklab)
        val dl = x.red - y.red
        val da = x.green - y.green
        val db = x.blue - y.blue
        return sqrt(dl * dl + da * da + db * db)
    }

    @Test
    fun indoorTraegtDenFarbtonDerUhrUndHebtSichVonDenRaedernAb() {
        val indoor = Farben.sport(Sport.Indoor).flaecheDunkel
        // Die Uhr zeigt Indoor in GColorRajah (FFAA55): derselbe Farbton, damit App und Uhr dieselbe Art zeigen.
        assertTrue(abs(farbton(indoor) - farbton(Color(0xFFFFAA55))) < 2f, "Farbton ${farbton(indoor)} statt ${farbton(Color(0xFFFFAA55))}")
        // Die drei Raeder stehen in Woche und Liste nebeneinander: deutlich verschieden, nicht nur messbar.
        listOf(Sport.Bike, Sport.Mtb).forEach { rad ->
            val d = abstand(indoor, Farben.sport(rad).flaecheDunkel)
            assertTrue(d >= 0.08f, "Indoor neben $rad: ${"%.3f".format(d)} in Oklab")
        }
    }

    @Test
    fun freiwasserUndCustomTragenDenFarbtonDerUhrUndHebenSichVonJederArtAb() {
        // Die Uhr zeigt Freiwasser in GColorVividCerulean (00AAFF) und Custom in GColorFolly (FF0055).
        mapOf(Sport.Freiwasser to Color(0xFF00AAFF), Sport.Offen to Color(0xFFFF0055)).forEach { (art, uhr) ->
            val f = Farben.sport(art).flaecheDunkel
            assertTrue(abs(farbton(f) - farbton(uhr)) < 2f, "$art: Farbton ${farbton(f)} statt ${farbton(uhr)}")
            // Chips, Kalender und Verteilung stellen jede Art neben jede andere, nicht nur die Nachbarn im Stapel.
            Sport.entries.filter { it != art }.forEach { andere ->
                val d = abstand(f, Farben.sport(andere).flaecheDunkel)
                assertTrue(d >= 0.08f, "$art neben $andere: ${"%.3f".format(d)} in Oklab")
            }
        }
        // Folly selbst ist die Zone 5, die im Detail desselben Trainings steht.
        val zone5 = abstand(Farben.sport(Sport.Offen).flaecheDunkel, Farben.trainingszonen[5].flaecheDunkel)
        assertTrue(zone5 >= 0.02f, "Custom wie Zone 5: ${"%.3f".format(zone5)} in Oklab")
    }

    @Test
    fun sportUndZonenflaechenSindDieUhrfarben() {
        // Kreis, Stapel und Kartenlinie zeigen die Farben der Uhr - sie duerfen sich nicht leise verschieben.
        assertEquals(Color(0xFFE8622E), Farben.sport(Sport.Laufen).flaecheDunkel)
        assertEquals(Color(0xFFB8792A), Farben.sport(Sport.Mtb).flaecheDunkel)
        assertEquals(Color(0xFF9C8B7C), Farben.sport(Sport.Anderes).flaecheDunkel)
        assertEquals(Color(0xFFFF0055), Farben.trainingszonen[5].flaecheDunkel)
    }
}
