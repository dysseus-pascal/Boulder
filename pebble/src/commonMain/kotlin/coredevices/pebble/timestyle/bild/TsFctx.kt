package coredevices.pebble.timestyle.bild

import kotlin.math.abs

/*
 * DIE ZIFFERN DER UHRZEIT, gezeichnet wie pebble-fctx 1.6.5 in der Firmware
 * (src/fw/apps/watch/timestyle/pebble-fctx/fctx.c): Umrisse aus einer .ffont,
 * Festkomma in Sechzehnteln, Kantenmarken-Fuellung - ohne Glaettung fuer LECO,
 * mit 8x8 Unterabtastung fuer die anderen Schriften. Kein Skia-Pfad: der
 * wuerde andere Kantenpixel treffen.
 */

/** Eine .ffont (FFont): Kopf, Zeichenbereiche, Zeichentabelle, Pfaddaten. */
internal class TsFfont(private val daten: ByteArray) {
    private fun u16(i: Int) = (daten[i].toInt() and 0xff) or ((daten[i + 1].toInt() and 0xff) shl 8)
    private fun s16(i: Int) = u16(i).toShort().toInt()

    val einheitenJeGeviert = s16(0)
    val oberlaenge = s16(2)
    val unterlaenge = s16(4)
    val versalhoehe = s16(6)
    private val anzahlBereiche = u16(8)
    private val anzahlZeichen = u16(10)
    private val tabelle = 12 + 4 * anzahlBereiche
    private val pfade = tabelle + 6 * anzahlZeichen

    /** ffont_glyph_info: Index in die Zeichentabelle oder -1. */
    private fun zeichen(codepunkt: Int): Int {
        var versatz = 0
        for (r in 0 until anzahlBereiche) {
            val beginn = u16(12 + 4 * r)
            val ende = u16(14 + 4 * r)
            if (codepunkt < beginn) break
            if (codepunkt < ende) return versatz + (codepunkt - beginn)
            versatz += ende - beginn
        }
        return -1
    }

    fun vorschub(codepunkt: Int): Int? = zeichen(codepunkt).takeIf { it >= 0 }?.let { s16(tabelle + 6 * it + 4) }

    /** Die Pfadbefehle eines Zeichens: (Buchstabe, Parameter). */
    fun befehle(codepunkt: Int): List<Pair<Char, IntArray>>? {
        val z = zeichen(codepunkt).takeIf { it >= 0 } ?: return null
        val versatz = u16(tabelle + 6 * z)
        val laenge = u16(tabelle + 6 * z + 2)
        var p = pfade + versatz
        val ende = p + laenge
        val liste = ArrayList<Pair<Char, IntArray>>()
        while (p < ende) {
            val code = u16(p).toChar()
            p += 2
            val n = when (code) {
                'M', 'L', 'T' -> 2
                'H', 'V' -> 1
                'C' -> 6
                'S', 'Q' -> 4
                'Z' -> 0
                else -> error("ungueltiger Pfadbefehl $code")
            }
            liste += code to IntArray(n) { s16(p + 2 * it) }
            p += 2 * n
        }
        return liste
    }
}

/** Ein FContext mit Kantenmarken, fuer genau eine Fuellung (begin_fill .. end_fill). */
internal class TsFctx(private val l: TsLeinwand, private val geglaettet: Boolean) {
    private val breite = BILD_BREITE
    private val hoehe = BILD_HOEHE
    // BW: ein Bit je Pixel (hier ein Byte); AA: 8 Bit je Pixel, je Bit eine Unterzeile.
    private val marken = ByteArray(breite * hoehe)
    private var extMinX = breite * 16
    private var extMinY = hoehe * 16
    private var extMaxX = 0
    private var extMaxY = 0
    private var offsetX = 0
    private var offsetY = 0
    private var skalaVonX = 1
    private var skalaVonY = 1
    private var skalaZuX = 1
    private var skalaZuY = 1
    private val subpixel = if (geglaettet) -1 else -16 / 2
    var fuellFarbe = 0xff
    var farbVerschiebung = 0

    fun setzeVersatz(x: Int, y: Int) {
        offsetX = x
        offsetY = y
    }

    /** fctx_set_text_em_height */
    fun setzeGeviert(schrift: TsFfont, pixel: Int) {
        skalaVonX = schrift.einheitenJeGeviert / 16
        skalaVonY = -skalaVonX
        skalaZuX = pixel
        skalaZuY = pixel
    }

    // --- Kanten (Hecker) ---

