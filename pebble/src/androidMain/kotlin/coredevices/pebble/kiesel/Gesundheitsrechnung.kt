package coredevices.pebble.kiesel

import io.rebble.libpebblecommon.connection.Pulsreihe
import io.rebble.libpebblecommon.database.entity.NachtAbschnittEntity
import io.rebble.libpebblecommon.database.entity.NachtEntity
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.pow
import kotlin.math.roundToInt

/** Messungen mit weniger Abstand gehoeren zu einer Sitzung an der Manschette. */
internal const val BLUTDRUCK_SITZUNG_S = 15 * 60L

/** Fuenf Minuten Puls: der mittlere Wert und die Spanne, in der er lag. */
internal data class Pulsbin(val sekunde: Long, val median: Int, val tiefster: Int, val hoechster: Int)

/** Was sich an den Gesundheitsdaten rechnen laesst - ohne Android, damit es pruefbar ist. */
internal object Gesundheitsrechnung {
    /**
     * Der Puls eines Tages, nur wach und bis zur selben Uhrzeit wie jetzt.
     * [wachpuls]: der Schnitt der wachen Messungen. [belastung]: Minuten mit
     * erhoehtem Puls, gewichtet wie Zone-Minuten - Zone moderat einfach,
     * kraeftig und Spitze doppelt. Jede Messung steht fuer die Minuten bis zur
     * naechsten, hoechstens zehn: die Uhr misst je nach Messstufe nicht jede
     * Minute.
     */
    data class Tagesherz(val tag: LocalDate, val wachpuls: Int?, val belastung: Int, val messungen: Int)

    /** Weniger wache Messungen tragen keinen Tagesschnitt. */
    const val TAGESHERZ_MINDESTENS = 24

    /**
     * Heute gegen die frueheren Tage bis zur selben Uhrzeit. [abstand]: der
     * Wachpuls gegen seinen Schnitt, fuer die Herzzeile. [haerte]: allein die
     * Belastung gegen ihren Median - der Satz sagt "more load than usual".
     * Frueher entschied ohne Belastung der Wachpuls mit, und der Satz sprach
     * von einem "harder day", waehrend die Herzzeile "Day as usual" sagte.
     * Ohne Urteil, solange weniger als [Fenster.TAGESHERZ_LERNTAGE] Tage da sind.
     */
    data class Tagesurteil(val heute: Tagesherz, val abstand: Int?, val haerte: Vergleich?)

    fun tagesurteil(tage: List<Tagesherz>, heute: LocalDate): Tagesurteil? {
        val jetzt = tage.firstOrNull { it.tag == heute }?.takeIf { it.wachpuls != null } ?: return null
        val frueher = tage.filter { it.tag < heute && it.wachpuls != null }
        if (frueher.size < Fenster.TAGESHERZ_LERNTAGE) return Tagesurteil(jetzt, null, null)
        val schnitt = frueher.map { it.wachpuls!! }.average().roundToInt()
        val abstand = jetzt.wachpuls!! - schnitt
        // Der Median, weil ein einzelner Trainingstag den Schnitt der Belastung verzerrt.
        // Der obere, wie immer schon: bei [10, 20, 30, 40] gilt 30, nicht 25.
        val last = Statistik.obererMedian(frueher.map { it.belastung })
        val haerte = if (last >= 10) when {
            jetzt.belastung >= maxOf(last * 3 / 2, last + 15) -> Vergleich.HOEHER
            jetzt.belastung <= last / 2 -> Vergleich.TIEFER
            else -> Vergleich.WIE_IMMER
        } else {
            // Kaum Belastung als Mass: weniger als fast nichts gibt es nicht, mehr erst ab 20 Minuten.
            if (jetzt.belastung >= 20) Vergleich.HOEHER else Vergleich.WIE_IMMER
        }
        return Tagesurteil(jetzt, abstand, haerte)
    }

