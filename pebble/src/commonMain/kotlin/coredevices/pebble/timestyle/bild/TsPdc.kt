package coredevices.pebble.timestyle.bild

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/*
 * TIMESTYLES SYMBOLE AUS DEN ORIGINAL-PDC-DATEIEN (Pebble Draw Command Image),
 * gelesen und gezeichnet wie in der Firmware: gdraw_command_image_draw ->
 * gdraw_command_draw -> gpath_fill_precise_internal / gpath_draw_outline_precise_internal.
 * Die Dateien sind byte-gleich mit resources/normal/base/timestyle/ (alle .pdc) der
 * Firmware (TsZifferblattReferenzTest prueft die SHA-256-Summen).
 */

/** Ein Zeichenbefehl (GDrawCommand). Punkte als GPointPrecise-Rohwerte. */
internal class TsPdcBefehl(
    val typ: Int,
    val versteckt: Boolean,
    val strichBreite: Int,
    val offen: Boolean,
    val radius: Int,
    val punkteX: IntArray,
    val punkteY: IntArray,
)

internal class TsPdcBild(val breite: Int, val hoehe: Int, val befehle: List<TsPdcBefehl>)

internal object TsPdc {
    private const val PFAD = 1
    private const val KREIS = 2
    private const val PFAD_GENAU = 3

    /** Liest eine PDCI-Datei (Signatur, Groesse, dann GDrawCommandImage). */
    fun lies(daten: ByteArray): TsPdcBild {
        fun u8(i: Int) = daten[i].toInt() and 0xff
        fun u16(i: Int) = u8(i) or (u8(i + 1) shl 8)
        fun s16(i: Int) = u16(i).toShort().toInt()
        require(daten.size >= 16 && daten.decodeToString(0, 4) == "PDCI") { "keine PDCI-Datei" }
        val breite = s16(10)
        val hoehe = s16(12)
        val anzahl = u16(14)
        var p = 16
        val befehle = ArrayList<TsPdcBefehl>(anzahl)
        repeat(anzahl) {
            val typ = u8(p)
            val versteckt = (u8(p + 1) and 1) != 0
            val strichBreite = u8(p + 3)
            val offenOderRadius = u16(p + 5)
            val n = u16(p + 7)
            p += 9
            val xs = IntArray(n) { s16(p + 4 * it) }
            val ys = IntArray(n) { s16(p + 4 * it + 2) }
            p += 4 * n
            befehle += TsPdcBefehl(
                typ = typ, versteckt = versteckt, strichBreite = strichBreite,
                offen = (offenOderRadius and 0xff) != 0, radius = offenOderRadius,
                punkteX = xs, punkteY = ys,
            )
        }
        return TsPdcBild(breite, hoehe, befehle)
    }

    /**
     * gdraw_command_image_draw nach TimeStyles gdraw_command_image_recolor:
     * JEDER Befehl bekommt [fuellung] und [strich] - auch offene Linien eine
     * Fuellung, wie auf der Uhr.
     */
    fun zeichne(l: TsLeinwand, bild: TsPdcBild, x: Int, y: Int, fuellung: Int, strich: Int) {
        // graphics_context_move_draw_box
        l.ursprungX += x
        l.ursprungY += y
        for (b in bild.befehle) {
            if (b.versteckt) continue
            when (b.typ) {
                PFAD_GENAU -> zeichneGenauenPfad(l, b, fuellung, strich)
                PFAD -> zeichnePfad(l, b, fuellung, strich)
                KREIS -> zeichneKreis(l, b, fuellung, strich)
                else -> error("unbekannter Zeichenbefehl ${b.typ}")
            }
        }
        l.ursprungX -= x
        l.ursprungY -= y
    }

