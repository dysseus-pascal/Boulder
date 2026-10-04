package coredevices.pebble.timestyle

import io.rebble.libpebblecommon.database.entity.alsHex
import io.rebble.libpebblecommon.database.entity.hexAlsBytes

/*
 * DIE EINSTELLUNGEN VON TIMESTYLE BEDROCK als ein Satz von 32 Byte - so
 * gehen sie ueber die Uhreinstellungen-Synchronisation (Schluessel
 * "tsBedrock", BytesWatchPref) zur Uhr. Dasselbe Format liest die Firmware
 * in timestyle/ts_format.c; Bytes, Pruefregeln und die Testvektoren sind
 * mit ihr abgesprochen und werden nicht einseitig geaendert.
 *
 * Byte 0 ist die Fassung (0 = nicht gesetzt). Eine hoehere Fassung bleibt
 * lesbar: reservierte Bits und Bytes werden beim Lesen uebergangen. Ein
 * ungueltiger Wert faellt auf die Vorgabe NUR SEINES Feldes zurueck.
 */

/** Die Fassung, die Boulder schreibt. */
const val TS_FASSUNG = 1

/** Laenge des Satzes - jede andere Laenge gilt als "nicht gesetzt". */
const val TS_LAENGE = 32

/** Der Name der zweiten Zeitzone: hoechstens so viele Zeichen in der Eingabe ... */
const val TS_NAME_ZEICHEN = 4

/** ... und so viele UTF-8-Bytes im Satz (Byte 16..22, Byte 23 bleibt 0). */
const val TS_NAME_BYTES = 7

/** Der Versatz der zweiten Zeitzone in ganzen Stunden. */
val TS_VERSAETZE = -12..14

/** Widget-IDs (SidebarWidgetType der Firmware). */
object TsWidget {
    const val KEINS = 0
    // 1 ist die Bluetooth-Anzeige: sie erscheint auf der Uhr von selbst und ist nicht waehlbar.
    const val BLUETOOTH = 1
    const val AKKU = 2
    const val ZEITZONE = 3
    const val DATUM = 4
    const val SEKUNDEN = 5
    const val WOCHE = 6
    const val WETTER = 7
    const val VORHERSAGE = 8
    const val SCHLAF = 9
    const val SCHRITTE = 10
    const val BEATS = 11
    const val PULS = 12
    const val UV = 13
}

/** Die waehlbaren Widgets in der Reihenfolge der Auswahlliste. */
val TS_WIDGETS: List<Int> = with(TsWidget) {
    listOf(KEINS, DATUM, WETTER, VORHERSAGE, UV, SCHRITTE, SCHLAF, PULS, AKKU, ZEITZONE, SEKUNDEN, WOCHE, BEATS)
}

/**
 * Alle 37 Sprachen des Originals mit Firmware-ID, alphabetisch, English zuerst.
 * Die Firmware nimmt sie alle ab b24 an (bis b23 nur die 29 ohne Sprachpaket).
 */
val TS_SPRACHEN: List<Pair<Int, String>> = listOf(
    0 to "English", 19 to "Basque", 15 to "Catalan", 29 to "Chinese", 25 to "Croatian", 7 to "Czech",
    21 to "Danish", 5 to "Dutch", 18 to "Estonian", 20 to "Finnish", 1 to "French", 33 to "Galician",
    2 to "German", 9 to "Greek", 36 to "Hebrew", 24 to "Hungarian", 30 to "Indonesian", 26 to "Irish",
    4 to "Italian", 34 to "Japanese", 35 to "Korean", 27 to "Latvian", 22 to "Lithuanian",
    16 to "Norwegian", 11 to "Polish", 8 to "Portuguese", 14 to "Romanian", 17 to "Russian",
    28 to "Serbian", 12 to "Slovak", 23 to "Slovenian", 3 to "Spanish", 10 to "Swedish", 6 to "Turkish",
    31 to "Ukrainian", 13 to "Vietnamese", 32 to "Welsh",
)

/**
 * DIE ACHT SPRACHEN MIT SPRACHPAKET: ihre Zeichen (Griechisch, Kyrillisch,
 * CJK, Hangul, Hebraeisch, das "ầ" von "Tuần") stehen nicht in den
 * eingebauten Schriften der Uhr, sondern nur in der Erweiterung eines
 * Sprachpakets - ohne passendes Paket zeigt die Uhr Ersatzkaestchen. Je
 * Sprache die ISO-Anfaenge der Pakete, die die Zeichen mitbringen. Welche
 * Zeichen ein Paket wirklich hat, sieht Boulder nicht; massgeblich ist die
 * Schrift des Pakets (Kyrillisch: ru und bg, Chinesisch: zh und en_CN/en_TW).
 */
