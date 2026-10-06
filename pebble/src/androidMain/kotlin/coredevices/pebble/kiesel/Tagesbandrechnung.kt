package coredevices.pebble.kiesel

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Was das Tagesband der Gesundheitsseite zeigt, ohne Oberflaeche: welches
 * Zeitfenster, welche Ereignisse darin, was noch geplant ist und wie dichte
 * Symbole zusammengehen. Farben, Zeichen und Worte legt die Ansicht darum -
 * hier steht nur, was ein Test ohne Schirm pruefen kann.
 */
internal object Tagesbandrechnung {

    /** So lange vor dem Einschlafen beginnt das Band: die Nacht soll nicht am Rand kleben. */
    val VOR_DER_NACHT: Duration = Duration.ofMinutes(15)

    /** Ohne Nacht beginnt es so lange vor Mitternacht - ein spaetes Glas des Vorabends steht dann noch darauf. */
    val OHNE_NACHT_VOR_MITTERNACHT: Duration = Duration.ofHours(2)

    /** Mindestens so lang ist das Band: ein ganzer Tag, auch kurz nach dem Aufwachen. */
    val SPANNE: Duration = Duration.ofHours(24)

    /** So weit reicht es mindestens ueber jetzt hinaus - die Jetzt-Linie steht nie am Rand. */
    val NACH_JETZT: Duration = Duration.ofHours(1)

    /** So weit reicht es ueber die Schlafenszeit hinaus, damit ihr Mond nicht abgeschnitten wird. */
    val NACH_BETT: Duration = Duration.ofMinutes(15)

    /** Gleiche Art binnen dieser Zeit wird ein Symbol: drei Glaeser in einer halben Stunde sind ein "0.75 l". */
    val ZUSAMMEN: Duration = Duration.ofMinutes(45)

    /**
     * Der kleinste Abstand zweier Symbolmitten in dp. 40 dp sind eine Stunde:
     * so stehen auch die Werte darunter ("63 mg", "0.25 l") frei nebeneinander,
     * nicht nur die 26-dp-Kreise.
     */
    const val ABSTAND_DP = 40f

    /** So weit darf ein Symbol nach rechts ruecken, bevor es mit dem davor gebuendelt wird. */
    const val SCHUB_DP = 24f

    /** Mehr als so viel Zeit zwischen zwei Messungen bricht die Pulslinie. */
    val PULS_LUECKE: Duration = Duration.ofMinutes(15)

    /** Das Zeitfenster des Bands. */
    data class Fenster(val von: Instant, val bis: Instant)

    /**
     * Der Anfang: das Einschlafen der Hauptnacht [nacht] minus
     * [VOR_DER_NACHT], ohne Nacht [heute] 00:00 minus [OHNE_NACHT_VOR_MITTERNACHT].
     * Eigens, weil Gesundheitsdaten den Puls des Bands ab hier schneidet.
     */
    fun von(nacht: Nacht?, heute: LocalDate, zone: ZoneId): Instant =
        nacht?.einschlafen?.minus(VOR_DER_NACHT) ?: heute.atStartOfDay(zone).toInstant().minus(OHNE_NACHT_VOR_MITTERNACHT)

    /**
     * Das ganze Fenster: ab [von] bis zum Spaeteren von [SPANNE] danach, jetzt
     * plus [NACH_JETZT] und der Schlafenszeit des laufenden Tags plus
     * [NACH_BETT] - dieselbe Schlafenszeit, die der Strahl blass zeigte
     * ([Gesundheitsrechnung.bettzeitHeute]).
     */
    fun fenster(nacht: Nacht?, jetzt: Instant, zone: ZoneId): Fenster {
        val von = von(nacht, jetzt.atZone(zone).toLocalDate(), zone)
        val bett = Gesundheitsrechnung.bettzeitHeute(nacht?.einschlafen, jetzt, zone)
        val bis = maxOf(von.plus(SPANNE), jetzt.plus(NACH_JETZT), bett.plus(NACH_BETT))
        return Fenster(von, bis)
    }

    /** Ein Ereignis auf dem Band. */
    sealed class Ereignis {
        abstract val zeit: Instant