    /** prv_draw_precise_path */
    private fun zeichneGenauenPfad(l: TsLeinwand, b: TsPdcBefehl, fuellung: Int, strich: Int) {
        val n = b.punkteX.size
        if (n <= 1) return
        if (alpha(fuellung) != 0) {
            l.fuellFarbe = fuellung
            // gpath_fill_precise_internal: die Fuellung rechnet mit ganzen Pixeln
            fuellePfad(l, IntArray(n) { b.punkteX[it] shr 3 }, IntArray(n) { b.punkteY[it] shr 3 })
        }
        if (alpha(strich) != 0 && b.strichBreite > 0) {
            l.strichFarbe = strich
            l.strichBreite = b.strichBreite
            val bis = if (b.offen) n - 1 else n
            for (i in 0 until bis) {
                val j = (i + 1) % n
                l.zeichneLinieGenau(genau(b.punkteX[i], b.punkteY[i]), genau(b.punkteX[j], b.punkteY[j]))
            }
        }
    }

    /** prv_draw_path (ganzzahlige Punkte) - in TimeStyles Bildern nicht benutzt, der Vollstaendigkeit halber. */
    private fun zeichnePfad(l: TsLeinwand, b: TsPdcBefehl, fuellung: Int, strich: Int) {
        val n = b.punkteX.size
        if (n <= 1) return
        if (alpha(fuellung) != 0) {
            l.fuellFarbe = fuellung
            fuellePfad(l, b.punkteX, b.punkteY)
        }
        if (alpha(strich) != 0 && b.strichBreite > 0) {
            l.strichFarbe = strich
            l.strichBreite = b.strichBreite
            val bis = if (b.offen) n - 1 else n
            for (i in 0 until bis) {
                val j = (i + 1) % n
                l.zeichneLinie(b.punkteX[i], b.punkteY[i], b.punkteX[j], b.punkteY[j])
            }
        }
    }

    /** prv_draw_circle - in TimeStyles Bildern nicht benutzt. */
    private fun zeichneKreis(l: TsLeinwand, b: TsPdcBefehl, fuellung: Int, strich: Int) {
        if (alpha(fuellung) != 0 && b.radius > 0) {
            l.fuellFarbe = fuellung
            l.fuelleKreis(b.punkteX[0], b.punkteY[0], b.radius)
        }
        // graphics_draw_circle ist nicht nachgebaut: kein TimeStyle-Bild hat einen Kreis.
        require(alpha(strich) == 0 || b.strichBreite == 0) { "Kreisumriss ist nicht nachgebaut" }
    }

    private class Schnitt(var x: Int, var delta: Int)

    /** sortIntersections: genau diese Austauschsortierung - bei gleichem x entscheidet die Reihenfolge. */
    private fun sortiere(werte: Array<Schnitt>, anzahl: Int) {
        for (i in 0 until anzahl) {
            for (j in i + 1 until anzahl) {
                if (werte[i].x > werte[j].x) {
                    val t = werte[i]; werte[i] = werte[j]; werte[j] = t
                }
            }
        }
    }

