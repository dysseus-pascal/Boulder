package coredevices.pebble.kiesel

import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Aus Tagen ein Muster machen.
 *
 * REINE RECHNUNG, kein Android: nichts hier kennt eine Datenbank oder einen
 * Bildschirm. Das ist Absicht - es ist die einzige Stelle der App, die eine
 * Aussage BEHAUPTET ("dein Mittwoch ist schwach"), und Behauptungen sollte man
 * ohne Telefon nachrechnen koennen. Die Pruefungen dazu stehen in
 * `AuswertungTest`.
 *
 * HEUTE ZAEHLT NICHT MIT. Ein angefangener Tag hat immer zu wenig Schritte;
 * wer ihn einrechnet, bekommt ein Wochenprofil, in dem der heutige Wochentag
 * immer der schwaechste ist - und das Muster waere ein Abbild der Uhrzeit, zu
 * der man hinschaut.
 */
internal object Auswertung {

    /**
     * Ein Wochentag im Mittel - mit seiner Streuung.
     *
     * DER MITTELWERT ALLEIN IST EINE HALBE AUSSAGE. Drei Mittwoche mit 4000,
     * 8000 und 12000 Schritten ergeben denselben Schnitt wie drei mit je 8000,
     * und nur einer der beiden Faelle heisst "typisch".
     */
    data class Profilwert(
        val tag: DayOfWeek,
        val mittel: Double?,
        val anzahl: Int,
        val kleinster: Double? = null,
        val groesster: Double? = null,
    )

    /** Eine Kalenderwoche im Mittel, benannt nach ihrem Montag. */
    data class Wochenwert(val montag: LocalDate, val mittel: Double, val anzahl: Int)

    data class Bild(
        val profil: List<Profilwert>,
        val gesamt: Double?,
        val anzahl: Int,
        val staerkster: Profilwert?,
        val schwaechster: Profilwert?,
        val wochen: List<Wochenwert>,
        /** Letzte vier Wochen gegen die vier davor, in Prozent. */
        val veraenderung: Double?,
    )

    /**
     * Wie viele Tage ein Wochentag braucht, bevor er "typisch" heissen darf.
     *
     * Aus einem einzigen Mittwoch ein Muster zu lesen ist keine Auswertung,
     * sondern eine Erinnerung. Zwei sind auch noch duenn, aber ab da wird das
     * Warten laenger als der Nutzen: es dauert drei Wochen, bis das Bild
     * ueberhaupt etwas zeigen darf.
     */
    const val MINDESTENS = 2

    /** Wie viele Wochen der Verlauf zeigt. */
    const val WOCHEN = 8

    /**
     * Die Wochentage in der Reihenfolge, in der sie ueberall stehen.
     *
     * Montag zuerst - das ist hier die Woche, und ein Bild, dessen
     * Reihenfolge von der Spracheinstellung abhaengt, laesst sich mit einem
     * zweiten nicht vergleichen.
     */
    val WOCHENTAGE: List<DayOfWeek> = DayOfWeek.values().toList()

    fun bild(reihe: List<Pair<LocalDate, Double>>, heute: LocalDate): Bild {
        val tage = reihe.filter { it.first < heute }

        val profil = WOCHENTAGE.map { wochentag ->
            val treffer = tage.filter { it.first.dayOfWeek == wochentag }
            val genug = treffer.size >= MINDESTENS
            Profilwert(
                wochentag,
                if (genug) treffer.map { it.second }.average() else null,
                treffer.size,
                if (genug) treffer.minOf { it.second } else null,
                if (genug) treffer.maxOf { it.second } else null,
            )
        }
        val gezaehlt = profil.filter { it.mittel != null }

        val wochen = tage.groupBy { it.first.with(DayOfWeek.MONDAY) }
            .map { (montag, werte) ->
                Wochenwert(montag, werte.map { it.second }.average(), werte.size)
            }
            .sortedBy { it.montag }
            .takeLast(WOCHEN)

        return Bild(
            profil = profil,
            gesamt = if (tage.isEmpty()) null else tage.map { it.second }.average(),
            anzahl = tage.size,
            staerkster = gezaehlt.maxByOrNull { it.mittel!! },
            schwaechster = gezaehlt.minByOrNull { it.mittel!! },
            wochen = wochen,
            veraenderung = veraenderung(tage, heute),
        )
    }

    /**
     * Die Richtung: vier Wochen gegen vier Wochen.
     *
     * VIER UND NICHT EINE. Eine Woche gegen die vorige ist Rauschen - ein
     * Feiertag, eine Erkaeltung, ein Wochenende weg, und die Zahl springt um
     * dreissig Prozent. Vier Wochen gegen vier Wochen ueberlebt einen
     * einzelnen schlechten Tag.
     *
     * Beide Haelften brauchen genug Tage; sonst vergleicht man eine volle
     * Woche mit drei Tagen und nennt den Unterschied einen Trend.
     */
    private fun veraenderung(
        tage: List<Pair<LocalDate, Double>>,
        heute: LocalDate,
    ): Double? {
        val jung = tage.filter { it.first >= heute.minusDays(28) }
        val alt = tage.filter {
            it.first >= heute.minusDays(56) && it.first < heute.minusDays(28)
        }
        if (jung.size < 7 || alt.size < 7) return null
        val a = alt.map { it.second }.average()
        if (a == 0.0) return null
        return (jung.map { it.second }.average() - a) / a * 100
    }

