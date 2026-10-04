package coredevices.pebble.timestyle

/*
 * DIE FARBVORLAGEN VON TIMESTYLE: die 77 Vorlagen der Konfigseite des
 * Originals (config_theme_color.html, TimeStylePebble gh-pages b8e45d4) in
 * ihrer Reihenfolge und mit ihren Namen, dazu die eigenen Vorlagen, die der
 * Nutzer speichern und loeschen kann (im Original im localStorage der Seite).
 *
 * Eine Vorlage setzt nur die vier Farbbytes. Fehlt im Original die Farbe der
 * Leistenschrift, nimmt seine Seite Schwarz (config.js, presetSelected) -
 * darum steht dort 0xc0.
 */

/** Eine Farbvorlage; die Farben als GColor8 (aarrggbb). */
data class TsFarbvorlage(
    val gruppe: String,
    val name: String,
    val zeit: Int,
    val hintergrund: Int,
    val leiste: Int,
    val leistentext: Int,
)

/** Die vier Farben einer Vorlage in den Satz uebernehmen; alles andere bleibt. */
fun TsBedrockEinstellungen.mitVorlage(v: TsFarbvorlage): TsBedrockEinstellungen =
    copy(timeColor = v.zeit, timeBgColor = v.hintergrund, sidebarColor = v.leiste, sidebarTextColor = v.leistentext)

/** Die Farben des Satzes als (namenlose) eigene Vorlage. */
fun TsBedrockEinstellungen.alsVorlage(): TsFarbvorlage =
    TsFarbvorlage(TS_EIGENE_GRUPPE, "", timeColor, timeBgColor, sidebarColor, sidebarTextColor)

/** Die Gruppe der eigenen Vorlagen. */
const val TS_EIGENE_GRUPPE = "Saved"

