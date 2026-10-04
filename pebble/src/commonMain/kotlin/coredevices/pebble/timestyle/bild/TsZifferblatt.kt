package coredevices.pebble.timestyle.bild

import coredevices.pebble.timestyle.TsBedrockEinstellungen
import coredevices.pebble.timestyle.TsWidget

/*
 * TIMESTYLE BEDROCK ALS BILD - so, wie die Uhr es zeichnet. Aufbau und Zahlen
 * aus clock_area.c, sidebar.c und sidebar_widgets.c der Firmware (nur emery,
 * 200 x 228, rechteckig): dieselben Hoehen, Versaetze, Schriften, dieselbe
 * Reihenfolge der Ebenen (Hintergrund, Leiste, dann die Uhrzeit darueber).
 *
 * Was die Uhr selbst misst (Wetter, Schritte, Puls ...), kommt als [TsZustand]
 * herein; das Bild haengt nur davon und von den Einstellungen ab.
 */

/** Die Schriftwahl der Ziffern (Byte 6, wie FONT_SETTING_* der Firmware). */
object TsSchriftwahl {
    const val SANS = 0
    const val LECO = 1
    const val FETT = 2
    const val FETTE_STUNDE = 3
    const val FETTE_MINUTE = 4
}

/** Die Wettersymbole von TimeStyle (WeatherCondition der Firmware). */
enum class TsWetterSymbol(val datei: String) {
    KLAR_TAG("CLEAR_DAY"),
    KLAR_NACHT("CLEAR_NIGHT"),
    BEWOELKT("CLOUDY_DAY"),
    STARKREGEN("HEAVY_RAIN"),
    STARKSCHNEE("HEAVY_SNOW"),
    REGEN("LIGHT_RAIN"),
    SCHNEE("LIGHT_SNOW"),
    TEILS_BEWOELKT_NACHT("PARTLY_CLOUDY_NIGHT"),
    TEILS_BEWOELKT("PARTLY_CLOUDY"),
    REGEN_UND_SCHNEE("RAINING_AND_SNOWING"),
    GEWITTER("THUNDERSTORM"),
    ALLGEMEIN("WEATHER_GENERIC"),
}

/** Das Wetter, wie die Uhr es aus ihrem Wetterspeicher hat. Null-Werte zeigen "...". */
data class TsWetter(
    val temperatur: Int?,
    val hoch: Int?,
    val tief: Int?,
    val uv: Int?,
    val symbol: TsWetterSymbol?,
    val symbolHeute: TsWetterSymbol?,
)

/** Alles, was die Uhr beim Zeichnen von sich weiss. */
data class TsZustand(
    val stunde: Int,
    val minute: Int,
    val sekunde: Int,
    /** Tag im Monat 1..31 */
    val tag: Int,
    /** Monat 0..11 wie tm_mon */
    val monat: Int,
    val jahr: Int,
    /** 0 = Sonntag wie tm_wday */
    val wochentag: Int,
    /** 0-basiert wie tm_yday */
    val jahresTag: Int,
    /** Die 24-h-Einstellung der UHR. */
    val vierundzwanzig: Boolean,
    /** tm_gmtoff der Uhr in Sekunden (fuer die zweite Zeitzone). */
    val utcVersatzSekunden: Long,
    val beats: Int,
    /** null: kein (frisches) Wetter - "..." ohne Symbol. */
    val wetter: TsWetter?,
    val schritte: Int,
    val distanzMeter: Int,
    val metrisch: Boolean,
    val puls: Int,
    val schlafSekunden: Int,
    val ruhigerSchlafSekunden: Int,
    val akkuProzent: Int,
    val laedt: Boolean,
    val verbunden: Boolean,
)

