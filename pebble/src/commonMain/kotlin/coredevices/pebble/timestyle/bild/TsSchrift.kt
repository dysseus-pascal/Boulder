package coredevices.pebble.timestyle.bild

/*
 * DIE LEISTENTEXTE IN DEN GOTHIC-PIXELSCHRIFTEN DER UHR (.pbf), gesetzt wie
 * graphics_draw_text der Firmware (src/fw/applib/graphics/text_layout.c,
 * text_render.c, text_resources.c): Woerter, Zeilen, Trennstrich und
 * Auslassungszeichen bei Ueberbreite, Zentrierung mit Ganzzahl-Halbierung,
 * Ersatzzeichen fuer fehlende Glyphen. Rechts-nach-links (Hebraeisch) und
 * arabische Formen sind NICHT nachgebaut - ohne Sprachpaket zeigt die Uhr
 * dort ohnehin nur Ersatzkaestchen, die in beide Richtungen gleich aussehen.
 */

internal class TsGlyphe(val breite: Int, val hoehe: Int, val links: Int, val oben: Int, val vorschub: Int, val bits: ByteArray, val bitAnfang: Int) {
    fun gesetzt(x: Int, y: Int): Boolean {
        val n = y * breite + x
        return (bits[bitAnfang + n / 8].toInt() shr (n % 8)) and 1 == 1
    }
}

/** Eine .pbf-Schrift (Fassung 2 oder 3, ohne RLE4 - so liegen die Gothic-Schriften in der Firmware). */
internal class TsPbf(private val daten: ByteArray) {
    private fun u8(i: Int) = daten[i].toInt() and 0xff
    private fun u16(i: Int) = u8(i) or (u8(i + 1) shl 8)
    private fun u32(i: Int) = u16(i) or (u16(i + 2) shl 16)

    private val fassung = u8(0) and 0x3f
    val maxHoehe = u8(1)
    private val anzahl = u16(2)
    val ersatz = u16(4)
    private val hashGroesse: Int
    private val codepunktBytes: Int
    private val versatz16: Boolean
    private val kopf: Int

    init {
        require(fassung == 2 || fassung == 3) { "PBF-Fassung $fassung" }
        hashGroesse = u8(6)
        codepunktBytes = u8(7)
        kopf = if (fassung == 3) u8(8) else 8
        val merkmale = if (fassung == 3) u8(9) else 0
        versatz16 = merkmale and 1 != 0
        require(merkmale and 2 == 0) { "RLE4 ist nicht nachgebaut" }
    }

    private val eintragGroesse = codepunktBytes + if (versatz16) 2 else 4

    /** prv_get_glyph_data_offset: 0 heisst "fehlt" (auch ein Versatz von 0). */
    private fun datenVersatz(codepunkt: Int): Int {
        val tabelle = codepunkt % hashGroesse
        val eintrag = kopf + 4 * tabelle
        val zaehl = u8(eintrag + 1)
        val anfang = kopf + 4 * hashGroesse + u16(eintrag + 2)
        var lo = 0
        var hi = zaehl
        var versatz = 0
        while (hi >= lo) {
            val mitte = (hi + lo) / 2
            val e = anfang + mitte * eintragGroesse
            val cp = if (codepunktBytes == 2) u16(e) else u32(e)
            if (cp < codepunkt) {
                lo = mitte + 1
            } else if (cp > codepunkt) {
                hi = mitte - 1
            } else {
                versatz = if (versatz16) u16(e + codepunktBytes) else u32(e + codepunktBytes)
                break
            }
        }
        if (versatz <= 0) return 0
        return kopf + 4 * hashGroesse + eintragGroesse * anzahl + versatz
    }

    private val zwischenspeicher = HashMap<Int, TsGlyphe?>()

    /** Die Glyphe in DIESER Schrift oder null. */
    fun glyphe(codepunkt: Int): TsGlyphe? = zwischenspeicher.getOrPut(codepunkt) {
        val a = datenVersatz(codepunkt)
        if (a == 0) {
            null
        } else {
            TsGlyphe(
                breite = u8(a), hoehe = u8(a + 1),
                links = daten[a + 2].toInt(), oben = daten[a + 3].toInt(), vorschub = daten[a + 4].toInt(),
                bits = daten, bitAnfang = a + 5,
            )
        }
    }
}

/** Eine Systemschrift (FontInfo) ohne Sprachpaket-Erweiterung. */
internal class TsSchrift(private val basis: TsPbf) {
    val hoehe: Int get() = basis.maxHoehe

