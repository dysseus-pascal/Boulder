package coredevices.pebble.timestyle.bild

import kotlin.math.max
import kotlin.math.min

/*
 * Linien wie die Firmware (src/fw/applib/graphics/graphics_line.c): die duenne
 * geglaettete Linie nach Wu und die dicke Linie mit runden Kappen. Ganzzahlen
 * und Rundungen sind die der C-Fassung; wo C in int16 oder uint16 speichert,
 * wird hier ebenso gekuerzt.
 */
internal object TsLinie {

    /** graphics_line_draw_1px_aa */
    fun duenn(l: TsLeinwand, p0x: Int, p0y: Int, p1x: Int, p1y: Int) {
        var x1 = i16(p0x + l.ursprungX)
        var y1 = i16(p0y + l.ursprungY)
        var x2 = i16(p1x + l.ursprungX)
        var y2 = i16(p1y + l.ursprungY)
        val farbe = l.strichFarbe

        if (y1 > y2) {
            var t = y1; y1 = y2; y2 = t
            t = x1; x1 = x2; x2 = t
        }
        l.setzePunkt(l.clip, x1, y1, 0, farbe)

        var dx = x2 - x1
        val xi: Int
        if (dx >= 0) {
            xi = 1
        } else {
            xi = -1
            dx = -dx
        }
        var dy = y2 - y1
        if (dy == 0) {
            var start = x1
            var ende = x1 + dx * xi
            if (ende < start) {
                val t = start; start = ende; ende = t
            }
            l.waagrechtVorbereitet(l.clip, y1, start shl 3, ende shl 3, farbe)
        } else if (dx == 0) {
            l.senkrechtVorbereitet(l.clip, x1, y1 shl 3, (y1 + dy) shl 3, farbe)
        } else if (dx == dy) {
            while (dy-- != 0) {
                x1 += xi
                y1++
                l.setzePunkt(l.clip, x1, y1, 0, farbe)
            }
        } else {
            // uint16_t in C: der Ueberlauf von error_acc ist das Signal zum Weiterruecken.
            var fehler = 0
            if (dy > dx) {
                val schritt = ((dx.toLong() shl 16) / dy).toInt() and 0xffff
                while (--dy != 0) {
                    val vorher = fehler
                    fehler = (fehler + schritt) and 0xffff
                    if (fehler <= vorher) x1 += xi
                    y1++
                    val gewicht = fehler shr 14
                    l.setzePunkt(l.clip, x1, y1, gewicht, farbe)
                    l.setzePunkt(l.clip, x1 + xi, y1, gewicht xor 3, farbe)
                }
                l.setzePunkt(l.clip, x2, y2, 0, farbe)
            } else {
                val schritt = ((dy.toLong() shl 16) / dx).toInt() and 0xffff
                while (--dx != 0) {
                    val vorher = fehler
                    fehler = (fehler + schritt) and 0xffff
                    if (fehler <= vorher) y1++
                    x1 += xi
                    val gewicht = fehler shr 14
                    l.setzePunkt(l.clip, x1, y1, gewicht, farbe)
                    l.setzePunkt(l.clip, x1, y1 + 1, gewicht xor 3, farbe)
                }
                l.setzePunkt(l.clip, x2, y2, 0, farbe)
            }
        }
    }

    /** prv_get_circle_border_precise */
    private fun kreisRandGenau(yIn: Int, radius: Int): Int {
        val y = yIn + FIX_EINS / 2
        return i16(radius - ganzzahlWurzel(radius.toLong() * radius - y.toLong() * y))
    }

    /** prv_calc_cap_prepared: min/max sind Rohwerte, Rueckgabe als Paar. */
    private fun kappe(mitte: Int, mitteVersatz: Int, radius: Int, fortschritt: Int, minIn: Int, maxIn: Int): Pair<Int, Int> {
        var mi = minIn
        var ma = maxIn
        if (fortschritt >= mitte - radius && fortschritt <= mitte + radius) {
            val rand = if (fortschritt <= mitte) {
                kreisRandGenau(mitte - fortschritt, (radius + FIX_EINS) and 0xffff)
            } else {
                kreisRandGenau(fortschritt - mitte, (radius + FIX_EINS) and 0xffff)
            }
            val kreisMin = i16(mitteVersatz - radius + rand)
            val kreisMax = i16(mitteVersatz + radius - rand)
            mi = i16(min(mi, kreisMin))
            ma = i16(max(ma, kreisMax))
        }
        return mi to ma
    }

    private class Ecken(val oben: Long, val unten: Long, val links: Long, val rechts: Long, val senkrecht: Boolean)