    /**
     * [grenzen]: die Zonengrenzen aus [pulsZonen]. Dieselben gerundeten
     * Grenzen wie das Pulsband - mit dem exakten Anteil der Reserve zaehlte
     * ein Puls auf der Grenze hier einfach, waehrend die Kurve ihn schon in
     * der naechsten Zone faerbte.
     *
     * OHNE NACHT ZAEHLT VOR SECHS NICHTS ALS WACH: die Uhr kann am Arm
     * gewesen sein, ohne dass eine Nacht auf dem Telefon ist, und ihr
     * Schlafpuls drueckte den Schnitt ("Day 6 below usual"). Der Rest des
     * Tages zaehlt - wer die Uhr nachts laedt und um halb sechs anlegt,
     * behaelt seinen Tagesschnitt. In die Belastung zaehlt alles: Schlafpuls
     * liegt unter der ersten Zone und wiegt ohnehin nichts.
     *
     * [heute] OHNE NACHT KEIN WACHPULS, wenn vor sechs gemessen wurde und es
     * vor [NACHT_SPAETESTENS_H] Uhr ist: die Nacht ist dann vielleicht nur
     * noch nicht auf dem Telefon, und der Schlaf nach sechs zaehlte als wach.
     * Spaeter hat die Uhr jede Nacht abgeschlossen.
     */
    fun tagesherz(
        puls: List<Pair<Long, Int>>,
        schlaf: List<Pair<Long, Long>>,
        grenzen: List<Int>,
        bisUhrzeit: java.time.LocalTime,
        zone: ZoneId,
        heute: LocalDate? = null,
    ): List<Tagesherz> {
        val sortiert = puls.filter { it.second > 0 }.sortedBy { it.first }
        val zeiten = LongArray(sortiert.size) { sortiert[it].first }
        val werte = IntArray(sortiert.size) { sortiert[it].second }
        return tagesherzKern(zeiten, werte, 0, schlaf, grenzen, bisUhrzeit, zone, heute)
    }

    /**
     * Dasselbe aus der [Pulsreihe] der Datenbank, ab der Sekunde [ab]: ohne
     * Pair je Minute - 14 Tage sind bis zu 20'160 Minuten.
     */
    fun tagesherz(
        reihe: Pulsreihe,
        ab: Long,
        schlaf: List<Pair<Long, Long>>,
        grenzen: List<Int>,
        bisUhrzeit: java.time.LocalTime,
        zone: ZoneId,
        heute: LocalDate? = null,
    ): List<Tagesherz> {
        val r = aufsteigend(reihe)
        var start = 0
        while (start < r.size && r.zeiten[start] < ab) start++
        return tagesherzKern(r.zeiten, r.werte, start, schlaf, grenzen, bisUhrzeit, zone, heute)
    }

    /** Die Reihe nach Zeit, nur Minuten mit Puls - aus der Datenbank kommt sie schon so, dann ohne Kopie. */
    private fun aufsteigend(r: Pulsreihe): Pulsreihe {
        val geordnet = (1 until r.size).all { r.zeiten[it] >= r.zeiten[it - 1] }
        if (geordnet && r.werte.all { it > 0 }) return r
        // Stabil wie sortedBy: gleiche Zeiten behalten ihre Reihenfolge.
        val i = (0 until r.size).filter { r.werte[it] > 0 }.sortedBy { r.zeiten[it] }
        return Pulsreihe(LongArray(i.size) { r.zeiten[i[it]] }, IntArray(i.size) { r.werte[i[it]] })
    }

    /** Was ein Tag im Tagesherz sammelt; veraenderlich, damit nicht jede Minute ein Triple entsteht. */
    private class Tagessumme(var summe: Long = 0, var anzahl: Int = 0, var belastung: Double = 0.0)

