package coredevices.pebble.kiesel

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Aus Tagen ein Muster machen.
 *
 * REINE RECHNUNG, kein Android: nichts hier kennt eine Datenbank oder einen
 * Bildschirm. Das ist Absicht - es ist die einzige Stelle der App, die eine
 * Aussage BEHAUPTET ("dein Mittwoch ist schwach"), und Behauptungen sollte man
 * ohne Telefon nachrechnen koennen. Die Pruefungen dazu stehen in
 * `AuswertungTest`.
 *
 * AB DER GRENZE ZAEHLT NICHTS MIT. Ein angefangener Tag hat immer zu wenig
 * Schritte; wer ihn einrechnet, bekommt ein Wochenprofil, in dem der heutige
 * Wochentag immer der schwaechste ist - und das Muster waere ein Abbild der
 * Uhrzeit, zu der man hinschaut. Wo die Grenze liegt, weiss nur der Aufrufer:
 * eine Nacht wird auf den Tag des Aufwachens gebucht und ist am Morgen fertig,
 * der Tag selbst erst um Mitternacht. Nachtreihen reichen darum bis
 * einschliesslich heute (Grenze morgen), Tageszaehler nur bis gestern.
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

    fun bild(reihe: List<Pair<LocalDate, Double>>, grenze: LocalDate): Bild {
        val tage = reihe.filter { it.first < grenze }

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
            veraenderung = veraenderung(tage, grenze),
        )
    }

    /**
     * Die letzten Kalendertage vor der Grenze, lueckenlos - Tage ohne Wert als null.
     *
     * Das Bild braucht jeden Tag als Fach, auch den leeren: eine Reihe, die
     * Luecken einfach zusammenschiebt, macht aus einer Woche ohne Uhr einen
     * scheinbar dichten Verlauf.
     */
    fun letzteTage(reihe: List<Pair<LocalDate, Double>>, grenze: LocalDate, anzahl: Int = 14): List<Pair<LocalDate, Double?>> {
        val werte = reihe.toMap()
        return (anzahl downTo 1).map { i -> grenze.minusDays(i.toLong()).let { it to werte[it] } }
    }

    /**
     * Runde Achsenstriche zwischen zwei Werten: der kleinste Schritt aus
     * 1, 2, 5 mal Zehnerpotenz, der hoechstens fuenf Striche ergibt. Bleiben
     * dabei weniger als drei, wird der Bereich auf den Schritt gerundet und
     * die Randstriche kommen dazu - die Achse darf dann ueber den Bereich
     * hinausreichen, das Bild nimmt die aeusseren Striche als Grenzen.
     *
     * Striche bei min, Mitte und max sind Zahlen wie 53, 61 und 69 - niemand
     * kann daran ablesen, wo 60 liegt. Runde Schritte kann man lesen; zwei
     * Striche allein (55 und 60 bei 52..62) zeigen aber keinen Massstab.
     */
    fun achsenschritte(von: Double, bis: Double): List<Double> {
        if (bis <= von) return listOf(von)
        val schritte = (0..6).flatMap { k -> listOf(1.0, 2.0, 5.0).map { it * Math.pow(10.0, k.toDouble()) } }
        val schritt = schritte.firstOrNull { striche(von, bis, it).size <= 5 } ?: schritte.last()
        val innen = striche(von, bis, schritt)
        if (innen.size >= 3) return innen
        return striche(Math.floor(von / schritt) * schritt, Math.ceil(bis / schritt) * schritt, schritt)
    }

    private fun striche(von: Double, bis: Double, schritt: Double): List<Double> {
        val erster = Math.ceil(von / schritt).toLong()
        val letzter = Math.floor(bis / schritt).toLong()
        return (erster..letzter).map { it * schritt }
    }

    /** Schritte kurz: ab tausend als "4.9k", die ",0" faellt weg ("10k"), darunter die Zahl. */
    fun tausender(wert: Double, locale: Locale = Locale.getDefault()): String {
        // Erst runden, dann die Grenze pruefen: 999.6 wuerde sonst als "1000" statt "1k" erscheinen.
        val rund = Math.rint(wert)
        if (rund < 1000) return "%.0f".format(locale, rund)
        val k = "%.1f".format(locale, wert / 1000)
        return k.removeSuffix(".0").removeSuffix(",0") + "k"
    }

    /**
     * Wann die typische Woche fruehestens tragen kann: der erste Tag plus zwei
     * Wochen - erst dann koennen drei Wochentage je zwei Tage haben. Eine
     * Schaetzung, weil Luecken es spaeter machen; darum sagt der Satz "about".
     */
    fun typischeWocheAb(erster: LocalDate): LocalDate = erster.plusDays(14)

    /**
     * Wann der Wochenverlauf tragen kann: der Montag der dritten Kalenderwoche
     * ab dem ersten Tag - verschoben um den Abstand von Grenze und Heute, weil
     * der Montag erst zaehlt, wenn er vor der Grenze liegt. Fuer Tageszaehler
     * (Grenze = heute) ist das der Dienstag; der Satz soll den Tag nennen, an
     * dem die Karte wirklich erscheint.
     */
    fun wochenAb(erster: LocalDate, grenze: LocalDate, heute: LocalDate): LocalDate =
        erster.with(DayOfWeek.MONDAY).plusWeeks(2).plusDays(ChronoUnit.DAYS.between(grenze, heute) + 1)

    /** Wann ein Paar fruehestens belastbar wird - null, wenn die beiden noch keinen gemeinsamen Tag haben. */
    fun linksAb(eins: List<Pair<LocalDate, Double>>, zwei: List<Pair<LocalDate, Double>>, grenze: LocalDate): LocalDate? {
        val b = zwei.toMap()
        return eins.filter { it.first < grenze && it.first in b }.minOfOrNull { it.first }?.plusDays(PAARE_MINDESTENS.toLong())
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
        grenze: LocalDate,
    ): Double? {
        val jung = tage.filter { it.first >= grenze.minusDays(28) }
        val alt = tage.filter {
            it.first >= grenze.minusDays(56) && it.first < grenze.minusDays(28)
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
        grenze: LocalDate,
    ): Zusammenhang {
        val b = zwei.toMap()
        val paare = eins.filter { it.first < grenze }
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
        grenze: LocalDate,
    ): Wochenendbild? {
        val tage = reihe.filter { it.first < grenze }
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
