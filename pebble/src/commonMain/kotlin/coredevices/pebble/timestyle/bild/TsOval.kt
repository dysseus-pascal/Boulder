package coredevices.pebble.timestyle.bild

import kotlin.math.max
import kotlin.math.min

/*
 * Gefuellte Kreise und Viertelkreise wie die Firmware (graphics_circle.c:
 * prv_fill_oval_quadrant -> prv_fill_oval_precise). TimeStyle braucht das fuer
 * die runden Ecken des Datumsfelds in grosser Schrift (Radius 2) und fuer
 * Linienstuecke, die nur ein Punkt sind. Die Firmware rechnet dabei auch mit
 * einem inneren Kreis von einem halben Pixel und mit Winkelmasken - beides
 * aendert Randpixel, darum steht es hier genauso.
 */
internal object TsOval {
    private const val VIERTEL = TRIG_MAX_ANGLE / 4
    private val QUADRANTEN = intArrayOf(TsLeinwand.ECKE_OR, TsLeinwand.ECKE_UR, TsLeinwand.ECKE_UL, TsLeinwand.ECKE_OL)

    private class Teil(val winkel: Int, val viertel: Int)
    private class Plan(val start: Teil, val voll: Int, val ende: Teil)

    /** prv_fill_oval_quadrant (innen und aussen gleich rund) */
    fun fuelleViertel(l: TsLeinwand, px: Int, py: Int, radius: Int, viertel: Int) {
        val mitte = genau((px shl 3) or 4, (py shl 3) or 4)
        val aussen = (radius shl 3) or 4
        val innen = (0 shl 3) or 4
        fuelleViertelGenau(l, mitte, aussen, aussen, innen, innen, viertel)
    }

    /** prv_fill_oval_quadrant_precise */
    private fun fuelleViertelGenau(l: TsLeinwand, mitte: Long, ax: Int, ay: Int, ix: Int, iy: Int, viertel: Int) {
        if (viertel == TsLeinwand.ECKEN_KEINE) return
        var start = 0
        var ende = 0
        if (viertel == TsLeinwand.ECKEN_ALLE) {
            ende = TRIG_MAX_ANGLE
        } else {
            if (viertel and QUADRANTEN[0] != 0) {
                for (i in 3 downTo 0) {
                    if (viertel and QUADRANTEN[i] == 0) {
                        start = ((i + 1) % 4) * VIERTEL
                        break
                    }
                }
            } else {
                for (i in 1..4) {
                    if (viertel and QUADRANTEN[i % 4] != 0) {
                        start = i * VIERTEL
                        break
                    }
                }
            }
            if (viertel and QUADRANTEN[0] == 0) {
                for (i in 3 downTo 0) {
                    if (viertel and QUADRANTEN[i] != 0) {
                        ende = ((i + 1) % 4) * VIERTEL
                        break
                    }
                }
            } else {
                for (i in 1..4) {
                    if (viertel and QUADRANTEN[i % 4] == 0) {
                        ende = i * VIERTEL
                        break
                    }
                }
            }
        }
        if (ende <= start) ende += TRIG_MAX_ANGLE
        fuelleOvalGenau(l, mitte, ax, ay, ix, iy, start, ende)
    }

    private fun volleViertel(startIn: Int, endeIn: Int): Int {
        var ende = endeIn
        if (startIn >= ende) ende += 4
        var maske = 0
        for (i in startIn + 1 until ende) maske = maske or QUADRANTEN[i % 4]
        return maske
    }

