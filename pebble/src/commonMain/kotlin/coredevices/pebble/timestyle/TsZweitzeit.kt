package coredevices.pebble.timestyle

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.asTimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/**
 * Der Text des Widgets "Time zone" zur Beispielzeit der Vorschau, GENAU WIE
 * DIE UHR ihn rechnet (timestyle/zweitzeit.c): ueber UTC auf die Minute -
 * sonst laege die Vorschau in Zonen mit halben Stunden (Indien,
 * Neufundland) eine Stunde neben der Uhr. Die Zone hier gilt an [tag],
 * also mit der Sommerzeit dieses Tages.
 */
fun tsZonenText(
    tag: LocalDate,
    hier: TimeZone,
    versatzStunden: Int,
    vierundzwanzig: Boolean,
    fuehrendeNull: Boolean,
    stunde: Int = 9,
    minute: Int = 41,
): String {
    val dort = UtcOffset(hours = versatzStunden).asTimeZone()
    val h = tag.atTime(stunde, minute).toInstant(hier).toLocalDateTime(dort).hour
    // Ab hier wie zweitzeit_text: 24 h als Zahl, sonst 1..12 mit a/p.
    if (vierundzwanzig) return if (fuehrendeNull) h.toString().padStart(2, '0') else h.toString()
    val zwoelf = if (h % 12 == 0) 12 else h % 12
    val zahl = if (fuehrendeNull) zwoelf.toString().padStart(2, '0') else zwoelf.toString()
    return zahl + if (h < 12) "a" else "p"
}