    /** prv_get_glyph: die Glyphe, sonst das Ersatzkaestchen, sonst das Leerzeichen. */
    fun glyphe(codepunkt: Int): TsGlyphe? =
        basis.glyphe(codepunkt) ?: basis.glyphe(basis.ersatz) ?: basis.glyphe(' '.code)
}

internal object TsText {
    private const val ZEILENUMBRUCH = '\n'.code
    private const val LEER = ' '.code
    private const val TRENNSTRICH = 0x2d
    private const val AUSLASSUNG = 0x2026
    private const val ZWSP = 0x200B
    private const val WORTVERBINDER = 0x2060
    private val WORTENDE = intArrayOf(0x0, 0xa, 0x20, 0x2d, 0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005, 0x2006, 0x2008, 0x2009, 0x200a, 0x200b, 0x205f, 0x3000)
    private val FORMAT = intArrayOf(0x7F, 0x200C, 0x200D, 0x200E, 0x200F, 0x202A, 0x202B, 0x202C, 0x202D, 0x202E, 0x20E3, 0xFE0E, 0xFE0F, 0xFEFF, 0xFFFC)

    private fun nullBreite(cp: Int) = cp == ZWSP || cp == WORTVERBINDER

    private fun ueberspringen(cp: Int) =
        (cp < 0x20 && cp != ZEILENUMBRUCH) || cp in 0x80..0x9F || cp in 0x2061..0x206F || cp in 0xFFF9..0xFFFB ||
            cp in 0xE0000..0xE007F || cp in 0xF0000..0x10FFFD || cp in 0x1F3FB..0x1F3FF

    private fun ideogramm(cp: Int) = cp > 0x2e80

    private fun unicodeLeerzeichen(cp: Int) = cp == 0x00A0 || cp in 0x2000..0x200A || cp == 0x202F || cp == 0x205F || cp == 0x3000

    /** prv_codepoint_get_horizontal_advance */
    private fun vorschub(s: TsSchrift, cp: Int): Int {
        if (nullBreite(cp)) return 0
        if (unicodeLeerzeichen(cp)) {
            val em = s.hoehe
            return when (cp) {
                0x2001, 0x2003, 0x3000 -> em
                0x2000, 0x2002 -> em / 2
                0x2004 -> em / 3
                0x2005 -> em / 4
                0x2006 -> em / 6
                0x2009, 0x202F -> em / 5
                0x200A -> maxOf(em / 12, 1)
                0x205F -> em * 4 / 18
                0x2007 -> s.glyphe('0'.code)?.vorschub ?: 0
                0x2008 -> s.glyphe('.'.code)?.vorschub ?: 0
                0x00A0 -> s.glyphe(LEER)?.vorschub ?: 0
                else -> 0
            }
        }
        return maxOf(s.glyphe(cp)?.vorschub ?: 0, 0)
    }

    private enum class Wortzustand { Start, Ideogramm, Wachsend, Verbindend, Ende }

    private fun weiter(z: Wortzustand, cp: Int): Wortzustand = when (z) {
        Wortzustand.Start -> when {
            cp == ZEILENUMBRUCH -> Wortzustand.Ende
            ideogramm(cp) -> Wortzustand.Ideogramm
            else -> Wortzustand.Wachsend
        }
        Wortzustand.Ideogramm -> if (cp == WORTVERBINDER) Wortzustand.Verbindend else Wortzustand.Ende
        Wortzustand.Wachsend -> when {
            cp == WORTVERBINDER -> Wortzustand.Verbindend
            ideogramm(cp) || cp in WORTENDE -> Wortzustand.Ende
            else -> Wortzustand.Wachsend
        }
        Wortzustand.Verbindend -> when {
            cp == ZEILENUMBRUCH -> Wortzustand.Ende
            ideogramm(cp) -> Wortzustand.Ideogramm
            cp == WORTVERBINDER -> Wortzustand.Verbindend
            else -> Wortzustand.Wachsend
        }
        Wortzustand.Ende -> Wortzustand.Ende
    }

    private class Wort(var anfang: Int, var ende: Int, var breite: Int)

    private class Zeile(var anfang: Int, var x: Int, var y: Int, var breite: Int, var maxBreite: Int, var hoehe: Int, var nachsatz: Int)

    private class Satz(val cps: IntArray, val schrift: TsSchrift, val box: TsRechteck) {
        fun cp(i: Int): Int = if (i < cps.size) cps[i] else 0
    }