    /**
     * Der Kern von [tagesherz] ueber [zeiten] und [werte] ab [start], nach
     * Zeit geordnet und nur mit Puls. SCHLAF UND TAG MIT ZEIGERN: frueher
     * pruefte jede Minute alle Naechte (schlaf.any) und baute ein
     * ZonedDateTime - 20'000 Objekte je Laden. Jetzt wandert ein Zeiger
     * durch die vereinten Schlafspannen, und die Ortszeit kommt aus dem
     * Versatz der Zone, der nur an ihren Wechseln neu geholt wird. Das
     * Ergebnis ist dasselbe, auch ueber die Zeitumstellung (TagesherzTest).
     */
    private fun tagesherzKern(
        zeiten: LongArray, werte: IntArray, start: Int,
        schlaf: List<Pair<Long, Long>>, grenzen: List<Int>, bisUhrzeit: LocalTime, zone: ZoneId, heute: LocalDate?,
    ): List<Tagesherz> {
        val n = zeiten.size
        val spannen = vereint(schlaf)
        val mitNacht = schlaf.map { Instant.ofEpochSecond(it.second).atZone(zone).toLocalDate() }.toSet()
        // isAfter(bisUhrzeit) fuer volle Sekunden: erst die naechste ganze Sekunde liegt danach.
        val bisSekunde = bisUhrzeit.toSecondOfDay()
        val regeln = zone.rules
        val jeTag = HashMap<LocalDate, Tagessumme>()
        val nachtsGemessen = mutableSetOf<LocalDate>()
        var s = 0
        var versatz = 0L
        var versatzBis = Long.MIN_VALUE
        var tagNr = Long.MIN_VALUE
        var tag = LocalDate.MIN
        for (i in start until n) {
            val sek = zeiten[i]
            // Die Spannen sind vereint und geordnet: was vor dieser Minute endet, kommt nie wieder.
            while (s < spannen.size && spannen[s].second <= sek) s++
            if (s < spannen.size && spannen[s].first <= sek) continue
            if (sek >= versatzBis) {
                val jetzt = Instant.ofEpochSecond(sek)
                versatz = regeln.getOffset(jetzt).totalSeconds.toLong()
                versatzBis = regeln.nextTransition(jetzt)?.toEpochSecond() ?: Long.MAX_VALUE
            }
            // Wie LocalDateTime.ofEpochSecond: Ortszeit = UTC + Versatz, Tag und Sekunde daraus.
            val lokal = sek + versatz
            val sekundeDesTages = Math.floorMod(lokal, 86_400L)
            if (sekundeDesTages > bisSekunde) continue
            val nr = Math.floorDiv(lokal, 86_400L)
            if (nr != tagNr) {
                tagNr = nr
                tag = LocalDate.ofEpochDay(nr)
            }
            val vielleichtSchlaf = sekundeDesTages < TAGESBEGINN_H * 3600 && tag !in mitNacht
            if (vielleichtSchlaf) nachtsGemessen += tag
            val minuten = if (i + 1 < n) ((zeiten[i + 1] - sek) / 60.0).coerceIn(1.0, 10.0) else 1.0
            val gewicht = when (pulsZone(werte[i], grenzen)) {
                0 -> 0.0
                1 -> 1.0
                else -> 2.0
            }
            val w = jeTag.getOrPut(tag) { Tagessumme() }
            if (!vielleichtSchlaf) {
                w.summe += werte[i]
                w.anzahl++
            }
            w.belastung += gewicht * minuten
        }
        val wartet = heute != null && heute in nachtsGemessen && bisUhrzeit.hour < NACHT_SPAETESTENS_H
        return jeTag.map { (tag, w) ->
            val wach = if (w.anzahl >= TAGESHERZ_MINDESTENS && !(wartet && tag == heute)) (w.summe.toDouble() / w.anzahl).roundToInt() else null
            Tagesherz(tag, wach, w.belastung.roundToInt(), w.anzahl)
        }.sortedBy { it.tag }
    }

    /**
     * Die Schlafspannen [von, bis) vereint und geordnet: eine Minute liegt
     * genau dann in einer von ihnen, wenn sie in einem dieser Stuecke liegt.
     */
    private fun vereint(schlaf: List<Pair<Long, Long>>): List<Pair<Long, Long>> {
        val aus = mutableListOf<Pair<Long, Long>>()
        schlaf.filter { it.first < it.second }.sortedBy { it.first }.forEach { (von, bis) ->
            val letzte = aus.lastOrNull()
            if (letzte != null && von <= letzte.second) aus[aus.lastIndex] = letzte.first to maxOf(letzte.second, bis)
            else aus += von to bis
        }
        return aus
    }

    /**
     * Bis dahin kann die Nacht von heute noch kommen: das Nachtfenster der
     * Uhr endet um elf, eine offene Nacht schliesst sie bis eine Stunde
     * danach ab (boulder/nacht.c, NACHLAUF_MIN).
     */
    const val NACHT_SPAETESTENS_H = 12

    /** Halbwertszeit von Koffein, wie in Kiesel-Helper. */
    const val KOFFEIN_HALBWERT_MIN = 300.0