    /**
     * Zwei Groessen gegeneinander.
     *
     * DIE EINZIGE STELLE, DIE EINEN ZUSAMMENHANG BEHAUPTET - und deshalb die,
     * die am meisten Vorsicht braucht. Gerechnet wird der Korrelationskoeffizient
     * nach Pearson und eine Ausgleichsgerade nach kleinsten Quadraten; beides
     * sagt NICHTS ueber Ursache und Wirkung. Wer lange schlaeft, hat vielleicht
     * einen tieferen Ruhepuls - oder wer einen tieferen Ruhepuls hat, schlaeft
     * besser, oder beides haengt an einem dritten, das hier gar nicht steht.
     */
    data class Zusammenhang(
        val n: Int,
        /** Pearson, -1 bis 1. `null`, wenn eine Seite gar nicht schwankt. */
        val r: Double?,
        val steigung: Double,
        val achse: Double,
        val punkte: List<Pair<Double, Double>>,
    ) {
        /** Reicht die Zahl der Tage fuer eine Aussage? */
        val belastbar: Boolean get() = n >= PAARE_MINDESTENS && r != null

        /**
         * Die Staerke als Stufe: 0 kein erkennbarer, 1 schwach, 2 mittel,
         * 3 deutlich. Die Worte dazu stehen in den Ressourcen - als ganze
         * Saetze, weil sich "ein schwacher Zusammenhang" nicht in jeder
         * Sprache aus Adjektiv und Nomen zusammensetzen laesst.
         *
         * Die Grenzen sind Konvention, keine Naturkonstante - aber eine Zahl
         * wie 0,37 allein sagt den meisten Menschen nichts, und "schwach" ist
         * ehrlicher als das Schweigen dazu.
         */
        val stufe: Int
            get() {
                val betrag = kotlin.math.abs(r ?: 0.0)
                return when {
                    betrag < 0.2 -> 0
                    betrag < 0.4 -> 1
                    betrag < 0.6 -> 2
                    else -> 3
                }
            }
    }

    /** Unter so vielen gemeinsamen Tagen wird kein Zusammenhang gezeigt. */
    const val PAARE_MINDESTENS = 14

    /**
     * Die Tage verbinden, an denen BEIDE Groessen etwas wissen.
     *
     * Ein Tag mit Schlaf, aber ohne Ruhepuls gehoert in kein Paar. Ihn mit
     * einem Mittelwert aufzufuellen hiesse, eine Messung zu erfinden, und
     * gerade hier faellt so etwas nicht auf: die Wolke sieht danach sogar
     * ordentlicher aus.
     */
    fun zusammenhang(
        eins: List<Pair<LocalDate, Double>>,
        zwei: List<Pair<LocalDate, Double>>,
        heute: LocalDate,
    ): Zusammenhang {
        val b = zwei.toMap()
        val paare = eins.filter { it.first < heute }
            .mapNotNull { (tag, x) -> b[tag]?.let { y -> x to y } }
        val n = paare.size
        if (n < 3) return Zusammenhang(n, null, 0.0, 0.0, paare)

        val mx = paare.sumOf { it.first } / n
        val my = paare.sumOf { it.second } / n
        var sxy = 0.0; var sxx = 0.0; var syy = 0.0
        paare.forEach { (x, y) ->
            sxy += (x - mx) * (y - my)
            sxx += (x - mx) * (x - mx)
            syy += (y - my) * (y - my)
        }
        if (sxx <= 0.0 || syy <= 0.0) return Zusammenhang(n, null, 0.0, my, paare)

        val steigung = sxy / sxx
        return Zusammenhang(
            n = n,
            r = sxy / kotlin.math.sqrt(sxx * syy),
            steigung = steigung,
            achse = my - steigung * mx,
            punkte = paare,
        )
    }

    /**
     * Wochenende gegen Werktag.
     *
     * Beide Seiten brauchen genug Tage; sonst vergleicht man vier Wochen
     * Arbeit mit einem einzigen Sonntag und nennt den Unterschied ein Muster.
     */
    data class Wochenendbild(
        val werktag: Double,
        val wochenende: Double,
        val tageWerk: Int,
        val tageFrei: Int,
    ) {
        val unterschied: Double get() = wochenende - werktag
    }

    /** Je Seite mindestens so viele Tage. */
    const val SEITE_MINDESTENS = 3

    fun wochenende(
        reihe: List<Pair<LocalDate, Double>>,
        heute: LocalDate,
    ): Wochenendbild? {
        val tage = reihe.filter { it.first < heute }
        val frei = tage.filter {
            it.first.dayOfWeek == DayOfWeek.SATURDAY || it.first.dayOfWeek == DayOfWeek.SUNDAY
        }
        val werk = tage - frei.toSet()
        if (frei.size < SEITE_MINDESTENS || werk.size < SEITE_MINDESTENS) return null
        return Wochenendbild(
            werk.map { it.second }.average(),
            frei.map { it.second }.average(),
            werk.size,
            frei.size,
        )
    }

    /**
     * Wie stark eine Groesse schwankt - Standardabweichung.
     *
     * FUER DIE SCHLAFMITTE IST DAS DIE EIGENTLICHE ZAHL. Wer jede Nacht
     * gleich lang schlaeft, aber jedes Mal zu einer anderen Zeit, hat einen
     * unauffaelligen Mittelwert und trotzdem ein Problem. Ein Mittelwert
     * allein sieht das nie.
     *
     * Gerechnet wird ueber die Stichprobe (n-1): die Tage sind eine Auswahl
     * aus allen, nicht alle.
     */
    fun streuung(werte: List<Double>): Double? {
        if (werte.size < 3) return null
        val mittel = werte.average()
        val summe = werte.sumOf { (it - mittel) * (it - mittel) }
        return kotlin.math.sqrt(summe / (werte.size - 1))
    }
}
