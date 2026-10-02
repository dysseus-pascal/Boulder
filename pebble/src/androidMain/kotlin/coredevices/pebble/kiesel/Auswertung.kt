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
     * Ab so vielen Tagen zaehlt eine Woche als voll. Eine Urlaubswoche, in der
     * nur der Wandertag erfasst ist, waere sonst eine aussergewoehnlich starke
     * Woche; das Bild zeichnet sie blass.
     */
    const val WOCHE_VOLL_AB = 4

    /**
     * UEBER SO VIELE TAGE rechnen Wochenprofil, Wochenende, Zielquote und
     * Schlafmitte - fest und auf der Karte genannt. Ueber die ganze Geschichte
     * gerechnet mischten sich Jahreszeiten und Quellwechsel ins Urteil, und die
     * Karten widersprachen den Vergleichen, die 30 Tage nehmen. Die acht Wochen
     * des Verlaufs und die Richtung (56 Tage) liegen darin.
     */
    const val FENSTER_TAGE = 90L

    /** Die Tage im festen Fenster vor der Grenze. */
    fun imFenster(reihe: List<Pair<LocalDate, Double>>, grenze: LocalDate): List<Pair<LocalDate, Double>> {
        val von = grenze.minusDays(FENSTER_TAGE)
        return reihe.filter { it.first < grenze && it.first >= von }
    }

    /**
     * Die Wochentage in der Reihenfolge, in der sie ueberall stehen.
     *
     * Montag zuerst - das ist hier die Woche, und ein Bild, dessen
     * Reihenfolge von der Spracheinstellung abhaengt, laesst sich mit einem
     * zweiten nicht vergleichen.
     */
    val WOCHENTAGE: List<DayOfWeek> = DayOfWeek.values().toList()

    /**
     * [eigenAb] ist der erste Tag, den Boulder selbst gemessen hat; davor
     * stammt die Reihe aus der Vorgeschichte (Kiesel-Helper).
     */
    fun bild(reihe: List<Pair<LocalDate, Double>>, grenze: LocalDate, eigenAb: LocalDate? = null): Bild {
        val tage = imFenster(reihe, grenze)

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
            veraenderung = veraenderung(tage, grenze, eigenAb),
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

    /**
     * Schritte kurz: ab tausend als "4.9k", die ",0" faellt weg ("10k"), darunter die Zahl.
     *
     * ABGERUNDET, NICHT GERUNDET: 9'960 Schritte als "10k" ueber einer Saeule,
     * die das Ziel nicht erreicht, behaupteten das Ziel. "9.9k" stimmt.
     */
    fun tausender(wert: Double, locale: Locale = Locale.getDefault()): String {
        val ganz = Math.floor(wert)
        if (ganz < 1000) return "%.0f".format(locale, ganz)
        // Auf Hunderter abrunden; das Format darf danach nicht mehr aufrunden.
        val k = "%.1f".format(locale, Math.floor(wert / 100) / 10)
        return k.removeSuffix(".0").removeSuffix(",0") + "k"
    }

    /**
     * Wann die typische Woche fruehestens tragen kann: drei Wochentage mit je
     * zwei Tagen sind bei lueckenlosen Daten nach zehn Tagen da (der erste und
     * der achte, der zweite und der neunte, der dritte und der zehnte). Der
     * zehnte Tag zaehlt erst, wenn er vor der Grenze liegt - fuer Tageszaehler
     * (Grenze = heute) also einen Tag spaeter, wie bei [wochenAb]. Luecken
     * machen es spaeter, nie frueher.
     */
    fun typischeWocheAb(erster: LocalDate, grenze: LocalDate, heute: LocalDate): LocalDate =
        erster.plusDays(9 + ChronoUnit.DAYS.between(grenze, heute) + 1)

    /**
     * Wann der Wochenverlauf tragen kann: der Montag der dritten Kalenderwoche
     * ab dem ersten Tag - verschoben um den Abstand von Grenze und Heute, weil
     * der Montag erst zaehlt, wenn er vor der Grenze liegt. Fuer Tageszaehler
     * (Grenze = heute) ist das der Dienstag; der Satz soll den Tag nennen, an
     * dem die Karte wirklich erscheint.
     */
    fun wochenAb(erster: LocalDate, grenze: LocalDate, heute: LocalDate): LocalDate =
        erster.with(DayOfWeek.MONDAY).plusWeeks(2).plusDays(ChronoUnit.DAYS.between(grenze, heute) + 1)

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
     *
     * UND BEIDE DIESELBE QUELLE: liegt [eigenAb] mitten in den acht Wochen,
     * verglichen sie Kiesel-Helpers Rechnung mit Boulders - ein Sprung der
     * Messart, kein Trend.
     */
    private fun veraenderung(
        tage: List<Pair<LocalDate, Double>>,
        grenze: LocalDate,
        eigenAb: LocalDate?,
    ): Double? {
        val jung = tage.filter { it.first >= grenze.minusDays(28) }
        val alt = tage.filter {
            it.first >= grenze.minusDays(56) && it.first < grenze.minusDays(28)
        }
        if (jung.size < 7 || alt.size < 7) return null
        if (eigenAb != null && (alt + jung).any { it.first < eigenAb } && (alt + jung).any { it.first >= eigenAb }) return null
        val a = alt.map { it.second }.average()
        if (a == 0.0) return null
        return (jung.map { it.second }.average() - a) / a * 100
    }

    /**
     * Zwei Gruppen von Tagen gegeneinander - "HRV nach kurzen Naechten" gegen
     * "nach laengeren". Kein Korrelationskoeffizient: der sagt wenig und
     * klingt nach mehr. Hier steht ein Unterschied in der Einheit der Groesse.
     *
     * KLAR heisst: der Unterschied der Mediane ist mindestens zweimal so gross
     * wie sein Standardfehler. Darunter ist er von der Schwankung von Tag zu
     * Tag nicht zu trennen, und die Karte sagt "no difference". Streuen beide
     * Gruppen gar nicht (fuenfmal 160 mg gegen sechsmal 80), ist jeder
     * Unterschied klar.
     */
    data class Gruppenvergleich(
        val a: List<Double>,
        val b: List<Double>,
        val medianA: Double,
        val medianB: Double,
        /** Median A minus Median B. */
        val unterschied: Double,
        /** Die uebliche Streuung von Tag zu Tag (gepoolte Standardabweichung). */
        val streuung: Double,
        val klar: Boolean,
    ) {
        val genug: Boolean get() = a.size >= VERGLEICH_MINDESTENS && b.size >= VERGLEICH_MINDESTENS
    }

    /** So viele Tage braucht jede Gruppe, damit ein Vergleich erscheint. */
    const val VERGLEICH_MINDESTENS = 5
    /** Ueber so viele Tage wird verglichen. */
    const val VERGLEICH_TAGE = 30L

    /**
     * Die Werte von [ziel] in den [VERGLEICH_TAGE] Tagen vor [grenze], nach
     * [gruppe] aufgeteilt: true Gruppe A, false Gruppe B, null gehoert zu keiner
     * (etwa wenn die Bedingung an dem Tag unbekannt ist).
     */
    fun vergleich(ziel: List<Pair<LocalDate, Double>>, grenze: LocalDate, gruppe: (LocalDate) -> Boolean?): Gruppenvergleich {
        val von = grenze.minusDays(VERGLEICH_TAGE)
        val a = mutableListOf<Double>()
        val b = mutableListOf<Double>()
        ziel.filter { !it.first.isBefore(von) && it.first.isBefore(grenze) }.forEach { (tag, w) ->
            when (gruppe(tag)) {
                true -> a += w
                false -> b += w
                null -> Unit
            }
        }
        fun median(l: List<Double>): Double {
            if (l.isEmpty()) return 0.0
            val s = l.sorted()
            return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
        }
        fun varianz(l: List<Double>): Double {
            if (l.size < 2) return 0.0
            val m = l.average()
            return l.sumOf { (it - m) * (it - m) } / (l.size - 1)
        }
        val ma = median(a)
        val mb = median(b)
        val streuung = if (a.size + b.size > 2) {
            kotlin.math.sqrt(((a.size - 1).coerceAtLeast(0) * varianz(a) + (b.size - 1).coerceAtLeast(0) * varianz(b)) / (a.size + b.size - 2).coerceAtLeast(1))
        } else 0.0
        // Der Standardfehler des Unterschieds; der Median streut etwa 1.25-mal so stark wie der Mittelwert.
        val fehler = if (a.isNotEmpty() && b.isNotEmpty()) 1.25 * streuung * kotlin.math.sqrt(1.0 / a.size + 1.0 / b.size) else Double.MAX_VALUE
        val unterschied = ma - mb
        val klar = a.isNotEmpty() && b.isNotEmpty() && if (fehler > 0) kotlin.math.abs(unterschied) >= 2 * fehler else unterschied != 0.0
        return Gruppenvergleich(a, b, ma, mb, unterschied, streuung, klar)
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
        val tage = imFenster(reihe, grenze)
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