    fun phasenMinuten(abschnitte: List<NachtAbschnittEntity>): IntArray {
        val aus = IntArray(5)
        abschnitte.forEach { if (it.phase in 0..4) aus[it.phase] += it.bis - it.von }
        return aus
    }

    /**
     * Ob die Uhr in dieser Nacht Phasen erkannt hat - leicht, tief oder REM,
     * je Index wie [phasenMinuten]. Wach allein oder Schlaf ohne Phase ist
     * keine: dann gibt es weder Phasenzeile noch Tiefschlaf im Trend.
     */
    fun mitPhasen(phasen: IntArray): Boolean = phasen[1] + phasen[2] + phasen[3] > 0

    fun nacht(e: NachtEntity, abschnitte: List<NachtAbschnittEntity>): Nacht {
        val beginn = Instant.ofEpochSecond(e.beginnUtc)
        fun zeit(min: Int) = beginn.plusSeconds(min * 60L)
        return Nacht(
            beginnUtc = e.beginnUtc,
            einschlafen = zeit(e.einschlafen),
            aufwachen = zeit(e.aufwachen),
            schlafMinuten = e.schlafMinuten,
            phasen = phasenMinuten(abschnitte),
            abschnitte = abschnitte.map { Nacht.Abschnitt(zeit(it.von), zeit(it.bis), it.phase) },
            ruhepuls = e.ruhepuls.takeIf { it > 0 },
            hrv = e.hrv.takeIf { it > 0 },
        )
    }

    /** Jede Sitzung gemittelt, aelteste zuerst, und ab drei Sitzungen der Schnitt der Zeitspanne. */
    data class Blutdruckstand(val sitzungen: List<Blutdruck>, val schnitt: Pair<Int, Int>?) {
        val letzte: Blutdruck get() = sitzungen.last()
    }

    /**
     * Die Messungen in Sitzungen, aelteste zuerst: weniger als
     * [BLUTDRUCK_SITZUNG_S] nach der vorigen Messung gehoert eine Messung zur
     * selben. Manschetten-Apps speichern oft drei Messungen hintereinander -
     * einzeln gezaehlt wog ein Morgen dreifach und machte aus einem Mal
     * Messen schon "drei Messungen" fuer den Schnitt.
     */
    fun blutdrucksitzungen(messungen: List<Blutdruck>): List<List<Blutdruck>> {
        val aus = mutableListOf<MutableList<Blutdruck>>()
        messungen.sortedBy { it.zeit }.forEach { m ->
            val letzte = aus.lastOrNull()
            if (letzte == null || Duration.between(letzte.last().zeit, m.zeit).seconds >= BLUTDRUCK_SITZUNG_S) aus += mutableListOf(m) else letzte += m
        }
        return aus
    }

    fun blutdruckstand(messungen: List<Blutdruck>): Blutdruckstand? {
        val sitzungen = blutdrucksitzungen(messungen)
        if (sitzungen.isEmpty()) return null
        // Der Schnitt ueber die Sitzungsmittel, nicht ueber gerundete Werte - sonst rundete er zweimal.
        fun oben(s: List<Blutdruck>) = s.map { it.oben }.average()
        fun unten(s: List<Blutdruck>) = s.map { it.unten }.average()
        // Unter drei Sitzungen ist ein Schnitt nur die letzte mit anderem Namen.
        val schnitt = sitzungen.takeIf { it.size >= Fenster.SCHNITT_WERTE }?.let { l ->
            l.map(::oben).average().roundToInt() to l.map(::unten).average().roundToInt()
        }
        // Die Sitzung traegt die Zeit ihrer ersten Messung: da begann das Messen.
        return Blutdruckstand(sitzungen.map { Blutdruck(it.first().zeit, oben(it).roundToInt(), unten(it).roundToInt()) }, schnitt)
    }

    /** Die letzte Sitzung fuer die Herzzeile, wenn sie in den letzten [Fenster.BLUTDRUCK_ZEILE_TAGE] Kalendertagen liegt. */
    fun blutdruckFuerHerzzeile(messungen: List<Blutdruck>, jetzt: Instant, zone: ZoneId): Blutdruck? =
        blutdruckstand(messungen)?.letzte?.takeIf { tageZurueck(it.zeit, jetzt, zone) < Fenster.BLUTDRUCK_ZEILE_TAGE }