    private class Kante(var x: Int, var schritt: Int, var zaehler: Int, var nenner: Int, var fehler: Int, var y: Int, var hoehe: Int)

    /** floorDivMod: (Quotient, Rest) mit Abrunden. */
    private fun bodenTeilung(zaehler: Int, nenner: Int): Pair<Int, Int> {
        return if (zaehler >= 0) {
            (zaehler / nenner) to (zaehler % nenner)
        } else {
            var q = -((-zaehler) / nenner)
            var r = (-zaehler) % nenner
            if (r != 0) {
                q--
                r = nenner - r
            }
            q to r
        }
    }

    private fun kantenAnfang(oberX: Int, oberY: Int, unterX: Int, unterY: Int, f: Int, aufrunden: (Int) -> Int): Kante {
        val y = aufrunden(oberY)
        val yEnde = aufrunden(unterY)
        val k = Kante(0, 0, 0, 0, 0, y, yEnde - y)
        if (k.hoehe != 0) {
            val dN = unterY - oberY
            val dM = unterX - oberX
            val anfang = dM * f * y - dM * oberY + dN * oberX - 1 + dN * f
            val (x, fehler) = bodenTeilung(anfang, dN * f)
            val (schritt, zaehler) = bodenTeilung(dM * f, dN * f)
            k.x = x; k.fehler = fehler; k.schritt = schritt; k.zaehler = zaehler; k.nenner = dN * f
        }
        return k
    }

    private fun Kante.weiter() {
        x += schritt
        y++
        hoehe--
        fehler += zaehler
        if (fehler >= nenner) {
            x++
            fehler -= nenner
        }
    }

    private fun aufrunden16(wert: Int): Int {
        val z = wert - 1 + 16
        return if (z >= 0) z / 16 else -((-z) / 16) - (if ((-z) % 16 != 0) 1 else 0)
    }

    private fun aufrunden2(wert: Int): Int {
        val z = wert - 1 + 2
        return if (z >= 0) z / 2 else -((-z) / 2) - (if ((-z) % 2 != 0) 1 else 0)
    }

    /** fctx_plot_edge_bw / fctx_plot_edge_aa */
    private fun kante(ax: Int, ay: Int, bx: Int, by: Int) {
        if (!geglaettet) {
            val k = if (ay > by) kantenAnfang(bx, by, ax, ay, 16, ::aufrunden16) else kantenAnfang(ax, ay, bx, by, 16, ::aufrunden16)
            while (k.hoehe > 0 && k.y < 0) k.weiter()
            while (k.hoehe > 0 && k.y <= hoehe - 1) {
                if (k.x < 0) {
                    marken[k.y * breite] = (marken[k.y * breite].toInt() xor 1).toByte()
                } else if (k.x <= breite - 1) {
                    marken[k.y * breite + k.x] = (marken[k.y * breite + k.x].toInt() xor 1).toByte()
                }
                k.weiter()
            }
        } else {
            val k = if (ay > by) kantenAnfang(bx, by, ax, ay, 2, ::aufrunden2) else kantenAnfang(ax, ay, bx, by, 2, ::aufrunden2)
            while (k.hoehe > 0 && k.y < 0) k.weiter()
            val maxY = hoehe * 8 - 1
            while (k.hoehe > 0 && k.y <= maxY) {
                val unter = k.y and 7
                val maske = 1 shl unter
                val px = (k.x + ABTAST_VERSATZ[unter]) / 8
                val py = k.y / 8
                val zeile = py * breite
                if (px < 0) {
                    marken[zeile] = (marken[zeile].toInt() xor maske).toByte()
                } else if (px <= breite - 1) {
                    marken[zeile + px] = (marken[zeile + px].toInt() xor maske).toByte()
                }
                k.weiter()
            }
        }
    }

    /** fctx_transform_points (Drehung 0, Drehpunkt 0) fuer einen Punkt. */
    private fun verwandle(px: Int, py: Int, vorschubX: Int, vorschubY: Int): Pair<Int, Int> {
        val x = (px + vorschubX) * skalaZuX / skalaVonX
        val y = (py + vorschubY) * skalaZuY / skalaVonY
        // cos(0) = TRIG_MAX_RATIO, sin(0) = 0: x * c / TRIG_MAX_RATIO ist wieder x
        val tx = x * TRIG_MAX_RATIO / TRIG_MAX_RATIO + offsetX + subpixel
        val ty = y * TRIG_MAX_RATIO / TRIG_MAX_RATIO + offsetY + subpixel
        if (tx < extMinX) extMinX = tx
        if (ty < extMinY) extMinY = ty
        if (tx > extMaxX) extMaxX = tx
        if (ty > extMaxY) extMaxY = ty
        return tx to ty
    }

