package coredevices.pebble.kiesel

import java.time.Duration
import java.time.Instant
import kotlin.math.abs

/**
 * Was sich aus Strecke und Puls eines Trainings herausrechnen laesst.
 *
 * NUR RECHNEN, NICHTS ZEICHNEN. Die Bilder stehen in der Trainingsansicht;
 * hier steht, was sie zeigen - und das laesst sich pruefen, ohne einen Schirm.
 *
 * DIE STRECKE IST DIE ACHSE, nicht die Zeit. Wer eine Fahrt nachschlaegt,
 * denkt in Kilometern: "am Anstieg bei Kilometer vier". Puls, Tempo und Hoehe
 * stehen deshalb ueber der Strecke, und die Zeit ist nur noch eine Zahl je
 * Kilometer.
 */
internal object Trainingsanalyse {

    /** Ein Punkt der Strecke mit allem, was sich bis dorthin ergibt. */
    data class Streckenpunkt(
        val meter: Double,      //< seit dem Start
        val sekunde: Long,      //< seit dem Start
        val hoehe: Double?,
        val tempo: Double,      //< m/s, geglaettet
        val lat: Double,
        val lon: Double,
        /** Liegt ein Sprung davor (verlorener Empfang)? Die Karte reisst dort ab. */
        val nachSprung: Boolean = false,
    )

    /**
     * Die Strecke mit Distanz und geglaettetem Tempo je Punkt.
     *
     * GEGLAETTET UEBER ZEHN SEKUNDEN: GPS springt um Meter, und aus zwei
     * Punkten im Sekundenabstand wird ein Tempo, das zwischen 10 und 40 km/h
     * flattert. Ueber ein Fenster von rund zehn Sekunden wird daraus die
     * Fahrt, die man wirklich gemacht hat.
     *
     * EIN SPRUNG ZAEHLT NICHT ALS WEG - wie bei Spur.laenge, sonst stuende
     * in der Kilometertabelle eine andere Strecke als oben in der Karte.
     */
    fun strecke(punkte: List<Spur.Punkt>): List<Streckenpunkt> {
        if (punkte.size < 2) return emptyList()
        val start = punkte.first().zeit
        val weg = Spur.weg(punkte)
        // Ausreisser (NaN in weg) sind weder Weg noch Ort - sie fehlen hier.
        val roh = punkte.indices.filter { !weg[it].isNaN() }.map { i ->
            val p = punkte[i]
            val sprung = i > 0 && Spur.abstand(punkte[i - 1], p) > Spur.SPRUNG_MAX
            Streckenpunkt(weg[i], p.zeit - start, p.hoehe, 0.0, p.lat, p.lon, sprung)
        }
        if (roh.size < 2) return emptyList()
        val geglaettet = roh.mapIndexed { i, p ->
            // Das Fenster: alle Punkte, die hoechstens fuenf Sekunden vor
            // und nach diesem liegen - aber nicht ueber einen Sprung: dort
            // vergeht Zeit ohne Weg, und das Tempo fiele auf null.
            var a = i
            while (a > 0 && !roh[a].nachSprung && p.sekunde - roh[a - 1].sekunde <= 5) a--
            var b = i
            while (b < roh.size - 1 && !roh[b + 1].nachSprung && roh[b + 1].sekunde - p.sekunde <= 5) b++
            val dt = (roh[b].sekunde - roh[a].sekunde)
            val tempo = if (dt > 0) (roh[b].meter - roh[a].meter) / dt else 0.0
            p.copy(tempo = tempo.coerceIn(0.0, 30.0))
        }
        // DER MEDIAN UEBER DREI NACHBARN NIMMT DIE SPITZE: ein einzelner
        // GPS-Versatz bleibt auch im Fenster als Zacken auf 35 km/h stehen
        // und wurde zur "Spitze" des Tages. Eine echte Abfahrt dauert laenger
        // als ein Punkt und bleibt.
        return geglaettet.mapIndexed { i, p ->
            val vor = geglaettet.getOrNull(i - 1)?.takeIf { !p.nachSprung }
            val nach = geglaettet.getOrNull(i + 1)?.takeIf { !it.nachSprung }
            if (vor == null || nach == null) p
            else p.copy(tempo = listOf(vor.tempo, p.tempo, nach.tempo).sorted()[1])
        }
    }

