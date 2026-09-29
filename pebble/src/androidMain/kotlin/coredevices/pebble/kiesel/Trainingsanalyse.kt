package coredevices.pebble.kiesel

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
    )

    /**
     * Die Strecke mit Distanz und geglaettetem Tempo je Punkt.
     *
     * GEGLAETTET UEBER ZEHN SEKUNDEN: GPS springt um Meter, und aus zwei
     * Punkten im Sekundenabstand wird ein Tempo, das zwischen 10 und 40 km/h
     * flattert. Ueber ein Fenster von rund zehn Sekunden wird daraus die
     * Fahrt, die man wirklich gemacht hat.
     */
    fun strecke(punkte: List<Spur.Punkt>): List<Streckenpunkt> {
        if (punkte.size < 2) return emptyList()
        val start = punkte.first().zeit
        var meter = 0.0
        val roh = ArrayList<Streckenpunkt>(punkte.size)
        punkte.forEachIndexed { i, p ->
            if (i > 0) meter += Spur.abstand(punkte[i - 1], p)
            roh += Streckenpunkt(meter, p.zeit - start, p.hoehe, 0.0, p.lat, p.lon)
        }
        return roh.mapIndexed { i, p ->
            // Das Fenster: alle Punkte, die hoechstens fuenf Sekunden vor
            // und nach diesem liegen.
            var a = i
            while (a > 0 && p.sekunde - roh[a - 1].sekunde <= 5) a--
            var b = i
            while (b < roh.size - 1 && roh[b + 1].sekunde - p.sekunde <= 5) b++
            val dt = (roh[b].sekunde - roh[a].sekunde)
            val tempo = if (dt > 0) (roh[b].meter - roh[a].meter) / dt else 0.0
            p.copy(tempo = tempo.coerceIn(0.0, 30.0))
        }
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
