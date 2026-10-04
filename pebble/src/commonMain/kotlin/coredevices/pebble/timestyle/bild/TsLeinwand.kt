package coredevices.pebble.timestyle.bild

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/*
 * DIE ZEICHENFLAECHE DER UHR, NACHGEBAUT FUER DIE VORSCHAU VON TIMESTYLE BEDROCK.
 *
 * Warum ein Nachbau statt Compose-Zeichnen: die Vorschau soll Pixel fuer Pixel
 * zeigen, was die Uhr zeigt. Skia glaettet und rundet anders als die Firmware;
 * schon eine halbe Graustufe an einer Symbolkante waere eine Abweichung. Darum
 * rechnet dieser Teil die Zeichenroutinen der Firmware (src/fw/applib/graphics/)
 * mit denselben Ganzzahlen nach - jede Funktion nennt ihr C-Vorbild. Auch
 * Eigenheiten werden mit uebernommen (z.B. dass eine Linie in den Clip-Rand
 * ragt), denn die Uhr zeigt sie ja.
 *
 * Farben sind GColor8-Bytes (aarrggbb) als Int 0..255, der Bildpuffer ist
 * 8 Bit je Pixel, Zeile fuer Zeile ohne Luecke - wie der Framebuffer von emery.
 * Festkommazahlen (Fixed_S16_3) sind Int-Rohwerte in Achteln eines Pixels.
 */

internal const val BILD_BREITE = 200
internal const val BILD_HOEHE = 228

/** Fixed_S16_3: ein Pixel = 8. */
internal const val FIX_EINS = 8

/** .integer eines Fixed_S16_3 (vorzeichenrichtig, wie das Bitfeld in C). */
internal fun fixGanz(roh: Int): Int = roh.toShort().toInt() shr 3

/** .fraction eines Fixed_S16_3. */
internal fun fixBruch(roh: Int): Int = roh and 7

/** Ein Fixed_S16_3 mit neuem .integer, .fraction bleibt (Zuweisung an das Bitfeld). */
internal fun fixMitGanz(roh: Int, ganz: Int): Int = (((ganz shl 3) or (roh and 7)).toShort()).toInt()

/** Ein Fixed_S16_3 mit neuem .fraction. */
internal fun fixMitBruch(roh: Int, bruch: Int): Int = (((roh and 7.inv()) or (bruch and 7)).toShort()).toInt()

/** Auf int16 kuerzen, wie jede Zuweisung an ein raw_value in C. */
internal fun i16(wert: Int): Int = wert.toShort().toInt()

/** Ein Rechteck in Bildschirmkoordinaten (GRect). */
internal data class TsRechteck(val x: Int, val y: Int, val w: Int, val h: Int) {
    val maxX: Int get() = x + w
    val maxY: Int get() = y + h
    fun enthaelt(px: Int, py: Int): Boolean = px >= x && px < x + w && py >= y && py < y + h
}

/** grect_clip: das erste Rechteck auf das zweite beschneiden (Breite/Hoehe nie negativ). */
internal fun TsRechteck.beschnitten(auf: TsRechteck): TsRechteck {
    val links = max(x, auf.x)
    val oben = max(y, auf.y)
    val rechts = min(x + w, auf.x + auf.w)
    val unten = min(y + h, auf.y + auf.h)
    return TsRechteck(links, oben, max(0, rechts - links), max(0, unten - oben))
}

// --- Farben (gtypes.c) ---------------------------------------------------------------------

internal fun alpha(farbe: Int): Int = (farbe shr 6) and 3

internal fun mitAlpha(farbe: Int, a: Int): Int = (farbe and 0x3f) or ((a and 3) shl 6)

/**
 * gcolor_blend. Faktor 1 und 2 sind in der Firmware eine Tabelle; sie ist genau
 * round((Quelle + 2*Ziel)/3) je 2-Bit-Kanal (fuer alle 4096 Eintraege geprueft,
 * TsSprachenUndMischungTest vergleicht alle mit der Tabelle der Firmware), deckend.
 */