    // --- Kalorien ---

    /**
     * Der Verbrauch aus dem Puls, in kcal: Anteil der Pulsreserve = Anteil der
     * Sauerstoffreserve (Swain 1997), 5 kcal je Liter Sauerstoff.
     *
     * NICHT MEHR KEYTEL: der rechnet mit dem Puls an sich und gab fuer eine
     * Radfahrt mit Puls 127 rund 250 kcal, Fitbit 180. Die Reserve zaehlt vom
     * eigenen Ruhepuls aus. DIE VO2MAX IST DIE MITTE DER ALTERSGRUPPE (FRIEND,
     * Kaminsky 2015), nicht Uth: der teilt durch den Ruhepuls, und der aus dem
     * Schlaf liegt unter dem wachen - 53 statt ~40 und wieder 240 kcal.
     * In der Reserve wiegt derselbe Unterschied wenig (Ruhepuls 60 statt 54:
     * 4 % weniger), deshalb gilt dort der Schlafpuls.
     *
     * GEPRUEFT AN EINER FAHRT: Norm und Uth unterscheiden sich gerade in dem,
     * was dort passt. Wer fitter ist als die Mitte, bekommt zu wenig.
     */
    fun kalorien(
        puls: List<Pulspunkt>, gewichtKg: Double, alter: Int, geschlecht: Geschlecht,
        ruhepuls: Int?, maxpuls: Int?,
    ): Double? {
        if (puls.isEmpty() || gewichtKg < 20 || alter <= 0) return null
        val ruhe = (ruhepuls?.takeIf { it in 30..110 } ?: RUHEPULS_ERSATZ).toDouble()
        // Den Maximalpuls prueft allein Herzgrenzen - eine zweite Pruefung hier verwarf
        // einen Wert, mit dem die Zonen derselben Seite rechneten. Ohne Wert Tanaka.
        val max = maxpuls?.toDouble() ?: (208 - 0.7 * alter)
        if (max <= ruhe + 20) return null
        val vo2max = vo2maxMitte(alter, geschlecht)
        var kcal = 0.0
        for (i in puls.indices) {
            // Unter dem Ruhepuls bleibt der Ruheumsatz, ueber dem Maximum die VO2max.
            val anteil = ((puls[i].bpm - ruhe) / (max - ruhe)).coerceIn(0.0, 1.0)
            val vo2 = VO2_RUHE + anteil * (vo2max - VO2_RUHE)   // ml je kg und Minute
            kcal += vo2 * gewichtKg / 1000 * KCAL_JE_LITER * minuten(puls, i)
        }
        return kcal
    }

    /**
     * Die aktiven Kalorien fuer die Akte: [kalorien] ohne den Ruheumsatz.
     *
     * DIE AKTE ZAEHLT DEN RUHEUMSATZ SELBST (Grundumsatz). Stuende er auch
     * hier drin, zaehlte die Tagessumme die Trainingsminuten doppelt.
     */
    fun aktiveKalorien(
        puls: List<Pulspunkt>, gewichtKg: Double, alter: Int, geschlecht: Geschlecht,
        ruhepuls: Int?, maxpuls: Int?,
    ): Double? {
        val gesamt = kalorien(puls, gewichtKg, alter, geschlecht, ruhepuls, maxpuls) ?: return null
        val ruhe = puls.indices.sumOf { VO2_RUHE * gewichtKg / 1000 * KCAL_JE_LITER * minuten(puls, it) }
        return (gesamt - ruhe).coerceAtLeast(0.0)
    }

