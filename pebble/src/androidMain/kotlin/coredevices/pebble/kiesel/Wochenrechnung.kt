package coredevices.pebble.kiesel

import java.time.DayOfWeek
import java.time.LocalDate

/** Ein Training, auf das reduziert, was die Wochenbilder brauchen. */
internal data class Einheit(val tag: LocalDate, val sport: Sport, val minuten: Long)

/** Ein Trainingstag im Kalender: seine Arten, die laengste zuerst, und die Minuten. */
internal data class Trainingstag(val arten: List<Sport>, val minuten: Long)

/** Eine Woche im Stapel: ihr Montag und die Minuten je Art, in fester Reihenfolge. */
internal data class Trainingswoche(val montag: LocalDate, val teile: List<Pair<Sport, Long>>) {
    val minuten: Long get() = teile.sumOf { it.second }
}

/** Eine Art im Vierteljahr: wie oft, wie lange und welcher Anteil an der Zeit. */
internal data class Anteil(val sport: Sport, val anzahl: Int, val minuten: Long, val prozent: Int)

/**
 * Die Rechnung hinter Kalender, Wochenstapel und Verteilung - wie in
 * Kiesel-Helper (TrainingBilder), aber ohne Zeichnen, damit sie sich pruefen
 * laesst.
 */
internal object Wochenrechnung {

    /** Die Tage mit Training, fuer den Vierwochenkalender. */
    fun tage(einheiten: List<Einheit>): Map<LocalDate, Trainingstag> =
        einheiten.groupBy { it.tag }.mapValues { (_, liste) ->
            val arten = liste.groupBy { it.sport }
                .map { (art, e) -> art to e.sumOf { it.minuten } }
                .sortedByDescending { it.second }
                .map { it.first }
            Trainingstag(arten, liste.sumOf { it.minuten })
        }

    /**
     * Die letzten [anzahl] Wochen bis einschliesslich der laufenden, aelteste
     * zuerst. DIE STAPELREIHENFOLGE IST FEST (die der Aufzaehlung), nicht nach
     * Groesse: so liegt Laufen jede Woche an derselben Stelle, und das Auge
     * vergleicht Schichten statt sie zu suchen.
     */
    fun wochen(einheiten: List<Einheit>, heute: LocalDate, anzahl: Int = 8): List<Trainingswoche> {
        val diese = heute.with(DayOfWeek.MONDAY)
        return (anzahl - 1 downTo 0).map { diese.minusWeeks(it.toLong()) }.map { montag ->
            val drin = einheiten.filter { !it.tag.isBefore(montag) && it.tag.isBefore(montag.plusWeeks(1)) }
            val teile = Sport.entries.mapNotNull { art ->
                drin.filter { it.sport == art }.sumOf { it.minuten }.takeIf { it > 0 }?.let { art to it }
            }
            Trainingswoche(montag, teile)
        }
    }

    /**
     * Woraus die Zeit bestand, die groesste Art zuerst. Die Prozente sind
     * gerundet und muessen nicht 100 ergeben - sie stehen nur in der Legende.
     */
    fun verteilung(einheiten: List<Einheit>): List<Anteil> {
        val gesamt = einheiten.sumOf { it.minuten }
        if (gesamt <= 0) return emptyList()
        return einheiten.groupBy { it.sport }
            .map { (art, e) -> art to e }
            .filter { (_, e) -> e.sumOf { it.minuten } > 0 }
            .map { (art, e) ->
                val min = e.sumOf { it.minuten }
                Anteil(art, e.size, min, Math.round(min * 100.0 / gesamt).toInt())
            }
            .sortedByDescending { it.minuten }
    }
}