    /** prv_fill_path_with_cb_aa mit prv_gpath_draw_filled_cb (ganzzahlige Punkte, Drehung 0). */
    fun fuellePfad(l: TsLeinwand, px: IntArray, py: IntArray) {
        val n = px.size
        if (n < 2) return
        // GPointPreciseFromGPoint
        val rx = IntArray(n) { i16((px[it] % 0x2000) * FIX_EINS) }
        val ry = IntArray(n) { i16((py[it] % 0x2000) * FIX_EINS) }
        var minX = fixGanz(rx[0]); var maxX = minX
        var minY = fixGanz(ry[0]); var maxY = minY
        var richtungGefunden = false
        var startAbwaerts = false
        var endeY = ry[0]
        for (i in n - 1 downTo 1) {
            minX = min(minX, fixGanz(rx[i])); maxX = max(maxX, fixGanz(rx[i]))
            minY = min(minY, fixGanz(ry[i])); maxY = max(maxY, fixGanz(ry[i]))
            if (richtungGefunden) continue
            if (fixGanz(endeY) != fixGanz(ry[i])) {
                startAbwaerts = fixGanz(endeY) > fixGanz(ry[i])
                richtungGefunden = true
            }
            endeY = ry[i]
        }
        val clipMinX = l.clip.x - l.ursprungX
        val clipMaxX = l.clip.w + clipMinX
        if (!(maxX >= clipMinX && minX <= clipMaxX)) return

        val hoch = Array(n) { Schnitt(0, 0) }
        val runter = Array(n) { Schnitt(0, 0) }
        val clipMinY = l.clip.y - l.ursprungY
        val clipMaxY = l.clip.h + clipMinY
        minY = max(minY, clipMinY)
        maxY = min(maxY, clipMaxY)

        val vorher = l.strichFarbe
        l.strichFarbe = l.fuellFarbe
        var i = minY
        while (i <= maxY) {
            var nHoch = 0
            var nRunter = 0
            var zuletztAbwaerts = startAbwaerts
            for (j in 0 until n) {
                val sx = rx[j]; val sy = ry[j]
                val k = if (j + 1 < n) j + 1 else 0
                val ex = rx[k]; val ey = ry[k]
                if ((fixGanz(sy) - i) * (fixGanz(ey) - i) <= 0) {
                    val abwaerts = if (fixGanz(ey) != fixGanz(sy)) fixGanz(ey) > fixGanz(sy) else zuletztAbwaerts
                    if (!(fixGanz(sy) == i && zuletztAbwaerts == abwaerts)) {
                        val dx = i16(ex - sx)
                        val dy = i16(ey - sy)
                        val x = i16(sx + dx * (i * FIX_EINS - sy) / dy)
                        var delta = i16(abs(dx / dy) * FIX_EINS)
                        if (fixGanz(delta) > 1) {
                            val kMinX = if (ex < sx) ex else sx
                            val kMaxX = if (ex > sx) ex else sx
                            if (x - (delta / 2) < kMinX) delta = i16((x - kMinX) * 2)
                            if (x + (delta / 2) > kMaxX) delta = i16((kMaxX - x) * 2)
                        }
                        if (abwaerts) runter[nRunter++].let { it.x = x; it.delta = delta }
                        else hoch[nHoch++].let { it.x = x; it.delta = delta }
                    }
                    zuletztAbwaerts = abwaerts
                }
            }
            sortiere(hoch, nHoch)
            sortiere(runter, nRunter)
            for (j in 0 until min(nHoch, nRunter)) {
                var a = Schnitt(hoch[j].x, hoch[j].delta)
                var b = Schnitt(runter[j].x, runter[j].delta)
                if (fixGanz(a.x) != fixGanz(b.x)) {
                    if (fixGanz(a.x) > fixGanz(b.x)) {
                        val t = a; a = b; b = t
                    }
                    gefuellteZeile(l, i, a.x, b.x, a.delta, b.delta)
                }
            }
            i++
        }
        l.strichFarbe = vorher
    }

    /** prv_gpath_draw_filled_cb (Farbuhr, geglaettet) */
    private fun gefuellteZeile(l: TsLeinwand, y: Int, beginnIn: Int, endeIn: Int, deltaBeginn: Int, deltaEnde: Int) {
        if (deltaBeginn >= 0 || deltaEnde >= 0) {
            val beginn = fixMitGanz(beginnIn, fixGanz(beginnIn) + 1)
            val ende = fixMitGanz(endeIn, fixGanz(endeIn) - 1)
            l.zeichneWaagrechtVerlauf(y, beginn, ende, deltaBeginn, deltaEnde)
            return
        }
        // Ohne Verlauf: graphics_fill_rect einer Zeile (kommt in diesem Weg nicht vor).
        l.fuelleRechteck(TsRechteck(fixGanz(beginnIn) + 1, y, fixGanz(endeIn) - fixGanz(beginnIn) - 1, 1), 0, TsLeinwand.ECKEN_KEINE)
    }
}