        data class Training(val training: TrainingEintrag) : Ereignis() {
            override val zeit: Instant get() = training.sitzung.startTime
        }

        /** Ein Glas von Drinktervall ([Glaseintrag.eigen]) oder Wasser einer anderen App. */
        data class Wasser(val glas: Glaseintrag) : Ereignis() {
            override val zeit: Instant get() = glas.zeit
        }

        data class Getraenk(val eintrag: Tageseintrag) : Ereignis() {
            override val zeit: Instant get() = eintrag.zeit
        }

        /**
         * Was in derselben Minute mit derselben Herkunft eingetragen wurde -
         * SupCycle hakt mehrere Praeparate in einem Zug ab. Fremde Eintraege
         * derselben Minute bilden eine eigene Gruppe: sonst loeschte ein
         * langer Druck nur einen Teil davon.
         */
        data class Praeparate(val eintraege: List<Tageseintrag>) : Ereignis() {
            override val zeit: Instant get() = eintraege.first().zeit
            val eigen: Boolean get() = eintraege.first().eigen
            val titel: String get() = eintraege.joinToString(", ") { it.name }
        }
    }

    /**
     * Die Ereignisse im [fenster], die frueheste zuerst. Getraenke nur mit
     * Drinktervall, Praeparate nur mit SupCycle auf der Uhr (UhrApps), wie
     * ueberall. Vor Mitternacht stehen nur die eigenen Getraenke und Glaeser
     * von gestern zur Verfuegung ([GesundheitsStand.gestern]) - mehr liest die
     * Seite fuer gestern nicht. Bei gleicher Zeit bleibt die Reihenfolge
     * Training, Wasser, Getraenk, Praeparat - die Sortierung ist stabil.
     */
    fun ereignisse(stand: GesundheitsStand, trainings: List<TrainingEintrag>, fenster: Fenster): List<Ereignis> {
        fun drin(t: Instant) = !t.isBefore(fenster.von) && t.isBefore(fenster.bis)
        return buildList {
            trainings.filter { drin(it.sitzung.startTime) }.forEach { add(Ereignis.Training(it)) }
            if (stand.mitDrinktervall) {
                (stand.gesternGlaeser + stand.glasEintraege).filter { drin(it.zeit) }.forEach { add(Ereignis.Wasser(it)) }
                (stand.gestern + stand.eintraege).filter { !it.praeparat && drin(it.zeit) }.forEach { add(Ereignis.Getraenk(it)) }
            }
            if (stand.mitSupCycle) {
                // Nach Minute und Herkunft: ein Zug von SupCycle ist ein Symbol, ein fremder Eintrag daneben ein eigenes.
                stand.eintraege.filter { it.praeparat && drin(it.zeit) }.groupBy { it.zeit.epochSecond / 60 to it.eigen }.values
                    .forEach { add(Ereignis.Praeparate(it)) }
            }
        }.sortedBy { it.zeit }
    }

    /**
     * Die Pulslinie des Bands: je Stueck die Mediane der Fuenf-Minuten-Fenster
     * ([Gesundheitsrechnung.fuenfMinuten]), Sekunde des Fensteranfangs und Puls.
     * GETRENNT WIRD AN DEN MESSUNGEN, NICHT AN DEN FENSTERN: deren Anfaenge
     * liegen bis zu fuenf Minuten vor der Messung - 10:00 und 10:19 (Fenster
     * 10:00 und 10:15) blieben so ueber 19 Minuten ohne Puls verbunden. Die
     * Herzseite trennt weiter an den Fenstern ([Gesundheitsrechnung.pulslinie]),
     * ihr Pulsband bleibt, wie es ist.
     */
    fun pulslinie(punkte: List<Pair<Long, Int>>): List<List<Pair<Long, Int>>> {
        val laeufe = mutableListOf<MutableList<Pair<Long, Int>>>()
        punkte.sortedBy { it.first }.forEach { p ->
            val lauf = laeufe.lastOrNull()
            if (lauf == null || p.first - lauf.last().first > PULS_LUECKE.seconds) laeufe += mutableListOf(p) else lauf += p
        }
        // Je Lauf eigene Fenster: zwei Laeufe liegen mehr als eine Viertelstunde auseinander und teilen keines.
        return laeufe.map { lauf -> Gesundheitsrechnung.fuenfMinuten(lauf).map { it.sekunde to it.median } }
    }