    /** Wie lange ein Pulspunkt gilt, in Minuten: bis zum naechsten, hoechstens eine. */
    private fun minuten(puls: List<Pulspunkt>, i: Int): Double =
        (if (i + 1 < puls.size) (puls[i + 1].sekunde - puls[i].sekunde).coerceIn(0, 60) else 10L) / 60.0

    /** Sauerstoff in Ruhe (1 MET), ml je kg und Minute. */
    const val VO2_RUHE = 3.5
    const val KCAL_JE_LITER = 5.0
    /** Ruhepuls, solange keine Nacht einen liefert - ein ueblicher Wert, eher wach als im Schlaf. */
    const val RUHEPULS_ERSATZ = 60

    /** VO2max in der Mitte (50. Perzentil) je Altersjahrzehnt, Mitte der Jahrzehnte 25 ... 75. */
    private val VO2MAX_MANN = doubleArrayOf(48.0, 42.4, 37.8, 32.6, 28.2, 24.4)
    private val VO2MAX_FRAU = doubleArrayOf(37.6, 30.9, 27.9, 24.3, 21.7, 19.0)

    fun vo2maxMitte(alter: Int, geschlecht: Geschlecht): Double {
        fun aus(t: DoubleArray): Double {
            val x = ((alter - 25) / 10.0).coerceIn(0.0, t.size - 1.0)
            val i = x.toInt().coerceAtMost(t.size - 2)
            return t[i] + (t[i + 1] - t[i]) * (x - i)
        }
        return when (geschlecht) {
            Geschlecht.Mann -> aus(VO2MAX_MANN)
            Geschlecht.Frau -> aus(VO2MAX_FRAU)
            Geschlecht.Andere -> (aus(VO2MAX_MANN) + aus(VO2MAX_FRAU)) / 2
        }
    }

    /**
     * Der Ruhepuls fuer die Kalorien: der Median der letzten Naechte.
     *
     * DER MEDIAN, weil eine Nacht nach Alkohol oder mit Fieber fuenf Schlaege
     * hoeher liegt und sonst jedes Training dieser Woche billiger machte.
     */
    fun ruhepulsAusNaechten(werte: List<Int>, naechte: Int = 7): Int? {
        val gute = werte.filter { it > 0 }.takeLast(naechte).sorted()
        if (gute.isEmpty()) return null
        val m = gute.size / 2
        return if (gute.size % 2 == 1) gute[m] else (gute[m - 1] + gute[m]) / 2
    }

    enum class Geschlecht { Mann, Frau, Andere }

    // --- Puls der Sitzung ---

    /**
     * Die Pulspunkte einer Sitzung aus den Messungen der Akte: nur [von, bis),
     * nach der Zeit, die Sekunde ab [von].
     *
     * DASSELBE FENSTER WIE DIE ROUTE: Ansicht und Kalorien in der Akte rechnen
     * mit genau diesen Punkten. Ein Pulswert nach dem Ende gehoert nicht mehr
     * zum Training, auch wenn sein Eintrag noch hineinreicht.
     */
    fun pulsInSitzung(messungen: List<Pair<Instant, Long>>, von: Instant, bis: Instant): List<Pulspunkt> =
        messungen.filter { (t, _) -> !t.isBefore(von) && t.isBefore(bis) }
            .sortedBy { it.first }
            .map { (t, bpm) -> Pulspunkt(Duration.between(von, t).seconds, bpm) }

    // --- Aussetzer des Sensors ---

    /** Ein Einbruch um mehr als so viel unter den letzten guten Wert ... */
    const val EINBRUCH_BPM = 25
    /** ... der binnen so vieler Sekunden dorthin zurueckkehrt (drei Kurvenpunkte der Uhr) ... */
    const val EINBRUCH_S = 45L
    /** ... wobei "dorthin" so viel Spiel hat. */
    const val RUECKKEHR_BPM = 15