internal fun mischeFarbe(quelle: Int, ziel: Int, faktor: Int): Int = when (faktor) {
    0 -> ziel
    1 -> drittelMischung(stark = ziel, schwach = quelle)
    2 -> drittelMischung(stark = quelle, schwach = ziel)
    3 -> quelle
    else -> error("Mischfaktor $faktor")
}

private fun drittelMischung(stark: Int, schwach: Int): Int {
    var ergebnis = 0xc0
    for (verschiebung in intArrayOf(4, 2, 0)) {
        val s = (schwach shr verschiebung) and 3
        val t = (stark shr verschiebung) and 3
        ergebnis = ergebnis or (((s + 2 * t + 1) / 3) shl verschiebung)
    }
    return ergebnis
}

/** gcolor_alpha_blend */
internal fun alphaMischung(quelle: Int, ziel: Int): Int = mischeFarbe(quelle, ziel, alpha(quelle))

// --- Die Zeichenflaeche --------------------------------------------------------------------

internal class TsLeinwand {
    /** Der Framebuffer, GColor8 je Pixel. */
    val pixel = IntArray(BILD_BREITE * BILD_HOEHE)

    /** draw_state.clip_box, in Bildschirmkoordinaten. */
    var clip = TsRechteck(0, 0, BILD_BREITE, BILD_HOEHE)

    /** draw_state.drawing_box.origin - der Ursprung der Ebene, die gerade zeichnet. */
    var ursprungX = 0
    var ursprungY = 0

    var fuellFarbe = 0xff
    var strichFarbe = 0xc0
    var textFarbe = 0xc0
    var strichBreite = 1

    /** Apps mit SDK 3 zeichnen auf Farbuhren immer geglaettet. */
    val geglaettet = true

    /** Zeichnet ab jetzt in einer Ebene mit diesem Rahmen (Ursprung und Clip). */
    fun ebene(rahmen: TsRechteck) {
        ursprungX = rahmen.x
        ursprungY = rahmen.y
        clip = rahmen.beschnitten(TsRechteck(0, 0, BILD_BREITE, BILD_HOEHE))
    }

    private fun lies(index: Int): Int = pixel[index]

    private fun schreib(index: Int, farbe: Int) {
        // Ein Index jenseits des Puffers waere in C ein Speicherfehler; die
        // Zeichenroutinen beschneiden vorher, hier bleibt nur der Schutz.
        if (index in pixel.indices) pixel[index] = farbe and 0xff
    }

    // --- graphics_private_raw.c ------------------------------------------------------------

    /** graphics_private_raw_blend_color_factor: Faktor 0..8 (Achtel Deckung). */
    private fun mischeFaktor(index: Int, farbe: Int, faktor: Int) {
        val quelle = mitAlpha(farbe, faktor * 3 / (FIX_EINS - 1))
        schreib(index, alphaMischung(quelle, lies(index)))
    }

    /** prv_assign_horizontal_line_raw */
    fun setzeWaagrechtRoh(y: Int, x1In: Int, x2In: Int, farbe: Int) {
        var x1 = max(x1In, 0 shl 3)
        val x2 = min(x2In, (BILD_BREITE - 1) shl 3)
        if (fixGanz(x1) > fixGanz(x2)) return
        var ausgabe = y * BILD_BREITE + fixGanz(x1)
        if (fixBruch(x1) != 0) {
            mischeFaktor(ausgabe, farbe, FIX_EINS - fixBruch(x1))
            ausgabe++
            x1 = fixMitGanz(x1, fixGanz(x1) + 1)
        }
        val breite = fixGanz(x2) - fixGanz(x1) + 1
        if (breite > 0) {
            for (i in 0 until breite) schreib(ausgabe + i, farbe)
            ausgabe += breite
        }
        if (fixBruch(x2) != 0) mischeFaktor(ausgabe, farbe, fixBruch(x2))
    }