    /** prv_calc_far_points */
    private fun aeussere(p0: Long, p1: Long, radius: Int): Ecken {
        val genauigkeit = 4L
        val dxF = (genauY(p1) - genauY(p0)).toLong() * genauigkeit
        val dyF = (genauX(p0) - genauX(p1)).toLong() * genauigkeit
        val laenge = ganzzahlWurzel(dxF * dxF + dyF * dyF)
        if (laenge == 0) return Ecken(p0, p0, p0, p0, false)
        val vx = i16((dxF * radius / laenge).toInt())
        val vy = i16((dyF * radius / laenge).toInt())
        val p = arrayOf(
            genau(genauX(p0) + vx, genauY(p0) + vy),
            genau(genauX(p0) - vx, genauY(p0) - vy),
            genau(genauX(p1) + vx, genauY(p1) + vy),
            genau(genauX(p1) - vx, genauY(p1) - vy),
        )
        val e = if (dxF > 0) {
            if (dyF > 0) Ecken(p[1], p[2], p[3], p[0], false) else Ecken(p[0], p[3], p[1], p[2], false)
        } else {
            if (dyF > 0) Ecken(p[3], p[0], p[2], p[1], false) else Ecken(p[2], p[1], p[0], p[3], false)
        }
        return Ecken(e.oben, e.unten, e.links, e.rechts, kotlin.math.abs(dxF) > kotlin.math.abs(dyF))
    }