    /**
     * Die Blutdruckwoche der Herzseite: oben und unten je wie die HRV-Woche
     * ([bandwoche]) - ein Wert je Tag, jeder Tag mit seinem Band. [schnitt]:
     * der Schnitt der Sitzungen dieser sieben Tage wie in [blutdruckstand].
     */
    data class Blutdruckwoche(val oben: List<Bandtag>, val unten: List<Bandtag>, val schnitt: Pair<Int, Int>?) {
        /** Ob in diesen sieben Tagen gemessen wurde - nur dann zeigt die Herzseite die Woche. */
        val gemessen: Boolean get() = oben.any { it.wert != null }
    }

    /**
     * Die sieben Tage bis [heute] aus den [messungen] (mindestens
     * [Fenster.HRV_NAECHTE] Tage vor der Woche, sonst fehlt den ersten Tagen
     * ein Teil ihres Bands). Der Wert eines Tages ist der Schnitt seiner
     * Sitzungen - eine Sitzung zaehlt einmal, wie oft die Manschette darin
     * auch mass, und gehoert zum Tag ihrer ersten Messung.
     *
     * JE EIN BAND FUER OBEN UND UNTEN, nach der Regel der HRV: die beiden
     * Werte liegen rund 40 mmHg auseinander und schwanken je fuer sich.
     */
    fun blutdruckwoche(messungen: List<Blutdruck>, heute: LocalDate, zone: ZoneId): Blutdruckwoche {
        val sitzungen = blutdrucksitzungen(messungen)
        val jeTag = sitzungen.groupBy { it.first().zeit.atZone(zone).toLocalDate() }
        // Aus den ungerundeten Sitzungsmitteln, wie der Schnitt in blutdruckstand: gerundet wird einmal, am Ende.
        fun tageswerte(wert: (Blutdruck) -> Int) =
            jeTag.mapValues { (_, l) -> l.map { s -> s.map(wert).average() }.average().roundToInt() }
        val ersterTag = heute.minusDays(Fenster.WOCHE_TAGE - 1L)
        val woche = messungen.filter { !it.zeit.atZone(zone).toLocalDate().isBefore(ersterTag) }
        return Blutdruckwoche(
            bandwoche(tageswerte { it.oben }, heute),
            bandwoche(tageswerte { it.unten }, heute),
            blutdruckstand(woche)?.schnitt,
        )
    }

    /** Wieviel Koffein zum Zeitpunkt [jetzt] noch wirkt - jede Dosis halbiert sich alle fuenf Stunden. */
    fun koffeinAktiv(dosen: List<Pair<Instant, Double>>, jetzt: Instant): Double = dosen
        .filter { !it.first.isAfter(jetzt) }
        .sumOf { (wann, mg) -> mg * 0.5.pow(Duration.between(wann, jetzt).toMinutes() / KOFFEIN_HALBWERT_MIN) }

    /** Das Koffein des Kalendertags [heute] - eine Rechnung fuer Seite und Widget. */
    fun koffeinHeute(dosen: List<Pair<Instant, Double>>, heute: LocalDate, zone: ZoneId): Double =
        dosen.filter { it.first.atZone(zone).toLocalDate() == heute }.sumOf { it.second }

    /**
     * Was ein Ernaehrungseintrag ist - null, wenn er auf der Gesundheitsseite
     * nichts verloren hat. PRAEPARAT IST, WAS NICHTS ENTHAELT, oder SupCycles
     * Kennung. GETRAENK NUR MIT KOFFEIN ODER AUS BOULDER: eine Mahlzeit aus
     * einer Essens-App hat kcal, ist aber kein Getraenk und kein Kaffeepunkt.
     * [mahlzeit]: der Eintrag nennt eine Mahlzeit (mealType) - dann ist er
     * auch ohne kcal kein Praeparat.
     */
    fun einordnung(eigen: Boolean, kennung: String?, koffeinMg: Double?, kcal: Double?, mahlzeit: Boolean = false): Eintragsart? = when {
        SupCycle.istKennung(kennung) -> Eintragsart.PRAEPARAT
        eigen && (kennung?.startsWith("drinktervall-kaffee-") == true || kennung?.startsWith("koffein-") == true) -> Eintragsart.GETRAENK
        (koffeinMg ?: 0.0) > 0 -> Eintragsart.GETRAENK
        mahlzeit -> null
        (kcal ?: 0.0) <= 0 -> Eintragsart.PRAEPARAT
        else -> null
    }