    /** So weit unter dem Ruhepuls beginnt die Pulszeile: die Ruhelinie klebt nicht am Boden. */
    const val UNTER_RUHE = 10

    /** So viel Luft bleibt ueber der obersten Linie oder dem Hoechstwert: nichts stoesst oben an. */
    const val LUFT_OBEN = 5

    /**
     * Eine Bezugslinie hinter dem Puls: der Ruhepuls ([zone] = null) oder die
     * Grenze zur Zone [zone] - 1 moderat, 2 kraeftig, 3 Spitze, die Stellen in
     * Farben.pulszonen wie auf der Herzseite.
     */
    data class Bezugslinie(val bpm: Int, val zone: Int?)

    /**
     * Der Ruhepuls der Ruhelinie. Mit Nacht der ihre: ihr Text darunter nennt
     * ihn ("resting 55"), Linie und Text sagen so dieselbe Zahl. Ohne Nacht
     * (die Uhr nachts nicht getragen) oder ohne Ruhepuls in ihr der, mit dem
     * die Zonen gerechnet sind ([GesundheitsStand.ruhepulsZonen], der Median
     * der letzten Naechte) - sonst fehlte gerade an einem solchen Tag der
     * Massstab, und unten stuende der tiefste Wert statt Ruhepuls - 10. Null
     * nur, wenn keine Nacht einen hatte: lieber keine Linie als eine auf dem
     * Ersatzwert der Rechnung.
     */
    fun ruhepuls(s: GesundheitsStand): Int? = s.nacht?.ruhepuls?.takeIf { it > 0 } ?: s.ruhepulsZonen?.takeIf { it > 0 }

    /** Die Skala der Pulszeile von [tief] bis [hoch] bpm und die Linien darin, von unten nach oben. */
    data class Pulsskala(val tief: Int, val hoch: Int, val linien: List<Bezugslinie>)

    /**
     * Die Skala der Pulszeile, damit der Puls einen Massstab hat:
     * - Unten [UNTER_RUHE] unter dem Ruhepuls, tiefer nur, wenn der tiefste
     *   Wert [werte] darunter liegt.
     * - Oben die erste Zonengrenze UEBER dem hoechsten Wert, plus [LUFT_OBEN]:
     *   ein ruhiger Tag behaelt so seine Hoehe und wird nicht gestaucht, und
     *   man sieht, wie weit es bis zur naechsten Zone war. Ueber der
     *   Spitzengrenze der hoechste Wert selbst.
     * - Gezeichnet werden nur Linien in der Skala, und nur hinter einem Puls:
     *   ohne [werte] steht keine Linie da, die etwas messen koennte.
     * [ruhepuls]: der des Bands ([ruhepuls]). Ohne ihn - Boulder kennt
     * keinen gemessenen - keine Ruhelinie, und unten gilt der tiefste Wert.
     * [zonen]: die Grenzen der Herzseite
     * ([GesundheitsStand.pulsZonen], [Gesundheitsrechnung.pulsZonen]).
     * [werte]: was die Pulslinie zeigt (die Mediane der Fuenf-Minuten-Fenster).
     */
    fun pulsskala(ruhepuls: Int?, zonen: List<Int>, werte: List<Int>): Pulsskala {
        val ruhe = ruhepuls?.takeIf { it > 0 }
        val tiefster = werte.minOrNull()
        val hoechster = werte.maxOrNull()
        val unten = (ruhe ?: tiefster ?: Trainingsanalyse.RUHEPULS_ERSATZ) - UNTER_RUHE
        val tief = if (tiefster != null) minOf(unten, tiefster) else unten
        val oberster = hoechster ?: ruhe ?: unten
        val hoch = (zonen.firstOrNull { it > oberster } ?: oberster) + LUFT_OBEN
        if (hoechster == null) return Pulsskala(tief, hoch, emptyList())
        val linien = listOfNotNull(ruhe?.let { Bezugslinie(it, null) }) + zonen.mapIndexed { i, w -> Bezugslinie(w, i + 1) }
        return Pulsskala(tief, hoch, linien.filter { it.bpm in tief..hoch }.sortedBy { it.bpm })
    }