val TS_SPRACHPAKET: Map<Int, List<String>> = mapOf(
    9 to listOf("el"),
    13 to listOf("vi"),
    17 to listOf("ru", "bg"),
    29 to listOf("zh", "en_CN", "en_TW"),
    31 to listOf("uk"),
    34 to listOf("ja"),
    35 to listOf("ko"),
    36 to listOf("he"),
)

/** Bis b23 nahm die Firmware nur diese 29 Sprachen an (die anderen wurden Englisch). */
val TS_SPRACHEN_BIS_B23: Set<Int> = (0..36).toSet() - TS_SPRACHPAKET.keys

/** Ob [id] eine Sprache des Formats ist (0..36). */
fun tsSpracheErlaubt(id: Int): Boolean = id in 0..36

/**
 * Ob die Uhr fuer [id] ein Sprachpaket braucht, das sie nicht hat. [paket] ist
 * die ISO-Kennung des installierten Pakets, wie die Uhr sie meldet (Kopfzeile
 * "Language:" des Pakets, i18n.c), null ohne Paket. Die Pakete der Liste
 * schreiben "ru_RU", "bg", "en_CN"; WARUM trotzdem auch "ru-RU" und
 * Gross/Klein: die Kennung kommt aus dem Paket, nicht aus Boulder - ein Paket
 * mit Bindestrich soll nicht faelschlich als "fehlt" gelten. Nur Sprache
 * oder Sprache plus Land zaehlt: "kok_IN" ist kein "ko".
 */
fun tsBrauchtSprachpaket(id: Int, paket: String?): Boolean {
    val passend = TS_SPRACHPAKET[id] ?: return false
    if (paket == null) return true
    val p = paket.trim().replace('-', '_').lowercase()
    return passend.map { it.lowercase() }.none { p == it || p.startsWith(it + "_") }
}

/**
 * Alle Einstellungen, Namen wie die Felder von Settings in der Firmware
 * (timestyle/settings.h). Farben sind GColor8-Bytes (aarrggbb, 0..255).
 * [clockFontId] wie FONT_SETTING_* (TsSchriftwahl); [fassung] sagt nur, was
 * gelesen wurde.
 */
data class TsBedrockEinstellungen(
    val showLeadingZero: Boolean,
    val hourlyVibe: Int,
    val btVibe: Boolean,
    val sidebarOnLeft: Boolean,
    val useLargeFonts: Boolean,
    val activateDisconnectIcon: Boolean,
    val widgets: List<Int>,
    val showBatteryPct: Boolean,
    val disableAutobattery: Boolean,
    val altclockName: String,
    val altclockOffset: Int,
    val healthUseDistance: Boolean,
    val decimalSeparator: Char,
    val healthUseRestfulSleep: Boolean,
    val timeColor: Int,
    val timeBgColor: Int,
    val sidebarColor: Int,
    val sidebarTextColor: Int,
    val languageId: Int,
    val clockFontId: Int = LECO,
    val fassung: Int = TS_FASSUNG,
) {
    /** Ob eines der drei Widgets [widget] ist - danach richten sich die bedingten Zeilen. */
    fun zeigt(widget: Int): Boolean = widget in widgets

    /**
     * [widget] an [platz] (0 oben, 1 Mitte, 2 unten) setzen - WIE DAS ORIGINAL
     * (config.js, Auswahl eines Widgets): steht es schon an einem anderen
     * Platz, wird dieser leer. So kommt kein Widget doppelt vor. Ein schon
     * gespeicherter Satz mit Doppelten bleibt, wie er ist (das Original prueft
     * auch nur beim Aendern), die Uhr zeigt ihn weiter.
     */
    fun mitWidget(platz: Int, widget: Int): TsBedrockEinstellungen = copy(
        widgets = widgets.mapIndexed { i, w ->
            when {
                i == platz -> widget
                widget != TsWidget.KEINS && w == widget -> TsWidget.KEINS
                else -> w
            }
        },
    )

    companion object {
        /** Die Schrift der Vorgabe (und die einzige bis b23). */
        const val LECO = 1

        /** Die Schriften 0..4 wie im Original (TsSchriftwahl). */
        val SCHRIFTEN = 0..4

        /** Die Vorgabe der Uhr = das Nutzerfoto: weisse LECO-Ziffern auf schwarz, weisse Leiste rechts. */
        val VORGABE = TsBedrockEinstellungen(
            showLeadingZero = false,
            hourlyVibe = 0,
            btVibe = false,
            sidebarOnLeft = false,
            useLargeFonts = false,
            activateDisconnectIcon = true,
            widgets = listOf(TsWidget.WETTER, TsWidget.SCHRITTE, TsWidget.DATUM),
            showBatteryPct = true,
            disableAutobattery = false,
            altclockName = "ALT",
            altclockOffset = 0,
            healthUseDistance = false,
            decimalSeparator = '.',
            healthUseRestfulSleep = false,
            timeColor = 0xFF,
            timeBgColor = 0xC0,
            sidebarColor = 0xFF,
            sidebarTextColor = 0xC0,
            languageId = 0,
        )
    }
}