/** Die Dateien, aus denen das Bild entsteht - byte-gleich mit denen der Firmware. */
class TsRessourcen internal constructor(
    internal val leco: TsFfont,
    internal val sans: TsFfont,
    internal val sansFett: TsFfont,
    internal val gothic18: TsSchrift,
    internal val gothic24: TsSchrift,
    internal val gothic28: TsSchrift,
    internal val symbole: Map<String, TsPdcBild>,
) {
    companion object {
        /** Die Dateinamen unter files/timestyle/ (composeResources) bzw. im Testordner. */
        val SYMBOLE = listOf(
            "BATTERY_BG", "BATTERY_CHARGE", "CLEAR_DAY", "CLEAR_NIGHT", "CLOUDY_DAY", "DATE_BG_LG", "DISCONNECTED",
            "HEALTH_HEART", "HEALTH_SLEEP", "HEALTH_STEPS", "HEAVY_RAIN", "HEAVY_SNOW", "LIGHT_RAIN", "LIGHT_SNOW",
            "PARTLY_CLOUDY_NIGHT", "PARTLY_CLOUDY", "RAINING_AND_SNOWING", "THUNDERSTORM", "WEATHER_GENERIC",
        )
        const val LECO = "LECO1976-Regular.ffont"
        const val SANS = "KumbhSans-Regular.ffont"
        const val SANS_FETT = "KumbhSans-Bold.ffont"
        const val GOTHIC_18 = "GOTHIC_18_BOLD.pbf"
        const val GOTHIC_24 = "GOTHIC_24_BOLD.pbf"
        const val GOTHIC_28 = "GOTHIC_28_BOLD.pbf"

        /** Alle Dateinamen. */
        val DATEIEN: List<String> = listOf(LECO, SANS, SANS_FETT, GOTHIC_18, GOTHIC_24, GOTHIC_28) + SYMBOLE.map { "$it.pdc" }

        /** Baut die Ressourcen aus den Dateiinhalten (Name -> Bytes). */
        fun aus(dateien: (String) -> ByteArray): TsRessourcen = TsRessourcen(
            leco = TsFfont(dateien(LECO)),
            sans = TsFfont(dateien(SANS)),
            sansFett = TsFfont(dateien(SANS_FETT)),
            gothic18 = TsSchrift(TsPbf(dateien(GOTHIC_18))),
            gothic24 = TsSchrift(TsPbf(dateien(GOTHIC_24))),
            gothic28 = TsSchrift(TsPbf(dateien(GOTHIC_28))),
            symbole = SYMBOLE.associateWith { TsPdc.lies(dateien("$it.pdc")) },
        )
    }
}

/** Die Masse der Leiste fuer emery (SidebarWidgetLayout nach SidebarWidgets_updateFonts). */
private class Masse(gross: Boolean) {
    val akkuNurSymbol = 14
    val akkuMitProzent = if (gross) 38 else 30
    val akkuTextY = if (gross) 15 else 17
    val datum = if (gross) 74 else 67
    val datumKompakt = if (gross) 50 else 47
    val datumKorrektur = if (gross) 9 else 10
    val datumZahlY = if (gross) 28 else 29
    val datumMonatY = if (gross) 56 else 53
    val datumBildX = 1
    val datumBildY = 29
    val datumRechteckX = 0
    val datumRechteckY = if (gross) 34 else 35
    val datumRechteckB = 30
    val datumRechteckH = 26
    val wetter = if (gross) 49 else 46
    val wetterTempY = if (gross) 21 else 22
    val textX = if (gross) -10 else -9
    val textB = if (gross) 50 else 48
    val vorhersage = if (gross) 76 else 69
    val vorhersageHochY = if (gross) 21 else 22
    val vorhersageStrichY = if (gross) 45 else 42
    val vorhersageTiefY = if (gross) 48 else 45
    val vorhersageStrichX = 1
    val vorhersageStrichB = 28
    val einfach = if (gross) 35 else 32
    val einfachLabelY = if (gross) -6 else -4
    val einfachY = if (gross) 8 else 12
    val trennung = 22
    val puls = if (gross) 47 else 40
    val pulsY = 20
    val schritte = if (gross) 35 else 32
    val schritteY = if (gross) 10 else 11
    val schlaf = 52
    val schlafStundenY = 13
    val schlafMinutenY = 34
    val sekunden = 18
    val sekundenY = -10
}

/**
 * Zeichnet das Zifferblatt. Rueckgabe: 200 x 228 GColor8-Werte (aarrggbb, 0..255),
 * Zeile fuer Zeile. [einstellungen] wie sie die Uhr gelesen hat (die Schrift also
 * schon so, wie die Firmware sie kennt).
 */
fun tsZifferblatt(einstellungen: TsBedrockEinstellungen, zustand: TsZustand, r: TsRessourcen): IntArray {
    val l = TsLeinwand()
    TsZeichner(l, einstellungen, zustand, r).zeichne()
    return l.pixel
}

/** Ein GColor8 als ARGB-Int fuer den Bildschirm des Telefons (Kanaele 0, 85, 170, 255). */
fun tsArgb(g: Int): Int {
    val r = ((g shr 4) and 3) * 85
    val gr = ((g shr 2) and 3) * 85
    val b = (g and 3) * 85
    return (0xff shl 24) or (r shl 16) or (gr shl 8) or b
}