    /** prv_calc_draw_config_ellipsis */
    private fun plan(start: Int, ende: Int): Plan {
        if (ende == start) return Plan(Teil(0, 0), 0, Teil(0, 0))
        if (ende - start >= TRIG_MAX_ANGLE) return Plan(Teil(0, 0), TsLeinwand.ECKEN_ALLE, Teil(0, 0))
        val sN = normalisiereWinkel(start)
        val eN = normalisiereWinkel(ende)
        var sQ = (sN / VIERTEL) % 4
        val eQ = (eN / VIERTEL) % 4
        return if (sQ == eQ) {
            val voll = if (ende - start > VIERTEL) volleViertel(sQ, eQ) else 0
            Plan(Teil(sN, QUADRANTEN[sQ]), voll, Teil(eN, QUADRANTEN[eQ]))
        } else {
            val s = Teil(sN, QUADRANTEN[sQ])
            val e = Teil(eN, QUADRANTEN[eQ])
            if (start % VIERTEL == 0) sQ--
            Plan(s, volleViertel(sQ, eQ), e)
        }
    }

    /** prv_get_rotated_precise_point (Kreisfall von ..._for_ellipsis) */
    private fun gedreht(mitte: Long, radius: Int, winkel: Int): Long {
        val r = radius and 0xffff
        return genau(
            genauX(mitte) + r * sinTabelle(winkel) / TRIG_MAX_RATIO,
            genauY(mitte) - r * cosTabelle(winkel) / TRIG_MAX_RATIO,
        )
    }

    /** prv_get_ellipsis_border - hier nur der Kreisfall (beide Radien gleich). */
    private fun ellipsenRand(versatz: Int, radiusQuadrat: Long): Int {
        // uint32 - int in C: ein negatives Ergebnis wuerde gross positiv.
        var wert = (radiusQuadrat shl 3) - versatz.toLong() * versatz
        wert = wert and 0xffffffffL
        return i16(ganzzahlWurzel(wert))
    }

    /** prv_intersection_between_horizontal_and_line */
    private fun schnitt(fortschritt: Int, oben: Long, unten: Long): Int {
        if (genauY(unten) - genauY(oben) == 0) return i16(genauX(oben) + (genauX(unten) - genauX(oben)))
        return i16(genauX(oben) + (genauX(unten) - genauX(oben)) * (fortschritt - genauY(oben)) / (genauY(unten) - genauY(oben)))
    }

    private class Kanten(var oben: Int, var unten: Int)

    /** prv_get_angles_mask_edge: setzt k.oben (fuer obere Viertel) oder k.unten. */
    private fun winkelKante(y: Int, mitte: Long, viertel: Int, k: Kanten, oben: Long, unten: Long) {
        if (viertel and TsLeinwand.ECKEN_OBEN != 0) {
            k.oben = if (genauY(mitte) - y <= genauY(unten)) {
                if (genauY(mitte) - y >= genauY(oben)) schnitt(i16(genauY(mitte) - y), oben, unten) else genauX(oben)
            } else {
                genauX(unten)
            }
        } else {
            k.unten = if (genauY(mitte) + y >= genauY(oben)) {
                if (genauY(mitte) + y <= genauY(unten)) schnitt(i16(genauY(mitte) + y), oben, unten) else genauX(unten)
            } else {
                genauX(oben)
            }
        }
    }

    /** prv_draw_scanline_collision_points */
    private fun zeile(l: TsLeinwand, y: Int, links: Int, rechts: Int, startIn: Int, endeIn: Int, enge: Boolean) {
        var start = startIn
        var ende = endeIn
        if (start > ende || (enge && start == ende)) {
            start = max(start, links)
            ende = min(ende, rechts)
            if (links <= ende) l.zeichneWaagrecht(y, i16(links), i16(ende - FIX_EINS))
            if (start <= rechts) l.zeichneWaagrecht(y, i16(start), i16(rechts - FIX_EINS))
        } else {
            start = max(links, start)
            ende = min(rechts, ende)
            if (start <= ende) l.zeichneWaagrecht(y, i16(start), i16(ende - FIX_EINS))
        }
    }

