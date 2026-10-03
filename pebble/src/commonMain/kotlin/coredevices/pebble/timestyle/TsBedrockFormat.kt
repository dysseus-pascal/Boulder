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
 * Die Sprachen mit Firmware-ID, alphabetisch, English zuerst. NUR DIESE: den
 * eingebauten Schriften der Uhr fehlen die Zeichen der uebrigen Sprachen des
 * Originals (Griechisch, Kyrillisch, CJK ...), sie zeigten Kaestchen.
 */
val TS_SPRACHEN: List<Pair<Int, String>> = listOf(
    0 to "English", 19 to "Basque", 15 to "Catalan", 25 to "Croatian", 7 to "Czech", 21 to "Danish",
    5 to "Dutch", 18 to "Estonian", 20 to "Finnish", 1 to "French", 33 to "Galician", 2 to "German",
    24 to "Hungarian", 30 to "Indonesian", 26 to "Irish", 4 to "Italian", 27 to "Latvian",
    22 to "Lithuanian", 16 to "Norwegian", 11 to "Polish", 8 to "Portuguese", 14 to "Romanian",
    28 to "Serbian", 12 to "Slovak", 23 to "Slovenian", 3 to "Spanish", 10 to "Swedish",
    6 to "Turkish", 32 to "Welsh",
)

private val ERLAUBTE_SPRACHEN: Set<Int> = TS_SPRACHEN.map { it.first }.toSet()

fun tsSpracheErlaubt(id: Int): Boolean = id in ERLAUBTE_SPRACHEN

/**
 * Alle Einstellungen, Namen wie die Felder von Settings in der Firmware
 * (timestyle/settings.h). Farben sind GColor8-Bytes (aarrggbb, 0..255).
 * [clockFontId] und [fassung] zeigt die Seite nicht: die Firmware hat nur
 * die LECO-Schrift, und die Fassung sagt nur, was gelesen wurde.
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

    companion object {
        /** Die einzige Schrift der Firmware. */
        const val LECO = 1

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
        clockFontId = TsBedrockEinstellungen.LECO,
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
 * Schreibt immer Fassung 1, reservierte Felder 0, LECO und nur gueltige
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
    setze(6, TsBedrockEinstellungen.LECO)
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
