package coredevices.pebble.kiesel

import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.metadata.Metadata
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Ein Tag der Wochenreihe: die laengste Art als Kreis, die anderen als Punkte darunter. */
internal data class Reihentag(val tag: LocalDate, val haupt: Sport?, val weitere: List<Sport>, val minuten: Long)

/** Was der Satz der Wochenkarte sagt: das letzte Training und die Woche gegen die Vorwoche. */
internal data class Wochenlage(
    val letzte: Sport?,
    /** Tage seit dem letzten Training, 0 = heute. */
    val tageHer: Long?,
    val anzahl: Int,
    val minuten: Long,
    val vorwoche: Long,
)

/**
 * Die Rechnung hinter der Sportseite nach dem Entwurf "Empfehlung": Satz,
 * Wochenreihe, Liste nach Wochen. Ohne Android, damit sie sich pruefen laesst.
 */
internal object Sportrechnung {

    /** Montag bis Sonntag der laufenden Woche, auch die Tage, die noch kommen. */
    fun reihe(einheiten: List<Einheit>, heute: LocalDate): List<Reihentag> {
        val tage = Wochenrechnung.tage(einheiten)
        val montag = heute.with(DayOfWeek.MONDAY)
        return (0L until 7L).map { montag.plusDays(it) }.map { tag ->
            val t = tage[tag]
            Reihentag(tag, t?.arten?.firstOrNull(), t?.arten?.drop(1).orEmpty(), t?.minuten ?: 0)
        }
    }

    /**
     * [letzte] kommt vom Aufrufer: am selben Tag entscheidet die Uhrzeit, und
     * die kennt [Einheit] nicht.
     */
    fun lage(einheiten: List<Einheit>, letzte: Einheit?, heute: LocalDate): Wochenlage {
        val (vor, diese) = Wochenrechnung.wochen(einheiten, heute, 2)
        return Wochenlage(
            letzte = letzte?.sport,
            tageHer = letzte?.let { ChronoUnit.DAYS.between(it.tag, heute) },
            anzahl = einheiten.count { !it.tag.isBefore(diese.montag) && !it.tag.isAfter(heute) },
            minuten = diese.minuten,
            vorwoche = vor.minuten,
        )
    }

    /** Die Minuten der letzten acht Wochen fuer das kleine Balkenbild, aelteste zuerst. */
    fun achtWochen(einheiten: List<Einheit>, heute: LocalDate): List<Long> =
        Wochenrechnung.wochen(einheiten, heute, 8).map { it.minuten }

    /** Nach Wochen, die juengste zuerst; innerhalb der Woche bleibt die Reihenfolge. */
    fun <T> nachWoche(liste: List<T>, tag: (T) -> LocalDate): List<Pair<LocalDate, List<T>>> =
        liste.groupBy { tag(it).with(DayOfWeek.MONDAY) }.toList().sortedByDescending { it.first }

    /** Die Arten fuer die Filterchips: die haeufigste zuerst, bei Gleichstand die laengere. */
    fun zaehle(einheiten: List<Einheit>): List<Pair<Sport, Int>> =
        einheiten.groupBy { it.sport }
            .map { (art, e) -> Triple(art, e.size, e.sumOf { it.minuten }) }
            .sortedWith(compareByDescending<Triple<Sport, Int, Long>> { it.second }.thenByDescending { it.third })
            .map { it.first to it.second }

    /** km/h aus Strecke und Zeit; ohne Strecke keine. */
    fun kmh(meter: Double, sekunden: Long): Double? =
        if (meter <= 0 || sekunden <= 0) null else meter / sekunden * 3.6

    /** Sekunden je Kilometer; unter 0,2 m/s ist es Stehen, kein Tempo. */
    fun tempo(meter: Double, sekunden: Long): Long? {
        if (meter <= 0 || sekunden <= 0) return null
        val ms = meter / sekunden
        return if (ms <= 0.2) null else Math.round(1000 / ms)
    }

    /**
     * Welche Sitzungen als Training zaehlen - ZWEI APPS, EIN TRAINING. Eigene
     * bleiben immer; eine fremde faellt heraus, wenn sie sich mit einer
     * eigenen ueberschneidet. Unter fremden bleibt bei mehr als halber
     * Ueberschneidung (gemessen an der kuerzeren) nur die laengere: Hersteller-
     * App und Strava schreiben denselben Lauf, und die Woche zaehlte ihn doppelt.
     * NUR BEI GLEICHER [art]: ein Laufbandlauf mitten in der Stunde Kraft
     * einer anderen App ist ein zweites Training, keine zweite Fassung.
     * In der Akte bleiben alle. Die Reihenfolge der Liste bleibt.
     */
    fun <T> waehle(
        sitzungen: List<T>, eigen: (T) -> Boolean, von: (T) -> Instant, bis: (T) -> Instant, art: (T) -> Any?,
    ): List<T> {
        fun ueberlapp(a: T, b: T): Long =
            (minOf(bis(a), bis(b)).epochSecond - maxOf(von(a), von(b)).epochSecond).coerceAtLeast(0)
        fun dauer(a: T): Long = (bis(a).epochSecond - von(a).epochSecond).coerceAtLeast(1)
        val eigene = sitzungen.filter(eigen)
        val fremde = sitzungen.filter { s -> !eigen(s) && eigene.none { e -> von(s) < bis(e) && von(e) < bis(s) } }
        val behalten = mutableListOf<T>()
        // Die laengste zuerst: sie gewinnt gegen jede kuerzere Fassung desselben Trainings.
        fremde.sortedByDescending { dauer(it) }.forEach { s ->
            if (behalten.none { b -> art(b) == art(s) && ueberlapp(s, b) * 2 > minOf(dauer(s), dauer(b)) }) behalten += s
        }
        return sitzungen.filter { eigen(it) || it in behalten }
    }

    /**
     * Ein Gang, den eine fremde App selbst erkannte (Samsung, Fitbit), ist
     * Bewegung und kein Training - wie die erkannten der Uhr, die Boulder
     * auch nicht zaehlt. Gezielt gestartete Spaziergaenge bleiben.
     */
    fun erkannterGang(sportart: Int, aufzeichnung: Int): Boolean =
        sportart == ExerciseSessionRecord.EXERCISE_TYPE_WALKING &&
            aufzeichnung == Metadata.RECORDING_METHOD_AUTOMATICALLY_RECORDED

    /**
     * Die Kieselsport-Art hinter einem deutschen Titel. Kiesel-Helper schrieb
     * die Titel in der Sprache des Telefons, und die Akte behaelt sie - auf
     * einer englischen Seite stuende sonst "Kraft" neben "Running".
     */
    fun artAusTitel(titel: String?): Long? = when (titel?.trim()) {
        "Laufen" -> 0
        "Bike Strasse/Gravel" -> 1
        "Wandern" -> 2
        "Kraft" -> 3
        "Schwimmen" -> 6
        "Training" -> -1
        else -> null
    }
}