    /** prv_fill_oval_precise (Kreis: innen und aussen je gleiche Radien) */
    private fun fuelleOvalGenau(l: TsLeinwand, mitte: Long, axIn: Int, ayIn: Int, ixIn: Int, iyIn: Int, winkelStart: Int, winkelEnde: Int) {
        val plan = plan(winkelStart, winkelEnde)
        val vollkreis = plan.voll == TsLeinwand.ECKEN_ALLE
        val mitteX = genauX(mitte)
        val mitteY = genauY(mitte)
        val ungeradeZeile = fixBruch(mitteY) == 4
        val enge = winkelEnde - winkelStart > TRIG_MAX_ANGLE / 2
        val ix = max(ixIn, 0)
        val iy = max(iyIn, 0)
        val ax = axIn
        val ay = ayIn
        val ohneInnen = ix == 0 || iy == 0
        val aussenQuadratY = ((ay.toLong() * ay) shr 3) and 0xffffffffL
        val innenQuadratY = ((iy.toLong() * iy) shr 3) and 0xffffffffL

        var startOben = gedreht(mitte, ax, plan.start.winkel)
        var endeOben = gedreht(mitte, ax, plan.ende.winkel)
        var startUnten = if (ohneInnen) mitte else gedreht(mitte, ix, plan.start.winkel)
        var endeUnten = if (ohneInnen) mitte else gedreht(mitte, ix, plan.ende.winkel)

        if (genauY(startOben) > genauY(startUnten)) {
            val t = startOben; startOben = startUnten; startUnten = t
        } else if (genauY(startOben) == genauY(startUnten) && (plan.start.viertel and TsLeinwand.ECKEN_UNTEN != 0)) {
            val t = startOben; startOben = startUnten; startUnten = t
        }
        if (genauY(endeOben) > genauY(endeUnten)) {
            val t = endeOben; endeOben = endeUnten; endeUnten = t
        } else if (genauY(endeOben) == genauY(endeUnten) && (plan.ende.viertel and TsLeinwand.ECKEN_UNTEN != 0)) {
            val t = endeOben; endeOben = endeUnten; endeUnten = t
        }

        var drawMin = 0
        var drawMax = fixGanz(ay)
        val angepasstMitte = fixGanz(mitteY) + l.ursprungY
        var obenA = angepasstMitte - fixGanz(ay) - 1
        var untenA = angepasstMitte + fixGanz(ay) + 1
        val c = l.clip
        obenA = min(max(obenA, c.y), c.y + c.h)
        untenA = min(max(untenA, c.y), c.y + c.h)
        obenA -= l.ursprungY
        untenA -= l.ursprungY
        val maxOben = max(fixGanz(mitteY) - obenA, 0)
        val maxUnten = max(untenA - fixGanz(mitteY), 0)
        val minOben = max(fixGanz(mitteY) - untenA, 0)
        val minUnten = max(obenA - fixGanz(mitteY) - 1, 0)
        drawMax = min(drawMax, max(maxOben, maxUnten))
        drawMin = max(drawMin, max(minOben, minUnten))

        var y = drawMin shl 3
        val obenZeichnen = vollkreis || (plan.voll and TsLeinwand.ECKEN_OBEN != 0 ||
            plan.start.viertel and TsLeinwand.ECKEN_OBEN != 0 || plan.ende.viertel and TsLeinwand.ECKEN_OBEN != 0)
        val untenZeichnen = vollkreis || (plan.voll and TsLeinwand.ECKEN_UNTEN != 0 ||
            plan.start.viertel and TsLeinwand.ECKEN_UNTEN != 0 ||
            (plan.ende.viertel and TsLeinwand.ECKEN_UNTEN != 0 && plan.ende.winkel % VIERTEL != 0))
        val versatzOben = 1
        var versatzUnten = 1

        val vorher = l.strichFarbe
        l.strichFarbe = l.fuellFarbe

        if (ungeradeZeile) {
            val k = Kanten(i16(mitteX - ax), i16(mitteX + ax))
            if (!vollkreis) {
                var yMitte = i16(y - 4)
                // start setzt k.oben (startende Kante) bzw. k.unten (endende) - wie die Zeiger in C
                val ks = Kanten(k.oben, k.unten)
                winkelKante(yMitte, mitte, plan.start.viertel, ks, startOben, startUnten)
                val ke = Kanten(ks.unten, ks.oben)
                winkelKante(yMitte, mitte, plan.ende.viertel, ke, endeOben, endeUnten)
                k.oben = ke.unten
                k.unten = ke.oben
                if (k.oben == k.unten) {
                    yMitte = fixMitGanz(yMitte, fixGanz(yMitte) + 1)
                    val ks2 = Kanten(k.oben, k.unten)
                    winkelKante(yMitte, mitte, plan.start.viertel, ks2, startOben, startUnten)
                    val ke2 = Kanten(ks2.unten, ks2.oben)
                    winkelKante(yMitte, mitte, plan.ende.viertel, ke2, endeOben, endeUnten)
                    k.oben = ke2.unten
                    k.unten = ke2.oben
                }
            }
            val aussenRand = ellipsenRand(y, aussenQuadratY)
            val links = i16(mitteX - aussenRand)
            val rechts = i16(mitteX + aussenRand)
            if (!ohneInnen && fixGanz(iy) != 0) {
                val innenRand = ellipsenRand(y, innenQuadratY)
                zeile(l, fixGanz(mitteY), links, i16(mitteX - innenRand), k.oben, k.unten, enge)
                zeile(l, fixGanz(mitteY), i16(mitteX + innenRand), rechts, k.oben, k.unten, enge)
            } else {
                zeile(l, fixGanz(mitteY), links, rechts, k.oben, k.unten, enge)
            }
            y = fixMitGanz(y, fixGanz(y) + 1)
        } else {
            y = fixMitBruch(y, 4)
            versatzUnten = 0
        }

        for (i in drawMin until drawMax) {
            // oben: start -> obenStart, ende -> obenEnde; unten: start -> untenEnde, ende -> untenStart
            var obenStart = i16(mitteX - ax)
            var obenEnde = i16(mitteX + ax)
            var untenStart = obenStart
            var untenEnde = obenEnde
            if (!vollkreis) {
                val ks = Kanten(obenStart, untenEnde)
                winkelKante(y, mitte, plan.start.viertel, ks, startOben, startUnten)
                obenStart = ks.oben
                untenEnde = ks.unten
                val ke = Kanten(obenEnde, untenStart)
                winkelKante(y, mitte, plan.ende.viertel, ke, endeOben, endeUnten)
                obenEnde = ke.oben
                untenStart = ke.unten
            }
            val aussenRand = ellipsenRand(y, aussenQuadratY)
            val links = i16(mitteX - aussenRand)
            val rechts = i16(mitteX + aussenRand)
            if (!ohneInnen && i < fixGanz(iy)) {
                val innenRand = ellipsenRand(y, innenQuadratY)
                val innenLinks = i16(mitteX - innenRand)
                val innenRechts = i16(mitteX + innenRand)
                if (obenZeichnen) {
                    zeile(l, fixGanz(mitteY) - i - versatzOben, links, innenLinks, obenStart, obenEnde, enge)
                    zeile(l, fixGanz(mitteY) - i - versatzOben, innenRechts, rechts, obenStart, obenEnde, enge)
                }
                if (untenZeichnen) {
                    zeile(l, fixGanz(mitteY) + i + versatzUnten, links, innenLinks, untenStart, untenEnde, enge)
                    zeile(l, fixGanz(mitteY) + i + versatzUnten, innenRechts, rechts, untenStart, untenEnde, enge)
                }
            } else {
                if (obenZeichnen) zeile(l, fixGanz(mitteY) - i - versatzOben, links, rechts, obenStart, obenEnde, enge)
                if (untenZeichnen) zeile(l, fixGanz(mitteY) + i + versatzUnten, links, rechts, untenStart, untenEnde, enge)
            }
            y = fixMitGanz(y, fixGanz(y) + 1)
        }

        l.strichFarbe = vorher
    }
}