private class TsZeichner(val l: TsLeinwand, val s: TsBedrockEinstellungen, val z: TsZustand, val r: TsRessourcen) {
    private val gross = s.useLargeFonts
    private val m = Masse(gross)
    private val leisteBreite = if (gross) 43 else 34
    private val xVersatz = (leisteBreite - 30) / 2
    private var kompakt = false

    private val klein = r.gothic18
    private val mittel = r.gothic24
    private val gr = r.gothic28
    private val aktuelleSchrift = if (gross) gr else mittel
    private val akkuSchrift = if (gross) gr else klein

    private val leisteSchwarz = (s.sidebarColor and 0x3f) == 0
    private val symbolFuellung = if (leisteSchwarz) 0xc0 else 0xff
    private val symbolStrich = if (leisteSchwarz) s.sidebarTextColor else 0xc0

    fun zeichne() {
        // Fenster: Hintergrund ueber alles
        l.ebene(TsRechteck(0, 0, BILD_BREITE, BILD_HOEHE))
        l.fuellFarbe = s.timeBgColor
        l.fuelleRechteck(TsRechteck(0, 0, BILD_BREITE, BILD_HOEHE), 0, TsLeinwand.ECKEN_KEINE)
        zeichneLeiste()
        zeichneUhrzeit()
    }

    // --- clock_area.c -------------------------------------------------------------------

    private fun zeichneUhrzeit() {
        l.ebene(TsRechteck(0, 0, BILD_BREITE, BILD_HOEHE))
        val leco = s.clockFontId == TsSchriftwahl.LECO
        val (stundenSchrift, minutenSchrift) = when (s.clockFontId) {
            TsSchriftwahl.FETT -> r.sansFett to r.sansFett
            TsSchriftwahl.FETTE_STUNDE -> r.sansFett to r.sans
            TsSchriftwahl.FETTE_MINUTE -> r.sans to r.sansFett
            TsSchriftwahl.LECO -> r.leco to r.leco
            else -> r.sans to r.sans
        }
        val f = TsFctx(l, geglaettet = !leco)
        f.farbVerschiebung = 0
        f.fuellFarbe = s.timeColor
        val h = BILD_HOEHE
        var groesse = 4 * h / 7
        var abstand = h / 16
        var hVersatz = 0
        if (leco) {
            groesse = 4 * h / 7 + 6
            abstand = h / 20
            hVersatz = -4
        }
        if (s.sidebarOnLeft) hVersatz += leisteBreite / 2 else hVersatz -= leisteBreite / 2 + 1
        f.setzeGeviert(stundenSchrift, groesse)
        f.setzeGeviert(minutenSchrift, groesse)
        val stunde = TsTexte.stunde(z.stunde, z.vierundzwanzig, s.showLeadingZero)
        val minute = z.minute.toString().padStart(2, '0')
        f.setzeVersatz((BILD_BREITE / 2 + hVersatz) * 16, abstand * 16)
        f.zeichneText(stunde, stundenSchrift, ankerOben = true)
        f.setzeVersatz((BILD_BREITE / 2 + hVersatz) * 16, (h - abstand) * 16)
        f.zeichneText(minute, minutenSchrift, ankerOben = false)
        f.fuelle()
    }

    // --- sidebar.c ----------------------------------------------------------------------

    private fun automatischerAkku(): Boolean {
        if (s.disableAutobattery) return false
        val ohneAkkuWidget = TsWidget.AKKU !in s.widgets
        return ohneAkkuWidget && (z.akkuProzent <= 10 || z.laedt)
    }

    private fun ersetzbar(): Int {
        for (i in 0..2) if (s.widgets[i] == TsWidget.KEINS) return i
        for (i in 0..2) if (s.widgets[i] == TsWidget.WETTER || s.widgets[i] == TsWidget.VORHERSAGE) return i
        return 1
    }