    /**
     * Kurze Einbrueche aus der Pulskurve nehmen.
     *
     * DER SENSOR VERLIERT AM LENKER KURZ DEN KONTAKT und meldet dann zehn,
     * zwanzig Sekunden lang einen Wert weit unter dem Puls - oft den Tritt
     * statt des Herzens. Die Kurve faellt auf 90 und springt zurueck; ein
     * Herz macht das nicht. Solche Punkte fallen weg: die Kurve laeuft
     * darueber hinweg, und Schnitt, Maximum und Zonen rechnen ohne sie.
     *
     * NUR NACH UNTEN: der Anstieg am Anfang eines Trainings ist echt und
     * schnell, und wer sprintet, hat den Sprung nach oben verdient. Ein
     * echter Abfall (Ampel, Pause) kommt nicht binnen einer halben Minute
     * zurueck und bleibt ebenfalls.
     */
    fun entstoert(puls: List<Pulspunkt>): List<Pulspunkt> {
        if (puls.size < 3) return puls
        val aus = ArrayList<Pulspunkt>(puls.size)
        var i = 0
        while (i < puls.size) {
            val p = puls[i]
            val gut = aus.lastOrNull()
            if (gut == null || gut.bpm - p.bpm <= EINBRUCH_BPM) {
                aus += p
                i++
                continue
            }
            // Ein Sprung nach unten: kommt die Kurve gleich wieder hoch?
            var j = i + 1
            while (j < puls.size && puls[j].sekunde - gut.sekunde <= EINBRUCH_S && abs(puls[j].bpm - gut.bpm) > RUECKKEHR_BPM) j++
            val aussetzer = j < puls.size && puls[j].sekunde - gut.sekunde <= EINBRUCH_S
            if (aussetzer) {
                i = j
            } else {
                aus += p
                i++
            }
        }
        return aus
    }

    // --- Pulszonen ---

    /** Die Zonen wie auf der Uhr: Anteile des Maximalpulses. */
    val ZONEN_PROZENT = intArrayOf(50, 60, 70, 80, 90)

    fun zone(bpm: Long, maxpuls: Int): Int {
        for (z in 5 downTo 1) {
            if (bpm >= maxpuls * ZONEN_PROZENT[z - 1] / 100) return z
        }
        return 0
    }

    /**
     * Sekunden je Zone, Index 0 = unter Zone 1, 1..5 = Zone.
     *
     * JEDER PUNKT GILT BIS ZUM NAECHSTEN, hoechstens eine Minute lang: ein
     * Loch in der Kurve - Sensor am Lenker - soll nicht eine Viertelstunde
     * einer Zone zuschreiben, die niemand gemessen hat.
     */
    fun zonenSekunden(puls: List<Pulspunkt>, maxpuls: Int): LongArray {
        val aus = LongArray(6)
        for (i in puls.indices) {
            val dauer = if (i + 1 < puls.size) (puls[i + 1].sekunde - puls[i].sekunde).coerceIn(0, 60) else 10L
            aus[zone(puls[i].bpm, maxpuls)] += dauer
        }
        return aus
    }

    // --- Puls ueber die Strecke ---

    /** Ein Wert ueber der Strecke: bei so vielen Metern dieser Wert. */
    data class Verlaufspunkt(val meter: Double, val wert: Double)

    /**
     * Den Puls von der Zeit auf die Strecke legen.
     *
     * Zu jedem Pulspunkt die Distanz, die zu seiner Sekunde erreicht war -
     * zwischen zwei Streckenpunkten geradlinig. Was vor dem ersten oder nach
     * dem letzten Streckenpunkt liegt, faellt weg: dort weiss niemand, wo
     * man war.
     */
    fun pulsUeberStrecke(puls: List<Pulspunkt>, strecke: List<Streckenpunkt>): List<Verlaufspunkt> {
        if (strecke.size < 2 || puls.isEmpty()) return emptyList()
        val aus = ArrayList<Verlaufspunkt>(puls.size)
        var j = 0
        for (p in puls) {
            while (j < strecke.size - 2 && strecke[j + 1].sekunde < p.sekunde) j++
            val a = strecke[j]
            val b = strecke[j + 1]
            if (p.sekunde < a.sekunde || p.sekunde > b.sekunde) continue
            val t = if (b.sekunde > a.sekunde) (p.sekunde - a.sekunde).toDouble() / (b.sekunde - a.sekunde) else 0.0
            aus += Verlaufspunkt(a.meter + (b.meter - a.meter) * t, p.bpm.toDouble())
        }
        return aus
    }