    /** Werte je Tag auf eine lueckenlose Reihe bringen; fehlende Tage zaehlen 0. */
    fun <T : Number> reihe(tage: List<LocalDate>, werte: Map<LocalDate, T>, null_: T): List<Pair<LocalDate, T>> =
        tage.map { it to (werte[it] ?: null_) }

    /**
     * Der Tag, zu dem eine Nacht gehoert: der des Aufwachens. Nicht "Beginn
     * des Fensters plus ein Tag": eine Nacht, die die Uhr nach langem
     * Wachliegen in zwei Teile zerlegt hatte, lag damit zweimal auf demselben
     * Tag, und der zweite Teil ueberschrieb den ersten.
     */
    fun nachtTag(beginnUtc: Long, aufwachenMin: Int, zone: ZoneId): LocalDate =
        Instant.ofEpochSecond(beginnUtc + aufwachenMin * 60L).atZone(zone).toLocalDate()

    /**
     * Eine Nacht je Tag - die laengste. Aeltere Firmware schickte nach langem
     * Wachliegen zwei Naechte fuer denselben Morgen; die kuerzere waere sonst
     * der Schlaf des Tages.
     */
    fun jeTag(naechte: List<NachtEntity>, zone: ZoneId): List<NachtEntity> =
        naechte.groupBy { nachtTag(it.beginnUtc, it.aufwachen, zone) }.values
            .map { l -> l.maxBy { it.schlafMinuten } }.sortedBy { it.beginnUtc }

    /**
     * Der Puls in Fuenf-Minuten-Fenstern. Der Median statt des Mittels: ein
     * einzelner Ausreisser der Uhr soll die Linie nicht ziehen, die Spanne
     * zeigt ihn trotzdem. Die Bins liegen auf vollen Fuenf-Minuten-Marken.
     */
    fun fuenfMinuten(punkte: List<Pair<Long, Int>>): List<Pulsbin> =
        punkte.groupBy { it.first / 300 * 300 }.toSortedMap().map { (sekunde, l) ->
            val werte = l.map { it.second }
            // Der obere Median: er ist ein gemessener Puls, kein Mittel zweier.
            Pulsbin(sekunde, Statistik.obererMedian(werte), werte.min(), werte.max())
        }

    /**
     * Tiefster und hoechster Puls, wie die Linie des Pulsbands sie zeigt: aus
     * den Medianen der [fuenfMinuten], den Punkten der [pulslinie]. Aus den Rohwerten
     * bestimmte ein einzelner Ausreisser des Sensors Spanne und Zone im Satz,
     * und die Kurve darunter erreichte ihn nie.
     */
    fun pulsSpanne(punkte: List<Pair<Long, Int>>): Pair<Int, Int>? = spanneDerBins(fuenfMinuten(punkte))

    /** [pulsSpanne] aus schon gebildeten Bins - die Herzseite bildet sie einmal fuer Satz und Band. */
    fun spanneDerBins(bins: List<Pulsbin>): Pair<Int, Int>? {
        val mediane = bins.map { it.median }
        return if (mediane.isEmpty()) null else mediane.min() to mediane.max()
    }

    /**
     * Wann der hoechste Wert der [pulsSpanne] lag (Sekunde des Fensters) und
     * welcher es war - der juengste, wenn er mehrmals vorkommt. Die Herzseite
     * zeigt 24 Stunden: ohne Zeit las sich eine Spitze von gestern Abend als
     * heutige.
     */
    fun pulsSpitze(punkte: List<Pair<Long, Int>>): Pair<Long, Int>? = spitzeDerBins(fuenfMinuten(punkte))

    /** [pulsSpitze] aus schon gebildeten Bins, wie [spanneDerBins]. */
    fun spitzeDerBins(bins: List<Pulsbin>): Pair<Long, Int>? {
        val hoechster = bins.maxOfOrNull { it.median } ?: return null
        return bins.last { it.median == hoechster }.let { it.sekunde to it.median }
    }