    private var aktX = 0
    private var aktY = 0

    /** bezier: rekursiv teilen, bis die Winkel unter 5 Grad bleiben. */
    private fun bezier(x1: Int, y1: Int, x2: Int, y2: Int, x3: Int, y3: Int, x4: Int, y4: Int, tiefe: Int) {
        val toleranz = (TRIG_MAX_ANGLE / 360) * 5
        val x12 = (x1 + x2) / 2; val y12 = (y1 + y2) / 2
        val x23 = (x2 + x3) / 2; val y23 = (y2 + y3) / 2
        val x34 = (x3 + x4) / 2; val y34 = (y3 + y4) / 2
        val x123 = (x12 + x23) / 2; val y123 = (y12 + y23) / 2
        val x234 = (x23 + x34) / 2; val y234 = (y23 + y34) / 2
        val x1234 = (x123 + x234) / 2; val y1234 = (y123 + y234) / 2
        val a23 = atan2Tabelle((y3 - y2) / 16, (x3 - x2) / 16)
        var da1 = abs(a23 - atan2Tabelle((y2 - y1) / 16, (x2 - x1) / 16))
        var da2 = abs(atan2Tabelle((y4 - y3) / 16, (x4 - x3) / 16) - a23)
        if (da1 >= TRIG_MAX_ANGLE) da1 = TRIG_MAX_ANGLE - da1
        if (da2 >= TRIG_MAX_ANGLE) da2 = TRIG_MAX_ANGLE - da2
        // In C ohne Tiefengrenze; 40 Stufen erreicht kein Umriss der Schriften
        // (die Punkte fallen vorher auf denselben Festkommawert).
        if (da1 + da2 < toleranz || tiefe > 40) {
            kante(x1, y1, x4, y4)
            return
        }
        bezier(x1, y1, x12, y12, x123, y123, x1234, y1234, tiefe + 1)
        bezier(x1234, y1234, x234, y234, x34, y34, x4, y4, tiefe + 1)
    }

    /** fctx_draw_commands */
    private fun zeichneBefehle(befehle: List<Pair<Char, IntArray>>, vorschubX: Int, vorschubY: Int) {
        var initX = 0; var initY = 0
        var curX = 0; var curY = 0
        var ctrlX = 0; var ctrlY = 0
        for ((code, p) in befehle) {
            when (code) {
                'M' -> {
                    curX = p[0]; curY = p[1]; initX = curX; initY = curY
                    val (tx, ty) = verwandle(curX, curY, vorschubX, vorschubY)
                    aktX = tx; aktY = ty
                    startX = tx; startY = ty
                }
                'Z', 'L', 'H', 'V' -> {
                    val (zx, zy) = when (code) {
                        'Z' -> initX to initY
                        'L' -> p[0] to p[1]
                        'H' -> p[0] to curY
                        else -> curX to p[0]
                    }
                    curX = zx; curY = zy
                    val (tx, ty) = verwandle(zx, zy, vorschubX, vorschubY)
                    kante(aktX, aktY, tx, ty)
                    aktX = tx; aktY = ty
                }
                'C', 'S', 'Q', 'T' -> {
                    val pts = IntArray(6)
                    when (code) {
                        'C' -> {
                            for (i in 0..5) pts[i] = p[i]
                            ctrlX = pts[2]; ctrlY = pts[3]
                        }
                        'S' -> {
                            pts[2] = p[0]; pts[3] = p[1]; pts[4] = p[2]; pts[5] = p[3]
                            pts[0] = curX - ctrlX + curX; pts[1] = curY - ctrlY + curY
                            ctrlX = pts[2]; ctrlY = pts[3]
                        }
                        'Q' -> {
                            ctrlX = p[0]; ctrlY = p[1]
                            pts[4] = p[2]; pts[5] = p[3]
                            pts[0] = (curX + 2 * ctrlX) / 3; pts[1] = (curY + 2 * ctrlY) / 3
                            pts[2] = (pts[4] + 2 * ctrlX) / 3; pts[3] = (pts[5] + 2 * ctrlY) / 3
                        }
                        else -> {
                            ctrlX = curX - ctrlX + curX; ctrlY = curY - ctrlY + curY
                            pts[4] = p[0]; pts[5] = p[1]
                            pts[0] = (curX + 2 * ctrlX) / 3; pts[1] = (curY + 2 * ctrlY) / 3
                            pts[2] = (pts[4] + 2 * ctrlX) / 3; pts[3] = (pts[5] + 2 * ctrlY) / 3
                        }
                    }
                    curX = pts[4]; curY = pts[5]
                    // exec_draw_func: alle drei Punkte verwandeln (auch fuer die Ausdehnung)
                    val (t0x, t0y) = verwandle(pts[0], pts[1], vorschubX, vorschubY)
                    val (t1x, t1y) = verwandle(pts[2], pts[3], vorschubX, vorschubY)
                    val (t2x, t2y) = verwandle(pts[4], pts[5], vorschubX, vorschubY)
                    bezier(aktX, aktY, t0x, t0y, t1x, t1y, t2x, t2y, 0)
                    aktX = t2x; aktY = t2y
                }
                else -> error("ungueltiger Pfadbefehl $code")
            }
        }
    }