    private fun zeichneLeiste() {
        val x = if (s.sidebarOnLeft) 0 else BILD_BREITE - leisteBreite
        l.ebene(TsRechteck(x, 0, leisteBreite, BILD_HOEHE))
        l.fuellFarbe = s.sidebarColor
        l.fuelleRechteck(TsRechteck(0, 0, leisteBreite, BILD_HOEHE), 0, TsLeinwand.ECKEN_KEINE)

        val trennung = s.activateDisconnectIcon && !z.verbunden
        val akku = automatischerAkku()
        val widgets = s.widgets.toMutableList()
        if (akku || trennung) {
            val i = ersetzbar()
            widgets[i] = if (akku) TsWidget.AKKU else TsWidget.BLUETOOTH
        }

        val schwelle = BILD_HOEHE - 9 * 2 - 3
        var abstand = 9
        kompakt = false
        var gesamt = hoehe(widgets[0]) + hoehe(widgets[1]) + hoehe(widgets[2])
        kompakt = gesamt > schwelle
        gesamt = hoehe(widgets[0]) + hoehe(widgets[1]) + hoehe(widgets[2])
        val mitteWeg = gesamt > schwelle
        gesamt = hoehe(widgets[0]) + hoehe(widgets[2])
        if (gesamt > schwelle) abstand = 4

        val oben = abstand
        val unten = BILD_HOEHE - abstand - hoehe(widgets[2])
        val mitte = ((unten - hoehe(widgets[1])) + (oben + hoehe(widgets[0]))) / 2
        zeichneWidget(widgets[0], oben)
        if (!mitteWeg) zeichneWidget(widgets[1], mitte)
        zeichneWidget(widgets[2], unten)
    }

    private fun hoehe(w: Int): Int = when (w) {
        TsWidget.AKKU -> if (z.laedt || !s.showBatteryPct) m.akkuNurSymbol else m.akkuMitProzent
        TsWidget.BLUETOOTH -> m.trennung
        TsWidget.DATUM -> if (kompakt) m.datumKompakt else m.datum
        TsWidget.ZEITZONE, TsWidget.WOCHE, TsWidget.UV, TsWidget.BEATS -> m.einfach
        TsWidget.SEKUNDEN -> m.sekunden
        TsWidget.WETTER -> m.wetter
        TsWidget.VORHERSAGE -> m.vorhersage
        TsWidget.SCHRITTE -> m.schritte
        TsWidget.SCHLAF -> m.schlaf
        TsWidget.PULS -> m.puls
        else -> 0
    }

    // --- sidebar_widgets.c --------------------------------------------------------------

    private fun text(t: String, schrift: TsSchrift, y: Int, farbe: Int = s.sidebarTextColor) {
        TsText.zeichne(l, t, schrift, TsRechteck(m.textX + xVersatz, y, m.textB, 20), TsText.Ausrichtung.Mitte, farbe)
    }

    private fun symbol(name: String, x: Int, y: Int, fuellung: Int = symbolFuellung, strich: Int = symbolStrich) {
        val bild = r.symbole[name] ?: error("Symbol $name fehlt")
        TsPdc.zeichne(l, bild, x, y, fuellung, strich)
    }

    private fun zeichneWidget(w: Int, y: Int) {
        when (w) {
            TsWidget.AKKU -> akku(y)
            TsWidget.BLUETOOTH -> symbol("DISCONNECTED", 3 + xVersatz, y)
            TsWidget.DATUM -> datum(y)
            TsWidget.WETTER -> wetter(y)
            TsWidget.VORHERSAGE -> vorhersage(y)
            TsWidget.WOCHE -> einfach(y, TsSprachen.WOCHE[s.languageId], TsTexte.woche(z.jahr, z.jahresTag, z.wochentag), m.einfachY)
            TsWidget.SEKUNDEN -> text(":" + z.sekunde.toString().padStart(2, '0'), gr, y + m.sekundenY)
            TsWidget.ZEITZONE -> einfach(
                y, s.altclockName,
                TsTexte.zweitzeit(z.stunde, z.minute, z.utcVersatzSekunden, s.altclockOffset, z.vierundzwanzig, s.showLeadingZero),
                m.einfachY - 1,
            )
            TsWidget.UV -> einfach(y, "UV", z.wetter?.uv?.toString() ?: "...", m.einfachY)
            TsWidget.SCHRITTE -> schritte(y)
            TsWidget.SCHLAF -> schlaf(y)
            TsWidget.PULS -> {
                symbol("HEALTH_HEART", 3 + xVersatz, y)
                text("${z.puls}", aktuelleSchrift, y + m.pulsY)
            }
            TsWidget.BEATS -> einfach(y, "@", "${z.beats}", m.einfachY - 1)
            else -> Unit
        }
    }

    private fun einfach(y: Int, label: String, wert: String, wertY: Int) {
        text(label, klein, y + m.einfachLabelY)
        text(wert, aktuelleSchrift, y + wertY)
    }