    /** prv_draw_stroked_line_precise (Breite >= 2) */
    fun dick(l: TsLeinwand, p0: Long, p1: Long, breite: Int) {
        val radius = (breite - 1) * FIX_EINS / 2
        val p0x = genauX(p0); val p0y = genauY(p0); val p1x = genauX(p1); val p1y = genauY(p1)

        if (p0x == p1x && p0y == p1y && fixBruch(p0x) == 0 && fixBruch(p0y) == 0) {
            val vorher = l.fuellFarbe
            l.fuellFarbe = l.strichFarbe
            l.fuelleKreis(fixGanz(p0x), fixGanz(p0y), fixGanz(radius))
            l.fuellFarbe = vorher
            return
        }

        val e = aeussere(p0, p1, radius)
        val dxPos = (p1x - p0x) >= 0
        val dyPos = (p1y - p0y) >= 0
        val obenDazu = dxPos == dyPos
        val addTop = if (obenDazu) FIX_EINS / 2 else 0
        val addBottom = if (!obenDazu) FIX_EINS / 2 else 0
        val maske = 0x7

        if (e.senkrecht) {
            var lm0 = e.oben; var lm1 = e.links
            var rm0 = e.oben; var rm1 = e.rechts
            val obenPunkt = i16(min(p0y, p1y) - radius)
            val untenPunkt = i16(max(p0y, p1y) + radius)
            val bruchOben = obenPunkt and maske
            val bruchUnten = untenPunkt and maske
            val clipMinY = l.clip.y - l.ursprungY
            val clipMaxY = clipMinY + l.clip.h
            val yMin = (obenPunkt shr 3).coerceIn(clipMinY, clipMaxY)
            val yMax = (untenPunkt shr 3).coerceIn(clipMinY, clipMaxY)

            if (bruchOben != 0) {
                val y = yMin
                if (y > fixGanz(genauY(lm1))) { lm0 = e.links; lm1 = e.unten }
                if (y > fixGanz(genauY(rm1))) { rm0 = e.rechts; rm1 = e.unten }
                val oben = if (p0y < p1y) p0 else p1
                val fortschritt = i16(y * FIX_EINS + FIX_EINS / 2)
                val (li, re) = kappe(genauY(oben), genauX(oben), radius, fortschritt, Short.MAX_VALUE.toInt(), Short.MIN_VALUE.toInt())
                if (li <= re) l.tupfeWaagrecht(y, li, re, bruchOben shr 1)
            }

            var y = if (bruchOben != 0) yMin + 1 else yMin
            while (y <= yMax) {
                if (y > fixGanz(genauY(lm1))) { lm0 = e.links; lm1 = e.unten }
                if (y > fixGanz(genauY(rm1))) { rm0 = e.rechts; rm1 = e.unten }
                var li = Short.MAX_VALUE.toInt()
                var re = Short.MIN_VALUE.toInt()
                if (y >= fixGanz(genauY(e.oben)) && y <= fixGanz(genauY(e.unten))) {
                    li = if (genauY(lm1) != genauY(lm0)) {
                        i16(genauX(lm0) + ((genauX(lm1) - genauX(lm0)) * (y - ((genauY(lm0) + addTop) / FIX_EINS))) * FIX_EINS / (genauY(lm1) - genauY(lm0)))
                    } else {
                        genauX(lm0)
                    }
                    re = if (genauY(rm1) != genauY(rm0)) {
                        i16(genauX(rm0) + ((genauX(rm1) - genauX(rm0)) * (y - ((genauY(rm0) + addBottom) / FIX_EINS))) * FIX_EINS / (genauY(rm1) - genauY(rm0)))
                    } else {
                        genauX(rm0)
                    }
                }
                val fortschritt = i16(y shl 3)
                kappe(p0y, p0x, radius, fortschritt, li, re).let { li = it.first; re = it.second }
                kappe(p1y, p1x, radius, fortschritt, li, re).let { li = it.first; re = it.second }
                if (li <= re) l.zeichneWaagrecht(y, li, re)
                y++
            }

            if (bruchUnten != 0) {
                val yy = yMax + 1
                val unten = if (p0y > p1y) p0 else p1
                val fortschritt = i16(yy * FIX_EINS - FIX_EINS / 2)
                val (li, re) = kappe(genauY(unten), genauX(unten), radius, fortschritt, Short.MAX_VALUE.toInt(), Short.MIN_VALUE.toInt())
                if (li <= re) l.tupfeWaagrecht(yy, li, re, bruchUnten shr 1)
            }
        } else {
            var tm0 = e.links; var tm1 = e.oben
            var bm0 = e.links; var bm1 = e.unten
            val linksPunkt = i16(min(p0x, p1x) - radius)
            val rechtsPunkt = i16(max(p0x, p1x) + radius)
            val bruchLinks = linksPunkt and maske
            val bruchRechts = rechtsPunkt and maske
            val clipMinX = l.clip.x - l.ursprungX
            val clipMaxX = clipMinX + l.clip.w
            val xMin = (linksPunkt shr 3).coerceIn(clipMinX, clipMaxX)
            val xMax = (rechtsPunkt shr 3).coerceIn(clipMinX, clipMaxX)

            if (bruchLinks != 0) {
                val x = xMin
                if (x > fixGanz(genauX(tm1))) { tm0 = e.oben; tm1 = e.rechts }
                if (x > fixGanz(genauX(bm1))) { bm0 = e.unten; bm1 = e.rechts }
                // In C wirklich nach y gewaehlt (p0.y < p1.y), nicht nach x.
                val links = if (p0y < p1y) p0 else p1
                val fortschritt = i16(x * FIX_EINS + FIX_EINS / 2)
                val (ob, un) = kappe(genauX(links), genauY(links), radius, fortschritt, Short.MAX_VALUE.toInt(), Short.MIN_VALUE.toInt())
                if (ob <= un) l.tupfeSenkrecht(x, ob, un, bruchLinks shr 1)
            }

            var x = if (bruchLinks != 0) xMin + 1 else xMin
            while (x <= xMax) {
                if (x > fixGanz(genauX(tm1))) { tm0 = e.oben; tm1 = e.rechts }
                if (x > fixGanz(genauX(bm1))) { bm0 = e.unten; bm1 = e.rechts }
                var ob = Short.MAX_VALUE.toInt()
                var un = Short.MIN_VALUE.toInt()
                if (x >= fixGanz(genauX(e.links)) && x <= fixGanz(genauX(e.rechts))) {
                    ob = if (genauX(tm1) != genauX(tm0)) {
                        i16(genauY(tm0) + ((genauY(tm1) - genauY(tm0)) * (x - ((genauX(tm0) + addTop) / FIX_EINS))) * FIX_EINS / (genauX(tm1) - genauX(tm0)))
                    } else {
                        genauY(tm0)
                    }
                    un = if (genauX(bm1) != genauX(bm0)) {
                        i16(genauY(bm0) + ((genauY(bm1) - genauY(bm0)) * (x - ((genauX(bm0) + addBottom) / FIX_EINS))) * FIX_EINS / (genauX(bm1) - genauX(bm0)))
                    } else {
                        genauY(bm0)
                    }
                }
                val fortschritt = i16(x shl 3)
                kappe(p0x, p0y, radius, fortschritt, ob, un).let { ob = it.first; un = it.second }
                kappe(p1x, p1y, radius, fortschritt, ob, un).let { ob = it.first; un = it.second }
                if (ob <= un) l.zeichneSenkrecht(x, ob, un)
                x++
            }

            if (bruchRechts != 0) {
                val xx = xMax + 1
                val rechts = if (p0x > p1x) p0 else p1
                val fortschritt = i16(xx * FIX_EINS - FIX_EINS / 2)
                val (ob, un) = kappe(genauX(rechts), genauY(rechts), radius, fortschritt, Short.MAX_VALUE.toInt(), Short.MIN_VALUE.toInt())
                if (ob <= un) l.tupfeSenkrecht(xx, ob, un, bruchRechts shr 1)
            }
        }
    }
}