    /** Das Tempo ueber die Strecke, in km/h. */
    fun tempoUeberStrecke(strecke: List<Streckenpunkt>): List<Verlaufspunkt> =
        strecke.map { Verlaufspunkt(it.meter, it.tempo * 3.6) }

    /** Die Hoehe ueber die Strecke - leer, wenn das GPS keine lieferte. */
    fun hoeheUeberStrecke(strecke: List<Streckenpunkt>): List<Verlaufspunkt> {
        val mit = strecke.filter { it.hoehe != null }
        if (mit.size < strecke.size / 2) return emptyList()
        // Geglaettet ueber fuenf Punkte: die Hoehe aus dem GPS zittert um
        // Meter, und ein Profil, das zittert, liest sich als Huegel.
        return mit.indices.map { i ->
            val a = (i - 2).coerceAtLeast(0)
            val b = (i + 2).coerceAtMost(mit.size - 1)
            Verlaufspunkt(mit[i].meter, (a..b).map { mit[it].hoehe!! }.average())
        }
    }

    // --- Kilometer ---

    data class Kilometer(
        val nummer: Int,        //< 1 = der erste
        val meter: Double,      //< meist 1000, der letzte weniger
        val sekunden: Long,
        val pulsMittel: Double?,
        val aufstieg: Double,
    )

    /**
     * Die Strecke in Kilometern, mit Zeit, Puls und Aufstieg je Stueck.
     *
     * Der letzte Rest zaehlt, wenn er wenigstens hundert Meter lang ist;
     * dreissig Meter Auslaufen sind kein Kilometer.
     */
    fun kilometer(strecke: List<Streckenpunkt>, puls: List<Pulspunkt>): List<Kilometer> {
        if (strecke.size < 2) return emptyList()
        val aus = mutableListOf<Kilometer>()
        var startIndex = 0
        var nummer = 1
        var i = 1
        while (i < strecke.size) {
            val grenze = nummer * 1000.0
            val letzter = i == strecke.size - 1
            if (strecke[i].meter >= grenze || letzter) {
                val von = strecke[startIndex]
                val bis = strecke[i]
                val laenge = bis.meter - von.meter
                if (laenge >= 100) {
                    val pulsHier = puls.filter { it.sekunde in von.sekunde..bis.sekunde }
                    var auf = 0.0
                    for (k in startIndex + 1..i) {
                        val h0 = strecke[k - 1].hoehe
                        val h1 = strecke[k].hoehe
                        if (h0 != null && h1 != null && h1 > h0) auf += h1 - h0
                    }
                    aus += Kilometer(
                        nummer, laenge, bis.sekunde - von.sekunde,
                        pulsHier.takeIf { it.isNotEmpty() }?.map { it.bpm }?.average(),
                        auf,
                    )
                }
                startIndex = i
                nummer++
            }
            i++
        }
        return aus
    }

    /**
     * Die Steigung je Punkt in Prozent, ueber rund fuenfzig Meter Strecke.
     *
     * Aus zwei Nachbarpunkten waere es Rauschen: GPS-Hoehe zittert um Meter,
     * und zwei Meter auf drei Meter Weg waeren siebzig Prozent.
     */
    fun steigung(strecke: List<Streckenpunkt>): List<Double> {
        if (strecke.size < 2 || strecke.count { it.hoehe != null } < strecke.size / 2) {
            return strecke.map { 0.0 }
        }
        return strecke.indices.map { i ->
            var a = i
            while (a > 0 && strecke[i].meter - strecke[a - 1].meter < 25) a--
            var b = i
            while (b < strecke.size - 1 && strecke[b + 1].meter - strecke[i].meter < 25) b++
            val ha = strecke[a].hoehe
            val hb = strecke[b].hoehe
            val dm = strecke[b].meter - strecke[a].meter
            if (ha == null || hb == null || dm < 5) 0.0 else ((hb - ha) / dm * 100).coerceIn(-40.0, 40.0)
        }
    }