/** Die 77 Vorlagen des Originals, Gruppe fuer Gruppe. */
val TS_FARBVORLAGEN: List<TsFarbvorlage> = listOf(
    TsFarbvorlage("Neon", "Orange Dreams", 0xf4, 0xc0, 0xf4, 0xc0),
    TsFarbvorlage("Neon", "Blue Screen", 0xcf, 0xc0, 0xcf, 0xc0),
    TsFarbvorlage("Neon", "Terminal Green", 0xcc, 0xc0, 0xcc, 0xc0),
    TsFarbvorlage("Neon", "Ultra Violet", 0xe3, 0xc0, 0xe3, 0xc0),
    TsFarbvorlage("Neon", "Pretty in Pink", 0xf2, 0xc0, 0xf2, 0xc0),
    TsFarbvorlage("Neon", "Red Velvet", 0xf0, 0xc0, 0xf0, 0xc0),
    TsFarbvorlage("Contrast", "Timeline-Past", 0xc0, 0xff, 0xf8, 0xc0),
    TsFarbvorlage("Contrast", "Timeline-Future", 0xc0, 0xff, 0xcb, 0xc0),
    TsFarbvorlage("Contrast", "Dark Past", 0xff, 0xc0, 0xf8, 0xc0),
    TsFarbvorlage("Contrast", "Dark Future", 0xff, 0xc0, 0xcb, 0xc0),
    TsFarbvorlage("Contrast", "Lime", 0xc0, 0xff, 0xcc, 0xc0),
    TsFarbvorlage("Contrast", "Sangria", 0xc0, 0xff, 0xe0, 0xff),
    TsFarbvorlage("Dark", "Shades of Grey", 0xff, 0xd5, 0xea, 0xc0),
    TsFarbvorlage("Dark", "Deep Blue", 0xff, 0xc2, 0xc7, 0xff),
    TsFarbvorlage("Dark", "Evergreen", 0xff, 0xc4, 0xd9, 0xc0),
    TsFarbvorlage("Dark", "Cabernet", 0xff, 0xf0, 0xe0, 0xff),
    TsFarbvorlage("Dark", "Complementary", 0xff, 0xd3, 0xfc, 0xc0),
    TsFarbvorlage("Dark", "Autumn", 0xff, 0xd0, 0xf4, 0xc0),
    TsFarbvorlage("Solid", "Flame", 0xff, 0xf0, 0xff, 0xc0),
    TsFarbvorlage("Solid", "Plain Blue", 0xff, 0xc2, 0xff, 0xc0),
    TsFarbvorlage("Solid", "Emerald", 0xff, 0xc4, 0xff, 0xc0),
    TsFarbvorlage("Solid", "Indigo", 0xff, 0xd2, 0xff, 0xc0),
    TsFarbvorlage("Solid", "Quartz", 0xff, 0xf2, 0xff, 0xc0),
    TsFarbvorlage("Solid", "Merlot", 0xff, 0xe1, 0xff, 0xc0),
    TsFarbvorlage("Pastel", "Into the Deep", 0xc0, 0xdb, 0xc6, 0xc0),
    TsFarbvorlage("Pastel", "Chardonnay", 0xc0, 0xfc, 0xe8, 0xc0),
    TsFarbvorlage("Pastel", "Tree of Time", 0xc0, 0xcc, 0xc8, 0xc0),
    TsFarbvorlage("Pastel", "Rosé", 0xc0, 0xfb, 0xf6, 0xc0),
    TsFarbvorlage("Pastel", "Sunset", 0xc0, 0xf8, 0xf4, 0xd0),
    TsFarbvorlage("Pastel", "Twilight", 0xc0, 0xeb, 0xd7, 0xc0),
    TsFarbvorlage("User Submissions", "Washington", 0xff, 0xc2, 0xe0, 0xff),
    TsFarbvorlage("User Submissions", "Highway", 0xff, 0xc1, 0xf8, 0xc0),
    TsFarbvorlage("User Submissions", "Dashboard", 0xf9, 0xc0, 0xd7, 0xc0),
    TsFarbvorlage("User Submissions", "Underwater", 0xef, 0xc1, 0xd7, 0xc0),
    TsFarbvorlage("User Submissions", "Algae", 0xcc, 0xc2, 0xcc, 0xc6),
    TsFarbvorlage("User Submissions", "Wetland", 0xcc, 0xc3, 0xf3, 0xc0),
    TsFarbvorlage("User Submissions", "Leather", 0xff, 0xd4, 0xea, 0xc0),
    TsFarbvorlage("User Submissions", "Salmon", 0xfa, 0xd0, 0xfa, 0xc0),
    TsFarbvorlage("User Submissions", "Clover", 0xee, 0xc4, 0xee, 0xc0),
    TsFarbvorlage("User Submissions", "Reggae", 0xfc, 0xf0, 0xc8, 0xff),
    TsFarbvorlage("User Submissions", "Gold Steel", 0xfc, 0xe0, 0xfc, 0xc0),
    TsFarbvorlage("User Submissions", "Unconcerned", 0xff, 0xc3, 0xf4, 0xc0),
    TsFarbvorlage("User Submissions", "Meadow", 0xc0, 0xc8, 0xc6, 0xff),
    TsFarbvorlage("User Submissions", "Flight", 0xf0, 0xff, 0xc3, 0xff),
    TsFarbvorlage("User Submissions", "Country", 0xff, 0xc8, 0xf8, 0xc0),
    TsFarbvorlage("User Submissions", "Grape", 0xff, 0xd1, 0xe7, 0xc0),
    TsFarbvorlage("User Submissions", "Mascot", 0xd2, 0xf8, 0xd2, 0xf8),
    TsFarbvorlage("User Submissions", "Aeon", 0xf4, 0xc1, 0xfc, 0xc1),
    TsFarbvorlage("User Submissions", "Stripe", 0xff, 0xc0, 0xf0, 0xff),
    TsFarbvorlage("User Submissions", "Gray Skies", 0xea, 0xc0, 0xea, 0xc0),
    TsFarbvorlage("User Submissions", "Allure", 0xcc, 0xd0, 0xcf, 0xc0),
    TsFarbvorlage("User Submissions", "Brass", 0xfc, 0xc0, 0xfc, 0xc0),
    TsFarbvorlage("User Submissions", "Sky", 0xc3, 0xcf, 0xcf, 0xc3),
    TsFarbvorlage("User Submissions", "Antique", 0xe9, 0xc0, 0xe9, 0xc0),
    TsFarbvorlage("User Submissions", "BlueGreen", 0xcc, 0xc0, 0xc3, 0xec),
    TsFarbvorlage("User Submissions", "Time Machine", 0xc0, 0xff, 0xc2, 0xff),
    TsFarbvorlage("User Submissions", "Phthalo", 0xcc, 0xc1, 0xee, 0xc1),
    TsFarbvorlage("User Submissions", "Xmas", 0xff, 0xe0, 0xc8, 0xc0),
    TsFarbvorlage("User Submissions", "Parra", 0xfc, 0xc2, 0xfc, 0xc2),
    TsFarbvorlage("User Submissions", "Red Steel", 0xff, 0xf0, 0xd5, 0xff),
    TsFarbvorlage("User Submissions", "Amber", 0xc0, 0xf8, 0xc0, 0xf8),
    TsFarbvorlage("User Submissions", "HUD", 0xd8, 0xc0, 0xc0, 0xdf),
    TsFarbvorlage("User Submissions", "Night Vision", 0xf0, 0xc0, 0xc0, 0xf0),
    TsFarbvorlage("User Submissions", "Champagne", 0xe3, 0xfb, 0xfe, 0xc0),
    TsFarbvorlage("User Submissions", "Blue Line", 0xc7, 0xc0, 0xc7, 0xc0),
    TsFarbvorlage("User Submissions", "Cotton Candy", 0xf9, 0xc7, 0xf5, 0xc0),
    TsFarbvorlage("User Submissions", "Sepia", 0xd4, 0xfe, 0xd4, 0xfe),
    TsFarbvorlage("User Submissions", "Meghamin", 0xcf, 0xc3, 0xcf, 0xc3),
    TsFarbvorlage("User Submissions", "Watermelon", 0xc0, 0xf1, 0xc8, 0xc0),
    TsFarbvorlage("User Submissions", "Crash", 0xc0, 0xec, 0xd2, 0xec),
    TsFarbvorlage("User Submissions", "Red Plastic", 0xff, 0xf0, 0xf0, 0xff),
    TsFarbvorlage("User Submissions", "Greenstripe", 0xc1, 0xff, 0xc9, 0xc0),
    TsFarbvorlage("User Submissions", "True Blue", 0xc2, 0xdb, 0xc2, 0xff),
    TsFarbvorlage("User Submissions", "Command", 0xff, 0xc0, 0xc3, 0xff),
    TsFarbvorlage("User Submissions", "Silver & Gold", 0xfe, 0xd5, 0xfe, 0xd5),
    TsFarbvorlage("User Submissions", "Jester", 0xf0, 0xd2, 0xc8, 0xff),
    TsFarbvorlage("User Submissions", "Pika Stripe", 0xf0, 0xfc, 0xd4, 0xff),
)