    /** prv_assign_vertical_line_raw */
    fun setzeSenkrechtRoh(x: Int, y1In: Int, y2: Int, farbe: Int) {
        var y1 = y1In
        if (fixBruch(y1) != 0) {
            if (x in 0 until BILD_BREITE) {
                mischeFaktor(fixGanz(y1) * BILD_BREITE + x, farbe, FIX_EINS - fixBruch(y1))
            }
            y1 = fixMitGanz(y1, fixGanz(y1) + 1)
        }
        while (fixGanz(y1) <= fixGanz(y2)) {
            if (x in 0 until BILD_BREITE) schreib(fixGanz(y1) * BILD_BREITE + x, farbe)
            y1 = fixMitGanz(y1, fixGanz(y1) + 1)
        }
        if (fixBruch(y2) != 0) {
            if (x in 0 until BILD_BREITE) {
                mischeFaktor(fixGanz(y1) * BILD_BREITE + x, farbe, fixBruch(y2))
            }
        }
    }

    /** prv_blend_horizontal_line_raw: x1..x2 einschliesslich. */
    fun mischeWaagrechtRoh(y: Int, x1In: Int, x2In: Int, farbe: Int) {
        val x1 = max(x1In, 0)
        val x2 = min(x2In, BILD_BREITE - 1)
        for (i in x1..x2) {
            val index = y * BILD_BREITE + i
            schreib(index, alphaMischung(farbe, lies(index)))
        }
    }

    /** prv_blend_vertical_line_raw: y1 bis VOR y2. */
    fun mischeSenkrechtRoh(x: Int, y1: Int, y2: Int, farbe: Int) {
        for (i in y1 until y2) {
            if (x !in 0 until BILD_BREITE) continue
            val index = i * BILD_BREITE + x
            schreib(index, alphaMischung(farbe, lies(index)))
        }
    }

    /** prv_assign_horizontal_line_delta_raw: Linie mit Verlaeufen an beiden Enden. */
    fun setzeWaagrechtVerlaufRoh(
        y: Int, x1In: Int, x2In: Int, linksIn: Int, rechts: Int, clipMinXIn: Int, clipMaxXIn: Int, farbe: Int,
    ) {
        val clipMinX = max(clipMinXIn, 0)
        val clipMaxX = min(clipMaxXIn, BILD_BREITE - 1)
        var x1 = x1In
        var x2 = x2In
        var links = linksIn
        val ausserhalb = clipMinX - fixGanz(x1)
        if (ausserhalb > links) {
            links = 0
            x1 = fixMitGanz(x1, fixGanz(x1) + ausserhalb)
        }
        x2 = fixMitGanz(x2, min(clipMaxX, fixGanz(x2)))
        if (fixGanz(x1) > fixGanz(x2)) return

        var ausgabe = y * BILD_BREITE + fixGanz(x1)
        if (links == 1) {
            if (fixGanz(x1) >= clipMinX) mischeFaktor(ausgabe, farbe, FIX_EINS - fixBruch(x1))
            ausgabe++
            x1 = fixMitGanz(x1, fixGanz(x1) + 1)
        } else {
            for (i in 0 until links) {
                if (fixGanz(x1) < clipMinX) {
                    ausgabe++
                    x1 = fixMitGanz(x1, fixGanz(x1) + 1)
                    continue
                }
                if (fixGanz(x1) > clipMaxX) break
                mischeFaktor(ausgabe, farbe, FIX_EINS * i / links)
                ausgabe++
                x1 = fixMitGanz(x1, fixGanz(x1) + 1)
            }
        }
        val breite = fixGanz(x2) - fixGanz(x1) + 1
        if (breite > 0) {
            for (i in 0 until breite) schreib(ausgabe + i, farbe)
            ausgabe += breite
            x1 = fixMitGanz(x1, fixGanz(x1) + breite)
        }
        if (rechts <= 1) {
            if (fixGanz(x1) <= clipMaxX) mischeFaktor(ausgabe, farbe, fixBruch(x2))
        } else {
            for (i in 0 until rechts) {
                if (fixGanz(x1) > clipMaxX) break
                mischeFaktor(ausgabe, farbe, FIX_EINS * (rechts - i) / rechts)
                ausgabe++
                x1 = fixMitGanz(x1, fixGanz(x1) + 1)
            }
        }
    }

    // --- graphics_private.c ----------------------------------------------------------------

