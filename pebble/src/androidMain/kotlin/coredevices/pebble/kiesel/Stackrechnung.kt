package coredevices.pebble.kiesel

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Was die Seiten Stack und Water zeigen, ohne Oberflaeche: die Praeparate
 * des Tages mit Einnahmezeit und Kur, die Koffeinkurve bis zur
 * Schlafenszeit und die Wasserwoche. Farben und Worte legt die Ansicht
 * darum - hier steht, was ein Test ohne Schirm pruefen kann.
 */
internal object Stackrechnung {

    /**
     * Ein Praeparat der Seite Stack. [um]: wann es genommen wurde, aus der
     * Akte - null, wenn es offen ist oder dort keiner steht. [plan]: die
     * Planzeit, null ohne Plan. [eintrag]: was ein langer Druck aendern darf,
     * nur Boulders eigener Eintrag (sonst null).
     */
    data class Praeparat(
        val name: String,
        val genommen: Boolean,
        val um: Instant?,
        val plan: LocalTime?,
        val versaeumt: Boolean,
        val kur: SupCycle.Kurwoche?,
        val eintrag: Tageseintrag?,
    )

    /**
     * Die Praeparate von heute in der Reihenfolge des Plans. DIE EINNAHMEZEIT
     * KOMMT AUS DER AKTE, nicht von der Uhr: dort steht der Eintrag, den ein
     * langer Druck loescht - Zeit und Loeschen gehoeren zum selben. Gefunden
     * an der Kennung des Platzes; Eintraege bis Boulder 0.33 haben keine,
     * dann am Namen. Versaeumt heisst dasselbe wie im Satz, in der Zeile und
     * im Widget ([praeparatVersaeumt]).
     */
    fun praeparate(stand: SupCycle.Stand, eintraege: List<Tageseintrag>, jetzt: Instant, zone: ZoneId, platz: (Int) -> String): List<Praeparat> {
        val tag = stand.tag.toString()
        val uhrTag = SupCycle.uhrTag(stand.tag, zone, stand.fassung)
        val genommene = eintraege.filter { it.praeparat }
        return stand.heuteJePlatz(platz).map { (i, e) ->
            val eintrag = if (!e.genommen) null else {
                genommene.filter { SupCycle.platzAus(it.kennung, tag) == i }.maxByOrNull { it.zeit }
                    ?: genommene.filter { it.kennung == null && it.name == e.name }.maxByOrNull { it.zeit }
            }
            Praeparat(
                name = e.name,
                genommen = e.genommen,
                um = eintrag?.zeit,
                plan = e.zeit,
                versaeumt = Gesundheitsrechnung.praeparatVersaeumt(e, jetzt.atZone(zone).toLocalTime()),
                kur = stand.plan.getOrNull(i)?.let { SupCycle.kurwoche(it, uhrTag, stand.fassung) },
                eintrag = eintrag?.takeIf { it.eigen },
            )
        }
    }

    /** So weit wird die Kurve abgetastet - fein genug, dass der Abfall rund wirkt. */
    val SCHRITT: Duration = Duration.ofMinutes(5)

    /** So lange reicht die Kurve mindestens ueber jetzt hinaus - die Jetzt-Linie steht nie am Rand. */
    val NACH_JETZT: Duration = Duration.ofMinutes(15)

    /**
     * Mindestens so weit reicht sie ueber die Schlafenszeit hinaus: ihre
     * gepunktete Linie soll nicht am Rand kleben. Kurz genug, dass 21:40
     * auf 22:00 endet wie im Entwurf; 22:00 selbst endet um 23:00.
     */
    val NACH_BETT: Duration = Duration.ofMinutes(10)

    /**
     * Die Koffeinkurve von [von] bis [bis], abgetastet in [punkte] (mg je
     * Zeitpunkt, aufsteigend, [jetzt] eingeschlossen). [bett]: die
     * Schlafenszeit, null, wenn sie vorbei ist - dann nennt die Seite keinen
     * Wert "at bedtime", wie die Zeile Stack auf Health.
     */
    data class Koffeinkurve(
        val von: Instant,
        val bis: Instant,
        val jetzt: Instant,
        val bett: Instant?,
        val punkte: List<Pair<Instant, Double>>,
    ) {
        val spitze: Double get() = punkte.maxOfOrNull { it.second } ?: 0.0
    }

    /**
     * Die Kurve aus dem Koffeinmodell der App ([Gesundheitsrechnung.koffeinAktiv]):
     * - VON MORGENS: ab sechs Uhr des laufenden Tags ([Gesundheitsrechnung.tagesbeginn]);
     *   ein Kaffee davor zieht den Anfang auf seine volle Stunde vor - sonst
     *   begaenne die Kurve schon hoch, ohne dass man saehe, woher.
     * - BIS ZUR SCHLAFENSZEIT: dieselbe wie im Tagesband
     *   ([Gesundheitsrechnung.bettzeitHeute]), mit [NACH_BETT] auf die volle
     *   Stunde danach; ist sie vorbei, bis kurz nach jetzt ([NACH_JETZT]).
     * - Je Getraenk zwei Punkte, eine Sekunde davor und darauf: das Modell
     *   springt beim Trinken, und die Kurve soll den Sprung zeigen statt
     *   einer Rampe ueber fuenf Minuten.
     */
    fun koffeinkurve(dosen: List<Pair<Instant, Double>>, jetzt: Instant, einschlafen: Instant?, zone: ZoneId): Koffeinkurve {
        val morgen = Gesundheitsrechnung.tagesbeginn(jetzt, zone)
        val frueheste = dosen.map { it.first }.filter { !it.isAfter(jetzt) && it.isAfter(morgen.minus(Duration.ofHours(6))) }.minOrNull()
        val von = if (frueheste != null && frueheste.isBefore(morgen)) volleStunde(frueheste, zone, auf = false) else morgen
        val bettHeute = Gesundheitsrechnung.bettzeitHeute(einschlafen, jetzt, zone)
        val bis = volleStunde(maxOf(bettHeute.plus(NACH_BETT), jetzt.plus(NACH_JETZT)), zone, auf = true)
        val zeiten = sortedSetOf(von, bis, jetzt)
        var t = von
        while (t.isBefore(bis)) {
            zeiten += t
            t = t.plus(SCHRITT)
        }
        dosen.map { it.first }.filter { it.isAfter(von) && it.isBefore(bis) }.forEach {
            zeiten += it.minusSeconds(1)
            zeiten += it
        }
        return Koffeinkurve(
            von, bis, jetzt, bettHeute.takeIf { it.isAfter(jetzt) },
            zeiten.map { it to Gesundheitsrechnung.koffeinAktiv(dosen, it) },
        )
    }

    /** Die volle Stunde vor ([auf] = false) oder nach [t] in der Ortszeit; eine volle Stunde bleibt, wie sie ist. */
    private fun volleStunde(t: Instant, zone: ZoneId, auf: Boolean): Instant {
        val unten = t.atZone(zone).truncatedTo(ChronoUnit.HOURS).toInstant()
        return if (!auf || unten == t) unten else unten.plus(Duration.ofHours(1))
    }

    /** Wie oft die Woche das Ziel erreichte - ein Tag zaehlt mit, sobald er es voll hat. */
    fun zielErreicht(woche: List<Pair<LocalDate, Int>>, zielMl: Int): Int = woche.count { it.second >= zielMl }
}