    /** Der Puls zu einer Sekunde - der naechste Messpunkt, hoechstens eine Minute weg. */
    fun pulsBei(puls: List<Pulspunkt>, sekunde: Long): Long? =
        puls.minByOrNull { abs(it.sekunde - sekunde) }
            ?.takeIf { abs(it.sekunde - sekunde) <= 60 }?.bpm

    /** Der mittlere Puls in einer Spanne, null wenn keiner drin liegt. */
    fun pulsMittel(puls: List<Pulspunkt>, von: Long, bis: Long): Double? =
        puls.filter { it.sekunde in von..bis }.takeIf { it.isNotEmpty() }?.map { it.bpm }?.average()

    /**
     * Wie schnell der Puls in den Pausen faellt, in Schlaegen je Minute.
     *
     * DAS IST DIE ZAHL, DIE BEIM KRAFTTRAINING ETWAS SAGT: nicht der Puls im
     * Satz, sondern wie schnell er danach wieder unten ist. Je Pause der
     * Abfall zwischen Anfang und Ende, auf die Minute gerechnet, im Mittel.
     */
    fun erholung(puls: List<Pulspunkt>, pausen: List<Pair<Long, Long>>): Double? {
        val werte = pausen.mapNotNull { (von, bis) ->
            if (bis - von < 30) return@mapNotNull null
            val a = pulsBei(puls, von) ?: return@mapNotNull null
            val b = pulsBei(puls, bis) ?: return@mapNotNull null
            (a - b).toDouble() / ((bis - von) / 60.0)
        }
        return werte.takeIf { it.isNotEmpty() }?.average()
    }

    /** Wie gleichmaessig Werte sind: die Standardabweichung. */
    fun schwankung(werte: List<Double>): Double {
        if (werte.size < 2) return 0.0
        val m = werte.average()
        return Math.sqrt(werte.sumOf { (it - m) * (it - m) } / werte.size)
    }

    /**
     * Die Sekunden in Bewegung: Stuecke mit mehr als 0,5 m/s. HALT AN DER
     * AMPEL ZAEHLT NICHT, und ein Loch in der Spur (mehr als eine Minute
     * ohne Punkt) auch nicht - dort weiss niemand, ob man fuhr.
     */
    fun bewegt(strecke: List<Streckenpunkt>): Long {
        var summe = 0L
        for (i in 1 until strecke.size) {
            val dt = strecke[i].sekunde - strecke[i - 1].sekunde
            if (dt in 1..60 && !strecke[i].nachSprung && strecke[i].tempo > 0.5) summe += dt
        }
        return summe
    }

    /** Zwischen welchen Werten das Tempo liegt, ohne die Ausreisser oben. */
    fun tempoSpanne(strecke: List<Streckenpunkt>): Pair<Double, Double> {
        val werte = strecke.map { it.tempo }.filter { it > 0.3 }.sorted()
        if (werte.isEmpty()) return 0.0 to 1.0
        val unten = werte[(werte.size * 0.05).toInt().coerceIn(0, werte.size - 1)]
        val oben = werte[(werte.size * 0.95).toInt().coerceIn(0, werte.size - 1)]
        return if (abs(oben - unten) < 0.1) unten to unten + 1.0 else unten to oben
    }
}

/** Ein Pulswert eines Trainings: Sekunde ab Beginn, Schlaege je Minute. */
internal data class Pulspunkt(val sekunde: Long, val bpm: Long)