    /** Ein Praeparat, das heute noch geplant ist. */
    data class Geplant(val zeit: Instant, val name: String)

    /**
     * Die offenen Praeparate mit Planzeit nach [jetzt], zur Planzeit heute.
     * Versaeumte stehen nicht im Band: die nennt die Zeile Stack in Bernstein.
     * Nur mit SupCycle, wie die genommenen.
     */
    fun geplant(stand: GesundheitsStand, jetzt: Instant, zone: ZoneId, platz: (Int) -> String): List<Geplant> {
        val plan = stand.supplemente?.takeIf { stand.mitSupCycle } ?: return emptyList()
        val heute = jetzt.atZone(zone).toLocalDate()
        return plan.heute(platz).mapNotNull { e ->
            val zeit = e.zeit?.takeIf { !e.genommen }?.let { heute.atTime(it).atZone(zone).toInstant() } ?: return@mapNotNull null
            if (zeit.isAfter(jetzt)) Geplant(zeit, e.name) else null
        }.sortedBy { it.zeit }
    }

    /** Die Art eines Symbols. Gleiche Arten gehen zusammen, verschiedene nur im Buendel "+N". */
    enum class Art { TRAINING, WASSER, KAFFEE, PRAEPARAT, BETT }

    /** Was auf ein Symbol kommt: Art, Zeit, ob es geplant ist - und was es traegt ([inhalt], fuer die Ansicht). */
    data class Marke<T>(val art: Art, val zeit: Instant, val geplant: Boolean, val inhalt: T)

    /**
     * Ein Symbol der Zeile, seine Mitte bei [x] dp: eine Art aus einer oder
     * mehreren Marken - oder als [buendel] ein "+N", das beim Tippen seine
     * Marken zeigt.
     */
    data class Symbol<T>(val x: Float, val marken: List<Marke<T>>, val buendel: Boolean)

    /**
     * Die Marken als Symbole, ohne dass sich zwei ueberdecken:
     * 1. Gleiche Art (und gleich geplant oder nicht) binnen [ZUSAMMEN] ab der
     *    ersten wird ein Symbol - die Ansicht summiert die Werte.
     * 2. Steht ein Symbol naeher als [ABSTAND_DP] am vorigen, rueckt es nach
     *    rechts, hoechstens [SCHUB_DP].
     * 3. Reicht das nicht, geht es mit dem vorigen in ein Buendel "+N" an
     *    dessen Stelle.
     * [x] gibt die Lage eines Zeitpunkts in dp.
     */
    fun <T> anordnen(marken: List<Marke<T>>, x: (Instant) -> Float): List<Symbol<T>> {
        val gruppen = mutableListOf<MutableList<Marke<T>>>()
        marken.sortedBy { it.zeit }.forEach { m ->
            val gruppe = gruppen.lastOrNull {
                it.first().art == m.art && it.first().geplant == m.geplant && Duration.between(it.first().zeit, m.zeit) <= ZUSAMMEN
            }
            if (gruppe != null) gruppe += m else gruppen += mutableListOf(m)
        }
        val aus = mutableListOf<Symbol<T>>()
        gruppen.sortedBy { x(it.first().zeit) }.forEach { g ->
            val lage = x(g.first().zeit)
            val vorher = aus.lastOrNull()
            when {
                vorher == null || lage - vorher.x >= ABSTAND_DP -> aus += Symbol(lage, g, buendel = false)
                vorher.x + ABSTAND_DP - lage <= SCHUB_DP -> aus += Symbol(vorher.x + ABSTAND_DP, g, buendel = false)
                else -> aus[aus.lastIndex] = Symbol(vorher.x, vorher.marken + g, buendel = true)
            }
        }
        return aus
    }
}