    /**
     * Die Bettzeit als Zeitpunkt: die Uhrzeit des letzten Einschlafens, sonst
     * 22 Uhr, auf den laufenden Tag gelegt. Der Tag beginnt um sechs, damit
     * 00:30 die kommende Nacht meint und nicht die vergangene. Ist sie schon
     * vorbei, null: "um 22:54 wirken noch 1 mg" ohne Datum laese sich als
     * heute Abend, gemeint waere morgen.
     */
    fun bettzeit(einschlafen: Instant?, jetzt: Instant, zone: ZoneId): Instant? =
        bettzeitHeute(einschlafen, jetzt, zone).takeIf { it.isAfter(jetzt) }

    /**
     * Dieselbe Bettzeit des laufenden Tags, AUCH WENN SIE VORBEI IST: das
     * Tagesband reicht bis zu ihr, auch wenn man noch wach ist - dann zeigt
     * es sie nur nicht mehr als Mond.
     */
    fun bettzeitHeute(einschlafen: Instant?, jetzt: Instant, zone: ZoneId): Instant {
        val uhrzeit = einschlafen?.atZone(zone)?.toLocalTime()?.withSecond(0)?.withNano(0) ?: LocalTime.of(22, 0)
        val tag = jetzt.atZone(zone).minusHours(TAGESBEGINN_H).toLocalDate()
        val bett = if (uhrzeit.hour < TAGESBEGINN_H) tag.plusDays(1) else tag
        return bett.atTime(uhrzeit).atZone(zone).toInstant()
    }

    /**
     * Der Beginn des laufenden Tags um sechs - DERSELBE TAG, auf den
     * [bettzeitHeute] die Bettzeit legt: um 00:30 hat er gestern um sechs
     * begonnen. Die Koffeinkurve der Seite Stack beginnt hier.
     */
    fun tagesbeginn(jetzt: Instant, zone: ZoneId): Instant =
        jetzt.atZone(zone).minusHours(TAGESBEGINN_H).toLocalDate().atTime(TAGESBEGINN_H.toInt(), 0).atZone(zone).toInstant()

    private const val TAGESBEGINN_H = 6L

    /** Die Linie des Pulsbands, UNGEGLAETTET: je Stueck die Mediane, aus denen der Satz Spanne, Spitze und Zone nennt. */
    fun pulslinie(bins: List<Pulsbin>): List<List<Pair<Long, Int>>> = stuecke(bins).map { st -> st.map { it.sekunde to it.median } }

    /** Zusammenhaengende Stuecke einer Bin-Reihe: mehr als [luecke] Sekunden ohne Bin trennen sie. */
    fun stuecke(bins: List<Pulsbin>, luecke: Long = 900): List<List<Pulsbin>> {
        val aus = mutableListOf<MutableList<Pulsbin>>()
        bins.forEach { b ->
            val letztes = aus.lastOrNull()
            if (letztes == null || b.sekunde - letztes.last().sekunde > luecke) aus += mutableListOf(b) else letztes += b
        }
        return aus
    }

    /**
     * Die Pulszonen des Tages als Anteile der Pulsreserve (Karvonen): ab 40 %
     * moderat, ab 60 % kraeftig, ab 80 % Spitze. Die Belastung in [tagesherz]
     * rechnet mit genau diesen Grenzen - was die Kurve gruen faerbt, zaehlt
     * dort einfach, was gelb oder rot ist, doppelt. Mit Ruhepuls 53 und
     * Maximalpuls 187 gibt das 107, 133 und 160, nahe an dem, was Google
     * Health zeigt.
     */
    fun pulsZonen(ruhepuls: Int, maxpuls: Int): List<Int> {
        val reserve = (maxpuls - ruhepuls).coerceAtLeast(20)
        return listOf(0.4, 0.6, 0.8).map { (ruhepuls + it * reserve).roundToInt() }
    }

    /** 0 leicht, 1 moderat, 2 kraeftig, 3 Spitze. */
    fun pulsZone(bpm: Int, grenzen: List<Int>): Int = grenzen.count { bpm >= it }

    /**
     * Ein Tag einer Woche mit Band (HRV, Blutdruck oben, Blutdruck unten):
     * sein Wert und das Band, das bis dahin als gewohnt galt.
     */
    data class Bandtag(val tag: LocalDate, val wert: Int?, val band: Pair<Int, Int>?) {
        val imBand: Boolean? get() = if (wert == null || band == null) null else wert in band.first..band.second
    }