private const val DECKEND = 0xC0

private fun Boolean.bit(n: Int): Int = if (this) 1 shl n else 0

private fun Int.bit(n: Int): Boolean = (this shr n) and 1 == 1

/**
 * Liest einen Satz nach den gemeinsamen Regeln. Das Ergebnis ist IMMER
 * vollstaendig und gueltig: falsche Laenge oder Fassung 0 ergibt die
 * Vorgabe, ein ungueltiges Feld die Vorgabe dieses Feldes.
 */
fun dekodieren(bytes: ByteArray): TsBedrockEinstellungen {
    val v = TsBedrockEinstellungen.VORGABE
    if (bytes.size != TS_LAENGE || bytes[0].toInt() == 0) return v
    val b = IntArray(TS_LAENGE) { bytes[it].toInt() and 0xFF }
    val a = b[13]
    val f = b[14]
    // Byte 23 gilt als 0, auch wenn es das nicht ist: der Name hat hoechstens 7 Bytes.
    val nameBytes = bytes.copyOfRange(16, 23)
    val nameEnde = nameBytes.indexOf(0).takeIf { it >= 0 } ?: nameBytes.size
    val versatz = bytes[11].toInt()
    return TsBedrockEinstellungen(
        // Jede Farbe wird deckend gemacht - einen ungueltigen Farbwert gibt es so nicht.
        timeColor = b[1] or DECKEND,
        timeBgColor = b[2] or DECKEND,
        sidebarColor = b[3] or DECKEND,
        sidebarTextColor = b[4] or DECKEND,
        languageId = b[5].takeIf { tsSpracheErlaubt(it) } ?: v.languageId,
        clockFontId = b[6].takeIf { it in TsBedrockEinstellungen.SCHRIFTEN } ?: v.clockFontId,
        hourlyVibe = b[7].takeIf { it <= 2 } ?: v.hourlyVibe,
        widgets = (0..2).map { i -> b[8 + i].takeIf { it != TsWidget.BLUETOOTH && it <= TsWidget.UV } ?: v.widgets[i] },
        altclockOffset = versatz.takeIf { it in TS_VERSAETZE } ?: v.altclockOffset,
        decimalSeparator = b[12].toChar().takeIf { it == '.' || it == ',' } ?: v.decimalSeparator,
        showLeadingZero = a.bit(0),
        btVibe = a.bit(1),
        sidebarOnLeft = a.bit(2),
        useLargeFonts = a.bit(3),
        activateDisconnectIcon = a.bit(4),
        // Bit 5 war useMetric: die Temperatur kommt schon in Boulders Einheit, das Bit bleibt frei.
        showBatteryPct = a.bit(6),
        disableAutobattery = a.bit(7),
        healthUseDistance = f.bit(0),
        healthUseRestfulSleep = f.bit(1),
        // Kaputtes UTF-8 wird nicht repariert, nur mit Ersatzzeichen gezeigt.
        altclockName = nameBytes.copyOfRange(0, nameEnde).decodeToString(),
        fassung = b[0],
    )
}

/**
 * Schreibt immer Fassung 1, reservierte Felder 0 und nur gueltige
 * Werte - auch aus einem Satz, der von Hand oder von einer neueren Uhr kam.
 * Fuer jeden gueltigen Satz gilt kodieren(dekodieren(x)) == x.
 */