    private var startX = 0
    private var startY = 0

    /** fctx_draw_string: Ausrichtung mittig, Anker oben oder Grundlinie. Text in Codepunkten. */
    fun zeichneText(text: String, schrift: TsFfont, ankerOben: Boolean) {
        var breite = 0
        val codepunkte = codepunkte(text)
        for (c in codepunkte) breite += schrift.vorschub(c) ?: 0
        var vorschubX = -breite / 2
        val vorschubY = if (ankerOben) -schrift.oberlaenge else 0
        for (c in codepunkte) {
            val befehle = schrift.befehle(c) ?: continue
            zeichneBefehle(befehle, vorschubX, vorschubY)
            vorschubX += schrift.vorschub(c) ?: 0
        }
    }

    /** fctx_end_fill_bw / fctx_end_fill_aa */
    fun fuelle() {
        if (!geglaettet) {
            var zeileMin = extMinY / 16
            var zeileMax = extMaxY / 16
            var spalteMin = extMinX / 16
            var spalteMax = extMaxX / 16
            if (zeileMin < 0) zeileMin = 0
            if (zeileMax >= hoehe) zeileMax = hoehe - 1
            if (spalteMin < 0) spalteMin = 0
            if (spalteMax >= breite) spalteMax = breite - 1
            for (zeile in zeileMin..zeileMax) {
                var innen = false
                for (spalte in spalteMin..spalteMax) {
                    val i = zeile * breite + spalte
                    if (marken[i].toInt() != 0) innen = !innen
                    marken[i] = 0
                    if (innen) l.pixel[i] = fuellFarbe and 0xff
                }
            }
        } else {
            var zeileMin = extMinY / 16
            var zeileMax = extMaxY / 16
            val spalteMin = extMinX / 16
            val spalteMax = extMaxX / 16
            if (zeileMin < 0) zeileMin = 0
            if (zeileMax >= hoehe) zeileMax = hoehe - 1
            val s = fuellFarbe
            for (zeile in zeileMin..zeileMax) {
                val von = maxOf(0, spalteMin)
                val bis = minOf(breite - 1, spalteMax)
                var maske = 0
                for (spalte in von..bis) {
                    val i = zeile * breite + spalte
                    maske = maske xor (marken[i].toInt() and 0xff)
                    marken[i] = 0
                    val a = (bitAnzahl(maske) + farbVerschiebung).coerceIn(0, 8)
                    if (a != 0) {
                        val d = l.pixel[i]
                        val r = (((s shr 4) and 3) * a + ((d shr 4) and 3) * (8 - a) + 4) / 8
                        val g = (((s shr 2) and 3) * a + ((d shr 2) and 3) * (8 - a) + 4) / 8
                        val b = ((s and 3) * a + (d and 3) * (8 - a) + 4) / 8
                        l.pixel[i] = (d and 0xc0) or (r shl 4) or (g shl 2) or b
                    }
                }
            }
        }
    }

    companion object {
        private val ABTAST_VERSATZ = intArrayOf(2, 7, 4, 1, 6, 3, 0, 5)
    }
}

/** Die Codepunkte eines Textes, wie die UTF-8-Dekodierung der Firmware sie liefert. */
internal fun codepunkte(text: String): IntArray {
    val liste = ArrayList<Int>()
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) {
            liste += ((c.code - 0xD800) shl 10) + (text[i + 1].code - 0xDC00) + 0x10000
            i += 2
        } else {
            liste += c.code
            i++
        }
    }
    return liste.toIntArray()
}

/** countBits (Kernighan) */
private fun bitAnzahl(v: Int): Int {
    var x = v
    var n = 0
    while (x != 0) {
        x = x and (x - 1)
        n++
    }
    return n
}