    /**
     * Die sieben Tage bis [heute], jeder mit seinem persoenlichen Band: Schnitt
     * plus/minus eine Standardabweichung der [Fenster.HRV_NAECHTE] Tage davor. Das
     * Band jedes Tages aus SEINEN Vortagen, nicht eines fuer die ganze Woche:
     * so wandert es mit, wie bei Google Health und Garmin, und der Tag selbst
     * zieht sein Band nicht zu sich. Mindestens 3 breit nach jeder Seite,
     * sonst laege bei sehr gleichmaessigen Werten jeder neue daneben.
     * Gerechnet fuer die HRV (je Nacht) und den Blutdruck (je Tag, oben und
     * unten getrennt) - DIESELBE REGEL, damit beide Karten dasselbe heissen.
     */
    fun bandwoche(werte: Map<LocalDate, Int>, heute: LocalDate): List<Bandtag> = (Fenster.WOCHE_TAGE - 1 downTo 0).map { heute.minusDays(it.toLong()) }.map { tag ->
        val vorher = werte.filter { (t, w) -> w > 0 && t < tag && !t.isBefore(tag.minusDays(Fenster.HRV_NAECHTE.toLong())) }.values.map { it.toDouble() }
        val band = vorher.takeIf { it.size >= Fenster.HRV_LERNNAECHTE }?.let { v ->
            val mittel = v.average()
            // Die Streuung der Stichprobe: die Tage stehen fuer alle, nicht nur fuer sich.
            val halb = maxOf(Statistik.streuung(v)!!, 3.0)
            (mittel - halb).roundToInt().coerceAtLeast(1) to (mittel + halb).roundToInt()
        }
        Bandtag(tag, werte[tag]?.takeIf { it > 0 }, band)
    }

    /**
     * Der Schnitt der Woche ueber die Tage mit Wert ("7-night avg"), ab
     * [Fenster.SCHNITT_WERTE] Werten - darunter waere er nur der letzte Wert
     * mit anderem Namen, wie beim Blutdruck.
     */
    fun wochenschnitt(tage: List<Bandtag>): Int? =
        tage.mapNotNull { it.wert }.takeIf { it.size >= Fenster.SCHNITT_WERTE }?.average()?.roundToInt()

    /**
     * Die Trendlinie durch eine Punktwolke: alle [schritt] Sekunden der Median
     * der Punkte bis [fenster] Sekunden davor und danach. Der Median, weil ein
     * einzelner Ausreisser der Uhr die Linie sonst zu sich zoege. Wo im
     * Fenster nichts liegt, bricht die Linie - dort weiss niemand, was war.
     */
    fun trend(punkte: List<Pair<Long, Int>>, fenster: Long, schritt: Long = 300): List<List<Pair<Long, Double>>> {
        if (punkte.isEmpty()) return emptyList()
        val sortiert = punkte.sortedBy { it.first }
        val aus = mutableListOf<MutableList<Pair<Long, Double>>>()
        var offen = false
        var t = sortiert.first().first
        // EIN FENSTER AUS ZWEI ZEIGERN: [unten, oben) sind die Punkte bis [fenster]
        // Sekunden um t. t waechst, also wandern beide nur vorwaerts - frueher
        // filterte jeder Schritt die ganze Liste (9-h-Nacht: 540 Punkte x 108 Schritte).
        var unten = 0
        var oben = 0
        while (t <= sortiert.last().first) {
            while (unten < sortiert.size && sortiert[unten].first < t - fenster) unten++
            while (oben < sortiert.size && sortiert[oben].first <= t + fenster) oben++
            // Nur zwischen Messungen: am Rand einer Luecke liefe die Linie sonst
            // eine Viertelstunde ueber den letzten Punkt hinaus ins Leere.
            if (unten >= oben || sortiert[unten].first > t || sortiert[oben - 1].first < t) offen = false
            else {
                // Der echte Median: bei gerader Anzahl das Mittel der beiden mittleren.
                val median = Statistik.median(sortiert.subList(unten, oben).map { it.second })
                if (!offen) { aus += mutableListOf<Pair<Long, Double>>(); offen = true }
                aus.last() += t to median
            }
            t += schritt
        }
        return aus
    }
}