    /** word_init; false am Textende. */
    private fun wortBeginn(s: Satz, w: Wort, start: Int): Boolean {
        w.breite = 0
        if (s.cp(start) == 0) {
            w.anfang = start; w.ende = start
            return false
        }
        var i = start
        while (nullBreite(s.cp(i))) {
            if (s.cp(i) == 0) {
                w.anfang = start; w.ende = start
                return false
            }
            i++
        }
        if (s.cp(i) == 0) {
            w.anfang = start; w.ende = start
            return false
        }
        w.anfang = i
        var aktuell = s.cp(i)
        var zustand = weiter(Wortzustand.Start, aktuell)
        do {
            i++
            val naechster = s.cp(i)
            if (zustand == Wortzustand.Wachsend || zustand == Wortzustand.Ideogramm) {
                w.breite += vorschub(s.schrift, aktuell)
            }
            aktuell = naechster
            zustand = weiter(zustand, aktuell)
        } while (zustand != Wortzustand.Ende)
        w.ende = i
        return true
    }

    /** word_trim_preceding_codepoint; true = gekuerzt. Anfang -1 = Textende (NULL in C). */
    private fun kuerzeVorne(s: Satz, w: Wort, cp: Int): Boolean {
        if (s.cp(w.anfang) != cp) return false
        val naechster = w.anfang + 1
        if (naechster >= s.cps.size) {
            w.anfang = -1
            return false
        }
        if (w.ende == naechster) {
            val textEnde = s.cp(w.ende) == 0 || naechster >= s.cps.size
            if (!textEnde) wortBeginn(s, w, w.ende)
            return false
        }
        w.breite -= vorschub(s.schrift, cp)
        w.anfang = naechster
        return true
    }

    /** walk_line ohne BiDi; [zeichnen] null = nur messen (update_dimensions). Rueckgabe: zuletzt besuchter Index oder -1. */
    private fun geheZeile(s: Satz, z: Zeile, zeichnen: ((x: Int, y: Int, cp: Int) -> Unit)?): Int {
        val verfuegbar = if (zeichnen == null) z.maxBreite else z.breite
        val nachsatzBreite = if (z.nachsatz != 0) vorschub(s.schrift, z.nachsatz) else 0
        if (verfuegbar < nachsatzBreite) return -1
        var i = z.anfang
        var aktuell = s.cp(i)
        if (aktuell == ZEILENUMBRUCH) aktuell = LEER // GTextOverflowModeFill: Zeilenumbruch wie Leerzeichen
        var gelaufen = 0
        var naechsteBreite = vorschub(s.schrift, aktuell)
        var zuletzt = -1
        while (gelaufen + naechsteBreite + nachsatzBreite <= verfuegbar) {
            val x = z.x + gelaufen
            if (zeichnen == null) {
                z.breite = x + naechsteBreite - z.x
            } else if (!nullBreite(aktuell) && !unicodeLeerzeichen(aktuell)) {
                zeichnen(x, z.y, aktuell)
            }
            gelaufen += naechsteBreite
            zuletzt = i
            if (i >= s.cps.size || i + 1 >= s.cps.size) {
                i = s.cps.size
                aktuell = 0
                break
            }
            i++
            aktuell = s.cp(i)
            if (aktuell == ZEILENUMBRUCH) aktuell = LEER
            naechsteBreite = vorschub(s.schrift, aktuell)
        }
        if (zuletzt >= 0) {
            while (aktuell == ZEILENUMBRUCH || aktuell == LEER) {
                val b = if (aktuell == ZEILENUMBRUCH) 0 else vorschub(s.schrift, aktuell)
                if (gelaufen < b) break
                gelaufen -= b
                if (i <= 0) break
                i--
                aktuell = s.cp(i)
            }
        }
        if (z.nachsatz != 0) {
            val x = z.x + gelaufen
            if (zeichnen == null) z.breite = x + nachsatzBreite - z.x else zeichnen(x, z.y, z.nachsatz)
        }
        return zuletzt
    }

    /** line_add_word; true = ganzes Wort passte. */
    private fun fuegeWortAn(s: Satz, z: Zeile, w: Wort): Boolean {
        if (z.breite > z.maxBreite) return false
        z.hoehe = s.schrift.hoehe
        if (s.cp(w.anfang) == ZEILENUMBRUCH) {
            kuerzeVorne(s, w, ZEILENUMBRUCH)
            if (w.anfang < 0) return false
        }
        val ueber = z.breite + w.breite > z.maxBreite
        val zeilenAnfang = z.breite == 0
        if (zeilenAnfang) z.anfang = w.anfang
        if (ueber && zeilenAnfang) {
            z.nachsatz = TRENNSTRICH
            var zuletzt = geheZeile(s, z, null)
            if (zuletzt < 0) zuletzt = w.anfang
            val gekuerzt = z.breite - vorschub(s.schrift, TRENNSTRICH)
            w.breite -= gekuerzt
            w.anfang = zuletzt + 1
            return false
        }
        if (!ueber) {
            z.breite += w.breite
            return true
        }
        while (kuerzeVorne(s, w, LEER)) { /* word_trim_preceding_whitespace */ }
        return false
    }