/*
 * Die eigenen Vorlagen als Text fuer die Einstellungen des Telefons: je Vorlage
 * acht Hex-Ziffern (Zeit, Hintergrund, Leiste, Leistentext), durch Kommas
 * getrennt, in der Reihenfolge des Speicherns. Ein kaputter Eintrag faellt
 * beim Lesen weg, statt die ganze Liste zu verlieren.
 */

/** Die eigenen Vorlagen als Text. */
fun tsEigeneAlsText(liste: List<TsFarbvorlage>): String =
    liste.joinToString(",") { v -> listOf(v.zeit, v.hintergrund, v.leiste, v.leistentext).joinToString("") { it.toString(16).padStart(2, '0') } }

/** Die eigenen Vorlagen aus dem Text; null oder leer = keine. */
fun tsEigeneAusText(text: String?): List<TsFarbvorlage> =
    text.orEmpty().split(",").mapNotNull { teil ->
        if (teil.length != 8) return@mapNotNull null
        val f = (0 until 4).map { teil.substring(2 * it, 2 * it + 2).toIntOrNull(16) ?: return@mapNotNull null }
        // Wie die Firmware: jede Zahl deckend (eine der 64 Farben).
        TsFarbvorlage(TS_EIGENE_GRUPPE, "", f[0] or 0xc0, f[1] or 0xc0, f[2] or 0xc0, f[3] or 0xc0)
    }

/** Speichern: hinten anhaengen, eine schon gespeicherte gleiche Vorlage nicht doppelt. */
fun List<TsFarbvorlage>.mitEigener(neu: TsFarbvorlage): List<TsFarbvorlage> =
    if (any { it.gleicheFarben(neu) }) this else this + neu

/** Loeschen: die Vorlage an [stelle] faellt weg. */
fun List<TsFarbvorlage>.ohneStelle(stelle: Int): List<TsFarbvorlage> = filterIndexed { i, _ -> i != stelle }

/** Ob zwei Vorlagen dieselben vier Farben haben (Name und Gruppe zaehlen nicht). */
fun TsFarbvorlage.gleicheFarben(andere: TsFarbvorlage): Boolean =
    zeit == andere.zeit && hintergrund == andere.hintergrund && leiste == andere.leiste && leistentext == andere.leistentext