    private fun akku(y: Int) {
        val prozent = if (z.akkuProzent > 0) z.akkuProzent else 5
        val yy = y - 5
        symbol("BATTERY_BG", 3 + xVersatz, yy)
        if (z.laedt) {
            // das Blitzsymbol mit vertauschten Farben
            symbol("BATTERY_CHARGE", 3 + xVersatz, yy, fuellung = symbolStrich, strich = symbolFuellung)
        } else {
            val breite = (18 * prozent + 50) / 100
            l.fuellFarbe = if (prozent <= 20) 0xf0 else symbolStrich
            l.fuelleRechteck(TsRechteck(6 + xVersatz, 8 + yy, breite, 8), 0, TsLeinwand.ECKEN_KEINE)
        }
        if (s.showBatteryPct && !z.laedt) {
            TsText.zeichne(
                l, TsTexte.akku(prozent, gross, s.languageId), akkuSchrift,
                TsRechteck(m.textX + xVersatz, m.akkuTextY + yy, m.textB, 20), TsText.Ausrichtung.Mitte, s.sidebarTextColor,
            )
        }
    }

    private fun datum(yIn: Int) {
        val y = yIn - m.datumKorrektur
        text(TsSprachen.TAGE[s.languageId][z.wochentag], aktuelleSchrift, y)
        if (!gross) {
            symbol("DATE_BG_LG", m.datumBildX + xVersatz, y + m.datumBildY)
        } else {
            l.fuellFarbe = symbolStrich
            l.fuelleRechteck(TsRechteck(m.datumRechteckX + xVersatz, y + m.datumRechteckY, m.datumRechteckB, m.datumRechteckH), 2, TsLeinwand.ECKEN_ALLE)
            l.fuellFarbe = symbolFuellung
            l.fuelleRechteck(
                TsRechteck(m.datumRechteckX + 2 + xVersatz, y + m.datumRechteckY + 2, m.datumRechteckB - 4, m.datumRechteckH - 4),
                0, TsLeinwand.ECKEN_ALLE,
            )
        }
        text("${z.tag}", aktuelleSchrift, y + m.datumZahlY, farbe = symbolStrich)
        if (!kompakt) text(TsSprachen.MONATE[s.languageId][z.monat], aktuelleSchrift, y + m.datumMonatY)
    }

    private fun wetter(y: Int) {
        val w = z.wetter
        w?.symbol?.let { symbol(it.datei, 3 + xVersatz, y) }
        if (w?.temperatur != null) {
            text(TsTexte.temperatur(w.temperatur, gross), aktuelleSchrift, y + m.wetterTempY)
        } else {
            text("...", aktuelleSchrift, y)
        }
    }

    private fun vorhersage(y: Int) {
        val w = z.wetter
        w?.symbolHeute?.let { symbol(it.datei, 3 + xVersatz, y) }
        if (w?.hoch != null && w.tief != null) {
            l.fuellFarbe = s.sidebarTextColor
            text(TsTexte.temperatur(w.hoch, gross), aktuelleSchrift, y + m.vorhersageHochY)
            l.fuelleRechteck(TsRechteck(m.vorhersageStrichX + xVersatz, 8 + y + m.vorhersageStrichY, m.vorhersageStrichB, 1), 0, TsLeinwand.ECKEN_KEINE)
            text(TsTexte.temperatur(w.tief, gross), aktuelleSchrift, y + m.vorhersageTiefY)
        } else {
            text("...", aktuelleSchrift, y)
        }
    }

    private fun schritte(y: Int) {
        symbol("HEALTH_STEPS", 3 + xVersatz, y - 7)
        val (t, kleinSchrift) = if (s.healthUseDistance) {
            TsTexte.distanz(z.distanzMeter, z.metrisch, s.decimalSeparator)
        } else {
            TsTexte.schritte(z.schritte, s.decimalSeparator) to false
        }
        text(t, if (kleinSchrift) klein else mittel, y + m.schritteY)
    }

    private fun schlaf(y: Int) {
        symbol("HEALTH_SLEEP", 3 + xVersatz, y - 7)
        val sekunden = if (s.healthUseRestfulSleep) z.ruhigerSchlafSekunden else z.schlafSekunden
        val minuten = sekunden / 60
        text("${minuten / 60}h", mittel, y + m.schlafStundenY)
        text("${minuten % 60}m", klein, y + m.schlafMinutenY)
    }
}