    /** graphics_private_draw_horizontal_line_prepared */
    fun waagrechtVorbereitet(clipBox: TsRechteck, y: Int, x1In: Int, x2In: Int, farbe: Int) {
        if (alpha(farbe) == 0) return
        if (y < clipBox.y || y > clipBox.maxY - 1) return
        var x1 = x1In
        var x2 = x2In
        val minX = clipBox.x
        if (fixGanz(x1) < minX) x1 = minX shl 3
        val maxX = clipBox.maxX - 1
        if (fixGanz(x2) > maxX) x2 = maxX shl 3
        if (fixGanz(x2) >= maxX) x2 = fixMitBruch(x2, 0)
        setzeWaagrechtRoh(y, x1, x2, farbe)
    }

    /** graphics_private_draw_vertical_line_prepared */
    fun senkrechtVorbereitet(clipBox: TsRechteck, x: Int, y1In: Int, y2In: Int, farbe: Int) {
        if (alpha(farbe) == 0) return
        if (x < clipBox.x || x > clipBox.maxX - 1) return
        var y1 = y1In
        var y2 = y2In
        val minY = clipBox.y
        if (fixGanz(y1) < minY) y1 = minY shl 3
        val maxY = clipBox.maxY - 1
        if (fixGanz(y2) > maxY) y2 = maxY shl 3
        if (fixGanz(y1) > fixGanz(y2)) return
        if (fixGanz(y2) >= maxY) y2 = fixMitBruch(y2, 0)
        setzeSenkrechtRoh(x, y1, y2, farbe)
    }

    /** graphics_private_draw_horizontal_line (geglaettet) */
    fun zeichneWaagrecht(y: Int, x1: Int, x2: Int) {
        waagrechtVorbereitet(
            clip, y + ursprungY, fixMitGanz(x1, fixGanz(x1) + ursprungX), fixMitGanz(x2, fixGanz(x2) + ursprungX),
            strichFarbe,
        )
    }

    /** graphics_private_draw_vertical_line (geglaettet) */
    fun zeichneSenkrecht(x: Int, y1: Int, y2: Int) {
        senkrechtVorbereitet(
            clip, x + ursprungX, fixMitGanz(y1, fixGanz(y1) + ursprungY), fixMitGanz(y2, fixGanz(y2) + ursprungY),
            strichFarbe,
        )
    }

    /** graphics_private_plot_pixel: Deckung 0 = voll, 3 = nichts. Koordinaten schon absolut. */
    fun setzePunkt(clipBox: TsRechteck, x: Int, y: Int, deckungsLuecke: Int, farbe: Int) {
        if (!clipBox.enthaelt(x, y)) return
        if (x !in 0 until BILD_BREITE) return
        val index = y * BILD_BREITE + x
        schreib(index, alphaMischung(mitAlpha(farbe, 3 - deckungsLuecke), lies(index)))
    }

    /** graphics_private_plot_horizontal_line (geglaettet) */
    fun tupfeWaagrecht(yIn: Int, x1In: Int, x2In: Int, luecke: Int) {
        val y = yIn + ursprungY
        var x1 = fixMitGanz(x1In, fixGanz(x1In) + ursprungX)
        var x2 = fixMitGanz(x2In, fixGanz(x2In) + ursprungX)
        x1 = i16(x1 + FIX_EINS / 2)
        x2 = i16(x2 + FIX_EINS / 2)
        if (fixBruch(x2) > (luecke shl 1)) x2 = fixMitGanz(x2, fixGanz(x2) + 1)
        // graphics_private_plot_horizontal_line_prepared
        if (y < clip.y || y > clip.maxY - 1) return
        val xMin = max(min(fixGanz(x1), fixGanz(x2)), clip.x)
        val xMax = min(max(fixGanz(x1), fixGanz(x2)), clip.maxX)
        mischeWaagrechtRoh(y, xMin, xMax, mitAlpha(strichFarbe, 3 - luecke))
    }