fun TsBedrockEinstellungen.kodieren(): ByteArray {
    val v = TsBedrockEinstellungen.VORGABE
    val b = ByteArray(TS_LAENGE)
    fun setze(i: Int, wert: Int) { b[i] = wert.toByte() }
    setze(0, TS_FASSUNG)
    setze(1, timeColor or DECKEND)
    setze(2, timeBgColor or DECKEND)
    setze(3, sidebarColor or DECKEND)
    setze(4, sidebarTextColor or DECKEND)
    setze(5, languageId.takeIf { tsSpracheErlaubt(it) } ?: v.languageId)
    setze(6, clockFontId.takeIf { it in TsBedrockEinstellungen.SCHRIFTEN } ?: v.clockFontId)
    setze(7, hourlyVibe.takeIf { it in 0..2 } ?: v.hourlyVibe)
    for (i in 0..2) {
        val w = widgets.getOrNull(i)
        setze(8 + i, w?.takeIf { it in TS_WIDGETS } ?: v.widgets[i])
    }
    setze(11, altclockOffset.takeIf { it in TS_VERSAETZE } ?: v.altclockOffset)
    setze(12, (decimalSeparator.takeIf { it == '.' || it == ',' } ?: v.decimalSeparator).code)
    setze(
        13,
        showLeadingZero.bit(0) or btVibe.bit(1) or sidebarOnLeft.bit(2) or useLargeFonts.bit(3) or
            activateDisconnectIcon.bit(4) or showBatteryPct.bit(6) or disableAutobattery.bit(7),
    )
    setze(14, healthUseDistance.bit(0) or healthUseRestfulSleep.bit(1))
    tsNameBytes(altclockName).copyInto(b, 16)
    return b
}

/**
 * Der Name, wie er in den Satz kommt: nur Zeichen, die die Uhr zeichnen kann,
 * und hoechstens 7 Bytes, ohne ein Zeichen zu zerschneiden. Ein Name von der
 * Uhr mit Ersatzzeichen verliert diese hier.
 */
private fun tsNameBytes(name: String): ByteArray {
    var bytes = ByteArray(0)
    for (zeichen in name.filter(::tsZeichenErlaubt)) {
        val mit = bytes + zeichen.toString().encodeToByteArray()
        if (mit.size > TS_NAME_BYTES) break
        bytes = mit
    }
    return bytes
}

/** Ob die Schriften der Uhr das Zeichen haben (ASCII und Latin-1 bis Latin Extended-A). */
fun tsZeichenErlaubt(c: Char): Boolean = c in ' '..'~' || c in ' '..'ſ'

/** Die Eingabe des Namens: nur erlaubte Zeichen - sonst wird sie abgewiesen (null). */
fun tsNameEingabe(text: String): String? {
    if (!text.all(::tsZeichenErlaubt)) return null
    // Auf 4 Zeichen, und so, dass die 7 Bytes des Satzes reichen (Umlaute zaehlen doppelt).
    var name = text.take(TS_NAME_ZEICHEN)
    while (name.encodeToByteArray().size > TS_NAME_BYTES) name = name.dropLast(1)
    return name
}

/** Der Satz als Hex, wie er in der Datenbank steht. */
fun TsBedrockEinstellungen.alsHex(): String = kodieren().alsHex()

/** Aus der Datenbank: fehlt der Eintrag oder ist er kein Hex, gilt die Vorgabe. */
fun tsAusHex(hex: String?): TsBedrockEinstellungen =
    hex?.hexAlsBytes()?.let(::dekodieren) ?: TsBedrockEinstellungen.VORGABE

/**
 * Was die Uhr mit Firmware-Build [build] aus diesem Satz macht. b21 und b22
 * nehmen gar keine Einstellungen an - sie zeigen ihre feste Einstellung, und
 * die ist VORGABE (settings.c von b22, Wert fuer Wert). b23 las die Schrift
 * nicht (immer LECO) und machte die acht Sprachen mit Sprachpaket zu Englisch.
 * Die Vorschau soll zeigen, was die Uhr zeigt, nicht was gewaehlt ist.
 * [build] null = unbekannt: DIESELBE REGEL WIE UEBERALL (tsBedrockFaehigkeit),
 * also nimmt sie keine Einstellungen an - VORGABE, nicht die neueste Firmware.
 */
fun TsBedrockEinstellungen.wieDieUhr(build: Int?): TsBedrockEinstellungen {
    val faehigkeit = tsBedrockFaehigkeit(build)
    if (!faehigkeit.einstellungen) return TsBedrockEinstellungen.VORGABE
    if (faehigkeit.schriften) return this
    return copy(
        clockFontId = TsBedrockEinstellungen.LECO,
        languageId = languageId.takeIf { it in TS_SPRACHEN_BIS_B23 } ?: 0,
    )
}