    /**
     * graphics_draw_text mit GTextOverflowModeFill. [box] relativ zur Ebene der
     * Leinwand; gezeichnet wird in [farbe], beschnitten an ihrem Clip.
     */
    fun zeichne(l: TsLeinwand, text: String, schrift: TsSchrift, box: TsRechteck, ausrichtung: Ausrichtung, farbe: Int) {
        val roh = codepunkte(text)
        // char_iter ueberspringt Formatzeichen und Steuerzeichen.
        val cps = roh.filter { it !in FORMAT && !ueberspringen(it) }.toIntArray()
        val global = TsRechteck(box.x + l.ursprungX, box.y + l.ursprungY, box.w, box.h)
        if (global.beschnitten(l.clip).h <= 0) return
        if (global.w <= 0 || global.h <= 0 || cps.isEmpty()) return
        val s = Satz(cps, schrift, global)
        val zeile = Zeile(0, global.x, global.y, 0, global.w, schrift.hoehe, 0)
        val wort = Wort(0, 0, 0)
        wortBeginn(s, wort, 0)

        while (!vertikalUeber(zeile, s)) {
            // line_add_words
            zeile.anfang = wort.anfang
            var rest = zeile.anfang >= 0
            while (rest && zeile.maxBreite > 0) {
                val naechstes = Wort(wort.anfang, wort.ende, wort.breite)
                if (!fuegeWortAn(s, zeile, naechstes)) {
                    wort.anfang = naechstes.anfang; wort.ende = naechstes.ende; wort.breite = naechstes.breite
                    rest = naechstes.anfang >= 0
                    break
                }
                rest = if (s.cp(wort.ende) == 0) false else wortBeginn(s, wort, wort.ende)
            }
            // set_ellipsis_on_overflow_last_line_cb
            if (rest && zeile.y + 2 * zeile.hoehe > global.y + global.h) {
                zeile.nachsatz = AUSLASSUNG
                geheZeile(s, zeile, null)
            }
            // prv_line_justify
            val frei = zeile.maxBreite - zeile.breite
            when (ausrichtung) {
                Ausrichtung.Mitte -> zeile.x += frei / 2
                Ausrichtung.Rechts -> zeile.x += frei
                Ausrichtung.Links -> Unit
            }
            val zeileUnten = zeile.y + zeile.hoehe + (zeile.hoehe + 4) / 5
            if (zeileUnten > l.clip.y) {
                geheZeile(s, zeile) { x, y, cp -> zeichneGlyphe(l, schrift, x, y, cp, farbe) }
            }
            if (zeile.y + zeile.hoehe > l.clip.y + l.clip.h) break
            if (!rest) break
            if (vertikalUeber(zeile, s)) break
            zeile.x = global.x
            zeile.y += schrift.hoehe
            zeile.breite = 0
            zeile.maxBreite = global.w
            zeile.nachsatz = 0
        }
    }

    /** prv_line_iter_is_vertical_overflow (Fill-Modus) */
    private fun vertikalUeber(z: Zeile, s: Satz): Boolean {
        val unten = if (z.y != s.box.y) z.y + s.schrift.hoehe else z.y
        return unten > s.box.y + s.box.h
    }

    /** render_glyph: jedes gesetzte Bit in Textfarbe (deckend), beschnitten am Clip. */
    private fun zeichneGlyphe(l: TsLeinwand, schrift: TsSchrift, x: Int, y: Int, cp: Int, farbe: Int) {
        val g = schrift.glyphe(cp) ?: return
        val ox = x + g.links
        val oy = y + g.oben
        val c = l.clip
        for (gy in 0 until g.hoehe) {
            val py = oy + gy
            if (py < c.y || py >= c.maxY || py !in 0 until BILD_HOEHE) continue
            for (gx in 0 until g.breite) {
                val px = ox + gx
                if (px < c.x || px >= c.maxX || px !in 0 until BILD_BREITE) continue
                if (g.gesetzt(gx, gy)) l.pixel[py * BILD_BREITE + px] = mitAlpha(farbe, 3)
            }
        }
    }

    enum class Ausrichtung { Links, Mitte, Rechts }
}