    /** graphics_private_plot_vertical_line (geglaettet) */
    fun tupfeSenkrecht(xIn: Int, y1In: Int, y2In: Int, luecke: Int) {
        val x = xIn + ursprungX
        var y1 = fixMitGanz(y1In, fixGanz(y1In) + ursprungY)
        var y2 = fixMitGanz(y2In, fixGanz(y2In) + ursprungY)
        y1 = i16(y1 + FIX_EINS / 2)
        y2 = i16(y2 + FIX_EINS / 2)
        if (fixBruch(y2) > (luecke shl 1)) y2 = fixMitGanz(y2, fixGanz(y2) + 1)
        // graphics_private_plot_vertical_line_prepared
        if (x < clip.x || x > clip.maxX - 1) return
        val yMin = max(min(fixGanz(y1), fixGanz(y2)), clip.y)
        val yMax = min(max(fixGanz(y1), fixGanz(y2)), clip.y + clip.h)
        mischeSenkrechtRoh(x, yMin, yMax, mitAlpha(strichFarbe, 3 - luecke))
    }

    /** graphics_private_draw_horizontal_line_delta_aa (mit _prepared) */
    fun zeichneWaagrechtVerlauf(yIn: Int, x1In: Int, x2In: Int, delta1: Int, delta2: Int) {
        var x1 = fixMitGanz(x1In, fixGanz(x1In) + ursprungX)
        var x2 = fixMitGanz(x2In, fixGanz(x2In) + ursprungX)
        val y = yIn + ursprungY
        val links = (if (fixGanz(delta1) > 1) (delta1 + FIX_EINS / 2) / FIX_EINS else 1) and 0xff
        val rechts = (if (fixGanz(delta2) > 1) (delta2 + FIX_EINS / 2) / FIX_EINS else 1) and 0xff
        x1 = fixMitGanz(x1, fixGanz(x1) - links / 2)
        x2 = fixMitGanz(x2, fixGanz(x2) - rechts / 2)
        if (y < clip.y || y > clip.maxY - 1) return
        setzeWaagrechtVerlaufRoh(y, x1, x2, links, rechts, clip.x, clip.maxX - 1, strichFarbe)
    }

    // --- graphics.c: Rechtecke -------------------------------------------------------------

    /** prv_fill_rect_legacy2 mit Radius 0 (der Weg aller ungerundeten Rechtecke). */
    fun fuelleRechteckEckig(rahmen: TsRechteck, farbeIn: Int) {
        val farbe = if (alpha(farbeIn) == 0) 0xff else farbeIn
        val absolut = TsRechteck(rahmen.x + ursprungX, rahmen.y + ursprungY, rahmen.w, rahmen.h)
        // grect_standardize: negative Breite/Hoehe umdrehen
        var r = absolut
        if (r.w < 0) r = r.copy(x = r.x + r.w, w = -r.w)
        if (r.h < 0) r = r.copy(y = r.y + r.h, h = -r.h)
        r = r.beschnitten(TsRechteck(0, 0, BILD_BREITE, BILD_HOEHE)).beschnitten(clip)
        if (r.w <= 0 || r.h <= 0) return
        for (y in r.y until r.maxY) {
            // graphics_private_draw_horizontal_line_integral: Ende ausschliesslich
            setzeWaagrechtRoh(y, r.x shl 3, (r.maxX - 1) shl 3, farbe)
        }
    }

    /** graphics_fill_round_rect (geglaettet). */
    fun fuelleRechteck(rahmen: TsRechteck, radiusIn: Int, ecken: Int) {
        // prv_clamp_corner_radius
        val radius = when {
            ecken == ECKEN_KEINE -> 0
            min(rahmen.w, rahmen.h) >= 2 * radiusIn -> radiusIn
            else -> min(rahmen.w, rahmen.h) / 2
        }
        if (radius <= 0) {
            fuelleRechteckEckig(rahmen, fuellFarbe)
            return
        }
        // prv_fill_rect_internal: vier Viertelkreise und drei Rechtecke
        var obenX = rahmen.x
        var obenW = rahmen.w
        var untenX = rahmen.x
        var untenW = rahmen.w
        if (ecken and ECKE_OL != 0) {
            fuelleViertelkreis(rahmen.x + radius, rahmen.y + radius, radius, ECKE_OL)
            obenX += radius
            obenW -= radius
        }
        if (ecken and ECKE_UL != 0) {
            fuelleViertelkreis(rahmen.x + radius, rahmen.y + rahmen.h - radius - 1, radius, ECKE_UL)
            untenX += radius
            untenW -= radius
        }
        if (ecken and ECKE_OR != 0) {
            fuelleViertelkreis(rahmen.x + rahmen.w - radius - 1, rahmen.y + radius, radius, ECKE_OR)
            obenW -= radius
        }
        if (ecken and ECKE_UR != 0) {
            fuelleViertelkreis(rahmen.x + rahmen.w - radius - 1, rahmen.y + rahmen.h - radius - 1, radius, ECKE_UR)
            untenW -= radius
        }
        fuelleRechteckEckig(TsRechteck(obenX, rahmen.y, obenW, radius), fuellFarbe)
        fuelleRechteckEckig(TsRechteck(rahmen.x, rahmen.y + radius, rahmen.w, rahmen.h - 2 * radius), fuellFarbe)
        fuelleRechteckEckig(TsRechteck(untenX, rahmen.y + rahmen.h - radius, untenW, radius), fuellFarbe)
    }

    /** graphics_internal_circle_quadrant_fill_aa -> prv_fill_oval_quadrant */
    fun fuelleViertelkreis(px: Int, py: Int, radius: Int, viertel: Int) {
        TsOval.fuelleViertel(this, px, py, radius, viertel)
    }

    /** graphics_fill_circle (geglaettet) */
    fun fuelleKreis(px: Int, py: Int, radius: Int) {
        if (radius == 0) {
            // graphics_draw_pixel mit der Fuellfarbe
            val x = px + ursprungX
            val y = py + ursprungY
            if (!clip.enthaelt(x, y)) return
            if (y !in 0 until BILD_HOEHE || x !in 0 until BILD_BREITE) return
            if (alpha(fuellFarbe) != 0) schreib(y * BILD_BREITE + x, mitAlpha(fuellFarbe, 3))
            return
        }
        TsOval.fuelleViertel(this, px, py, radius, ECKEN_ALLE)
    }

    // --- graphics_line.c -------------------------------------------------------------------

    /** graphics_draw_line (geglaettet) */
    fun zeichneLinie(p0x: Int, p0y: Int, p1x: Int, p1y: Int) {
        if (strichBreite > 1) {
            // graphics_line_draw_stroked_aa: gerade Breiten werden ungerade
            val breite = if (strichBreite % 2 == 0) strichBreite + 1 else strichBreite
            TsLinie.dick(this, punktGenau(p0x, p0y), punktGenau(p1x, p1y), breite)
        } else {
            TsLinie.duenn(this, p0x, p0y, p1x, p1y)
        }
    }

    /** graphics_line_draw_precise_stroked */
    fun zeichneLinieGenau(p0: Long, p1: Long) {
        if (strichBreite >= 2) {
            TsLinie.dick(this, p0, p1, strichBreite)
        } else {
            zeichneLinie(genauX(p0) shr 3, genauY(p0) shr 3, genauX(p1) shr 3, genauY(p1) shr 3)
        }
    }

    companion object {
        const val ECKEN_KEINE = 0
        const val ECKE_OL = 1
        const val ECKE_OR = 2
        const val ECKE_UL = 4
        const val ECKE_UR = 8
        const val ECKEN_ALLE = 15
        const val ECKEN_OBEN = ECKE_OL or ECKE_OR
        const val ECKEN_UNTEN = ECKE_UL or ECKE_UR
    }
}

// --- GPointPrecise als ein Long (x und y je ein Fixed_S16_3-Rohwert) -----------------------

internal fun genau(x: Int, y: Int): Long = (i16(x).toLong() shl 32) or (i16(y).toLong() and 0xffffffffL)

internal fun genauX(p: Long): Int = (p shr 32).toInt()

internal fun genauY(p: Long): Int = p.toInt()

/** GPointPreciseFromGPoint: ganze Pixel (mit dem Modulo 0x2000 der Firmware). */
internal fun punktGenau(x: Int, y: Int): Long = genau((x % 0x2000) * FIX_EINS, (y % 0x2000) * FIX_EINS)

internal fun abstand(a: Int): Int = abs(a)
