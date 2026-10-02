package coredevices.pebble.kiesel

import android.content.Context
import android.util.Log
import coredevices.pebble.R
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.MealType
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import io.rebble.libpebblecommon.connection.HealthDataApi
import io.rebble.libpebblecommon.database.entity.NachtAbschnittEntity
import io.rebble.libpebblecommon.database.entity.NachtEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.first
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow

/** Eine Nacht der Firmware, in Zeitpunkten statt Minuten ab dem Fensterbeginn. */
internal data class Nacht(
    val beginnUtc: Long,
    val einschlafen: Instant,
    val aufwachen: Instant,
    val schlafMinuten: Int,
    /** Minuten je Phase: 0 wach, 1 leicht, 2 tief, 3 REM, 4 Schlaf ohne Phase. */
    val phasen: IntArray,
    val abschnitte: List<Abschnitt>,
    val ruhepuls: Int?,
    val hrv: Int?,
) {
    data class Abschnitt(val von: Instant, val bis: Instant, val phase: Int)
    val mitPhasen: Boolean get() = phasen[1] + phasen[2] + phasen[3] > 0
    /**
     * Die Nacht, wie die Nachtansicht sie zeigt: alle Abschnitte, ohne sie
     * Einschlafen bis Aufwachen. Auch die Herzseite nimmt ihr SpO2 daraus -
     * sonst nannten beide einen anderen Tiefstwert derselben Nacht.
     */
    val spanne: LongRange get() =
        (abschnitte.minOfOrNull { it.von.epochSecond } ?: einschlafen.epochSecond)..(abschnitte.maxOfOrNull { it.bis.epochSecond } ?: aufwachen.epochSecond)
}

/**
 * Ein Ernaehrungseintrag aus der Akte: ein Getraenk oder ein Praeparat.
 * [eigen]: von Boulder geschrieben - nur solche Eintraege darf
 * Boulder in der Akte aendern oder loeschen. [kennung]: die eigene Kennung
 * (clientRecordId), an der ein SupCycle-Eintrag seinen Platz traegt.
 */
internal data class Tageseintrag(
    val zeit: Instant, val praeparat: Boolean, val name: String, val koffeinMg: Double?,
    val id: String = "", val eigen: Boolean = false, val kennung: String? = null,
)

/** Was ein Ernaehrungseintrag der Akte auf der Gesundheitsseite ist. */
internal enum class Eintragsart { GETRAENK, PRAEPARAT }

/** So weit zurueck zaehlt der Blutdruck: wer selten misst, misst kaum einmal die Woche. */
internal const val BLUTDRUCK_TAGE = 30L

/** Messungen mit weniger Abstand gehoeren zu einer Sitzung an der Manschette. */
internal const val BLUTDRUCK_SITZUNG_S = 15 * 60L

/**
 * So viele Kalendertage zaehlt die letzte Sitzung fuer die Herzzeile. Neben
 * dem Puls von heute laese sich ein Wert von vor Wochen wie ein heutiger.
 */
internal const val BLUTDRUCK_ZEILE_TAGE = 7L

/** Eine Blutdruckmessung aus der Akte, in mmHg. */
internal data class Blutdruck(val zeit: Instant, val oben: Int, val unten: Int)

/**
 * Wasser aus der Akte, mit seiner Kennung. [eigen]: ein Glas von
 * Drinktervall - nur das ist ein "Glas"; was andere Apps eintragen, hat
 * seine eigene Menge.
 */
internal data class Glaseintrag(val zeit: Instant, val id: String, val eigen: Boolean, val ml: Int = 0)

internal data class GesundheitsStand(
    val schritte: Long,
    val aktivMinuten: Long,
    val meter: Double,
    val kcal: Long,
    /** Schritte der letzten sieben Tage bis heute, aeltester zuerst; heute bis jetzt. */
    val wocheSchritte: List<Pair<LocalDate, Long>>,
    val nacht: Nacht?,
    /**
     * Ruhepulse der frueheren Naechte, aelteste zuerst, hoechstens sieben.
     * OHNE DIE LETZTE NACHT: sie ist es, die mit dem Schnitt verglichen
     * wird, und zoege ihn sonst zu sich.
     */
    val ruhepulsWoche: List<Int>,
    /**
     * Die HRV derselben frueheren Naechte, wie [ruhepulsWoche]. Nur fuer den
     * Schnitt, den "Your usual" nennt - geurteilt wird an ihrem Band in [hrvTage].
     */
    val hrvWoche: List<Int>,
    /** Schlafminuten der letzten sieben Naechte, aelteste zuerst; 0 = keine Nacht. */
    val wocheSchlaf: List<Pair<LocalDate, Int>>,
    /** Puls der letzten 24 Stunden: Sekunde seit 1970, Schlaege je Minute. */
    val puls: List<Pair<Long, Int>>,
    /** SpO2 der letzten 24 Stunden, mit Nacht ab ihrem Einschlafen - auch wenn das laenger her ist. */
    val spo2: List<Pair<Long, Int>>,
    val wasserMl: Int,
    /** Die Glaeser von Drinktervall - Wasser anderer Apps zaehlt in [wasserMl], aber nicht als Glas. */
    val glaeser: List<Instant>,
    /** Wasser je Tag, sieben Tage bis heute. */
    val wocheWasser: List<Pair<LocalDate, Int>>,
    val wasserZiel: Drinktervall.Ziel?,
    val supplemente: SupCycle.Stand?,
    val koffein: List<Pair<Instant, Double>>,
    /** Drinktervall auf der Uhr: nur dann Wasser und Koffein (siehe [UhrApps]). */
    val mitDrinktervall: Boolean = true,
    /** SupCycle auf der Uhr: nur dann die Praeparate. */
    val mitSupCycle: Boolean = true,
    /** Getraenke und Praeparate von heute, fuer den Tagesstrahl. */
    val eintraege: List<Tageseintrag> = emptyList(),
    /** Der Puls dieses Tages und der frueheren bis zur selben Uhrzeit, aelteste zuerst. */
    val tagesherz: List<Gesundheitsrechnung.Tagesherz> = emptyList(),
    /** Die Glaeser von heute mit Kennung - zum Loeschen im Tagesstrahl. */
    val glasEintraege: List<Glaseintrag> = emptyList(),
    /** Blutdruck der letzten [BLUTDRUCK_TAGE] Tage aus der Akte, aelteste zuerst. */
    val blutdruck: List<Blutdruck> = emptyList(),
    /** Der juengste Minutenwert der Uhr - wann zuletzt synchronisiert wurde. */
    val letzteDaten: Instant?,
    /** Die Grenzen moderat, kraeftig, Spitze in bpm (siehe [Gesundheitsrechnung.pulsZonen]). */
    val pulsZonen: List<Int> = emptyList(),
    /** Die HRV der letzten sieben Naechte mit dem persoenlichen Band, aelteste zuerst. */
    val hrvTage: List<Gesundheitsrechnung.HrvTag> = emptyList(),
    /** Ohne [nacht]: der Beginn der juengsten Nacht ueberhaupt - der Weg zu ihr und zu den frueheren. */
    val juengsteNacht: Long? = null,
    /**
     * Eigene Getraenke und Glaeser von gestern, hoechstens 24 Stunden alt:
     * der Strahl zeigt nur heute, und ein Fehleintrag kurz vor Mitternacht
     * muss sich auch danach noch aendern lassen.
     */
    val gestern: List<Tageseintrag> = emptyList(),
    val gesternGlaeser: List<Glaseintrag> = emptyList(),
)

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
    /** Ab so vielen frueheren Tagen gibt es ein Urteil. */
    const val TAGESHERZ_LERNTAGE = 5

    /**
     * Heute gegen die frueheren Tage bis zur selben Uhrzeit. [abstand]: der
     * Wachpuls gegen seinen Schnitt, fuer die Herzzeile. [haerte]: allein die
     * Belastung gegen ihren Median - der Satz sagt "more load than usual".
     * Frueher entschied ohne Belastung der Wachpuls mit, und der Satz sprach
     * von einem "harder day", waehrend die Herzzeile "Day as usual" sagte.
     * Ohne Urteil, solange weniger als [TAGESHERZ_LERNTAGE] Tage da sind.
     */
    data class Tagesurteil(val heute: Tagesherz, val schnittPuls: Int?, val abstand: Int?, val haerte: Vergleich?)

    fun tagesurteil(tage: List<Tagesherz>, heute: LocalDate): Tagesurteil? {
        val jetzt = tage.firstOrNull { it.tag == heute }?.takeIf { it.wachpuls != null } ?: return null
        val frueher = tage.filter { it.tag < heute && it.wachpuls != null }
        if (frueher.size < TAGESHERZ_LERNTAGE) return Tagesurteil(jetzt, null, null, null)
        val schnitt = frueher.map { it.wachpuls!! }.average().roundToInt()
        val abstand = jetzt.wachpuls!! - schnitt
        // Der Median, weil ein einzelner Trainingstag den Schnitt der Belastung verzerrt
        val last = frueher.map { it.belastung }.sorted().let { it[it.size / 2] }
        val haerte = if (last >= 10) when {
            jetzt.belastung >= maxOf(last * 3 / 2, last + 15) -> Vergleich.HOEHER
            jetzt.belastung <= last / 2 -> Vergleich.TIEFER
            else -> Vergleich.WIE_IMMER
        } else {
            // Kaum Belastung als Mass: weniger als fast nichts gibt es nicht, mehr erst ab 20 Minuten.
            if (jetzt.belastung >= 20) Vergleich.HOEHER else Vergleich.WIE_IMMER
        }
        return Tagesurteil(jetzt, schnitt, abstand, haerte)
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
        val jeTag = linkedMapOf<LocalDate, Triple<Long, Int, Double>>()   // Summe, Anzahl, Belastung
        val mitNacht = schlaf.map { Instant.ofEpochSecond(it.second).atZone(zone).toLocalDate() }.toSet()
        val nachtsGemessen = mutableSetOf<LocalDate>()
        sortiert.forEachIndexed { i, (sek, bpm) ->
            if (schlaf.any { (von, bis) -> sek in von until bis }) return@forEachIndexed
            val zeit = java.time.Instant.ofEpochSecond(sek).atZone(zone)
            if (zeit.toLocalTime().isAfter(bisUhrzeit)) return@forEachIndexed
            val tag = zeit.toLocalDate()
            val vielleichtSchlaf = zeit.hour < TAGESBEGINN_H && tag !in mitNacht
            if (vielleichtSchlaf) nachtsGemessen += tag
            val minuten = if (i + 1 < sortiert.size) ((sortiert[i + 1].first - sek) / 60.0).coerceIn(1.0, 10.0) else 1.0
            val gewicht = when (pulsZone(bpm, grenzen)) {
                0 -> 0.0
                1 -> 1.0
                else -> 2.0
            }
            val alt = jeTag[tag] ?: Triple(0L, 0, 0.0)
            jeTag[tag] = if (vielleichtSchlaf) alt.copy(third = alt.third + gewicht * minuten)
            else Triple(alt.first + bpm, alt.second + 1, alt.third + gewicht * minuten)
        }
        val wartet = heute != null && heute in nachtsGemessen && bisUhrzeit.hour < NACHT_SPAETESTENS_H
        return jeTag.map { (tag, w) ->
            val wach = if (w.second >= TAGESHERZ_MINDESTENS && !(wartet && tag == heute)) (w.first.toDouble() / w.second).roundToInt() else null
            Tagesherz(tag, wach, w.third.roundToInt(), w.second)
        }.sortedBy { it.tag }
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
        val schnitt = sitzungen.takeIf { it.size >= 3 }?.let { l ->
            l.map(::oben).average().roundToInt() to l.map(::unten).average().roundToInt()
        }
        // Die Sitzung traegt die Zeit ihrer ersten Messung: da begann das Messen.
        return Blutdruckstand(sitzungen.map { Blutdruck(it.first().zeit, oben(it).roundToInt(), unten(it).roundToInt()) }, schnitt)
    }

    /** Die letzte Sitzung fuer die Herzzeile, wenn sie in den letzten [BLUTDRUCK_ZEILE_TAGE] Kalendertagen liegt. */
    fun blutdruckFuerHerzzeile(messungen: List<Blutdruck>, jetzt: Instant, zone: ZoneId): Blutdruck? =
        blutdruckstand(messungen)?.letzte?.takeIf { tageZurueck(it.zeit, jetzt, zone) < BLUTDRUCK_ZEILE_TAGE }

    /**
     * Die y-Achse des Blutdruckbilds in mmHg: fuenf mmHg Luft unter dem
     * tiefsten und ueber dem hoechsten Wert, auf Zehner gerundet - so liegt
     * kein Punkt am Rand, und die Zahlen der Achse bleiben rund.
     */
    fun blutdruckachse(sitzungen: List<Blutdruck>): Pair<Int, Int>? {
        if (sitzungen.isEmpty()) return null
        val tief = sitzungen.minOf { minOf(it.oben, it.unten) } - 5
        val hoch = sitzungen.maxOf { maxOf(it.oben, it.unten) } + 5
        return Math.floorDiv(tief, 10) * 10 to Math.floorDiv(hoch + 9, 10) * 10
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
        kennung?.startsWith("supcycle-") == true -> Eintragsart.PRAEPARAT
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
            val werte = l.map { it.second }.sorted()
            Pulsbin(sekunde, werte[werte.size / 2], werte.first(), werte.last())
        }

    /**
     * Tiefster und hoechster Puls, wie die Linie des Pulsbands sie zeigt: aus
     * den Medianen der [fuenfMinuten], vor dem Glaetten. Aus den Rohwerten
     * bestimmte ein einzelner Ausreisser des Sensors Spanne und Zone im Satz,
     * und die Kurve darunter erreichte ihn nie.
     */
    fun pulsSpanne(punkte: List<Pair<Long, Int>>): Pair<Int, Int>? {
        val mediane = fuenfMinuten(punkte).map { it.median }
        return if (mediane.isEmpty()) null else mediane.min() to mediane.max()
    }

    /**
     * Wann der hoechste Wert der [pulsSpanne] lag (Sekunde des Fensters) und
     * welcher es war - der juengste, wenn er mehrmals vorkommt. Die Herzseite
     * zeigt 24 Stunden: ohne Zeit las sich eine Spitze von gestern Abend als
     * heutige.
     */
    fun pulsSpitze(punkte: List<Pair<Long, Int>>): Pair<Long, Int>? {
        val bins = fuenfMinuten(punkte)
        val hoechster = bins.maxOfOrNull { it.median } ?: return null
        return bins.last { it.median == hoechster }.let { it.sekunde to it.median }
    }

    /**
     * Rasterlinien in runden Schritten, hoechstens fuenf Striche: der kleinste
     * Schritt ab [mindestSchritt], mit dem hoechstens fuenf Vielfache in
     * [tief, hoch] liegen. Ein Puls will Zehner, eine SpO2-Achse Fuenfer.
     */
    fun raster(tief: Int, hoch: Int, mindestSchritt: Int = 1): List<Int> {
        if (hoch < tief) return emptyList()
        for (schritt in listOf(1, 2, 5, 10, 20, 50, 100, 200, 500, 1000).filter { it >= mindestSchritt }) {
            val erste = Math.floorDiv(tief + schritt - 1, schritt) * schritt
            val linien = generateSequence(erste) { it + schritt }.takeWhile { it <= hoch }.toList()
            if (linien.size <= 5) return linien
        }
        return emptyList()
    }

    /** Schritte kurz - jetzt abgerundet wie ueberall (Formate). */
    @Deprecated("Formate.schritteKurz", ReplaceWith("Formate.schritteKurz(schritte, locale)"))
    fun schritteKurz(schritte: Long, locale: Locale = Locale.getDefault()): String = Formate.schritteKurz(schritte, locale)

    /** Liter mit einer Nachkommastelle, ohne Einheit: "1.2". Formate.liter schreibt "0" statt "0.0". */
    @Deprecated("Formate.liter", ReplaceWith("Formate.liter(ml, locale)"))
    fun literKurz(ml: Int, locale: Locale = Locale.getDefault()): String = String.format(locale, "%.1f", ml / 1000.0)

    /** Der Schnitt der Tage mit Wert, oder null, wenn weniger als [mindestens] solche Tage da sind. */
    fun schnittDerVortage(werte: List<Double>, mindestens: Int = 1): Double? =
        werte.filter { it > 0 }.takeIf { it.size >= mindestens }?.average()

    /**
     * Die Bettzeit als Zeitpunkt: die Uhrzeit des letzten Einschlafens, sonst
     * 22 Uhr, auf den laufenden Tag gelegt. Der Tag beginnt um sechs, damit
     * 00:30 die kommende Nacht meint und nicht die vergangene. Ist sie schon
     * vorbei, null: "um 22:54 wirken noch 1 mg" ohne Datum laese sich als
     * heute Abend, gemeint waere morgen.
     */
    fun bettzeit(einschlafen: Instant?, jetzt: Instant, zone: ZoneId): Instant? {
        val uhrzeit = einschlafen?.atZone(zone)?.toLocalTime()?.withSecond(0)?.withNano(0) ?: LocalTime.of(22, 0)
        val tag = jetzt.atZone(zone).minusHours(TAGESBEGINN_H).toLocalDate()
        val bett = if (uhrzeit.hour < TAGESBEGINN_H) tag.plusDays(1) else tag
        return bett.atTime(uhrzeit).atZone(zone).toInstant().takeIf { it.isAfter(jetzt) }
    }

    private const val TAGESBEGINN_H = 6L

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

    /** Eine Nacht der HRV-Woche: ihr Wert und das Band, das bis dahin als gewohnt galt. */
    data class HrvTag(val tag: LocalDate, val wert: Int?, val band: Pair<Int, Int>?) {
        val imBand: Boolean? get() = if (wert == null || band == null) null else wert in band.first..band.second
    }

    /** So viele Naechte vor einem Tag bilden sein Band. */
    const val HRV_FENSTER = 30
    /** Mit weniger Naechten gibt es kein Band - es waere Zufall. */
    const val HRV_MINDESTENS = 7

    /**
     * Die sieben Naechte bis [heute], jede mit ihrem persoenlichen Band: Schnitt
     * plus/minus eine Standardabweichung der [HRV_FENSTER] Naechte davor. Das
     * Band jedes Tages aus SEINEN Vortagen, nicht eines fuer die ganze Woche:
     * so wandert es mit, wie bei Google Health und Garmin, und die Nacht selbst
     * zieht ihr Band nicht zu sich. Mindestens 3 ms breit nach jeder Seite,
     * sonst laege bei sehr gleichmaessigen Naechten jede neue daneben.
     */
    fun hrvWoche(werte: Map<LocalDate, Int>, heute: LocalDate): List<HrvTag> = (6 downTo 0).map { heute.minusDays(it.toLong()) }.map { tag ->
        val vorher = werte.filter { (t, w) -> w > 0 && t < tag && !t.isBefore(tag.minusDays(HRV_FENSTER.toLong())) }.values.map { it.toDouble() }
        val band = vorher.takeIf { it.size >= HRV_MINDESTENS }?.let { v ->
            val mittel = v.average()
            val sd = kotlin.math.sqrt(v.sumOf { (it - mittel) * (it - mittel) } / (v.size - 1))
            val halb = maxOf(sd, 3.0)
            (mittel - halb).roundToInt().coerceAtLeast(1) to (mittel + halb).roundToInt()
        }
        HrvTag(tag, werte[tag]?.takeIf { it > 0 }, band)
    }

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
        while (t <= sortiert.last().first) {
            val nah = sortiert.filter { abs(it.first - t) <= fenster }
            val werte = nah.map { it.second }.sorted()
            // Nur zwischen Messungen: am Rand einer Luecke liefe die Linie sonst
            // eine Viertelstunde ueber den letzten Punkt hinaus ins Leere.
            if (nah.none { it.first <= t } || nah.none { it.first >= t }) offen = false
            else {
                val median = if (werte.size % 2 == 1) werte[werte.size / 2].toDouble()
                else (werte[werte.size / 2 - 1] + werte[werte.size / 2]) / 2.0
                if (!offen) { aus += mutableListOf<Pair<Long, Double>>(); offen = true }
                aus.last() += t to median
            }
            t += schritt
        }
        return aus
    }
}

/**
 * Laedt, was die Gesundheitsseite zeigt.
 *
 * BEWEGUNG, PULS, SPO2 UND NAECHTE AUS BOULDERS EIGENER DATENBANK: die Uhr
 * ist ihre Quelle, und so zeigt die Seite sie auch ohne Health Connect.
 * Wasser und Koffein kommen aus der Akte - dort traegt Boulder sie ein, und
 * andere Apps koennen dasselbe tun.
 */
internal object Gesundheitsdaten {
    private const val TAG = "Gesundheit"

    suspend fun lies(context: Context, api: HealthDataApi): GesundheitsStand = withContext(Dispatchers.IO) {
        val zone = ZoneId.systemDefault()
        val heute = LocalDate.now()
        val jetzt = Instant.now()
        fun anfang(tag: LocalDate) = tag.atStartOfDay(zone).toEpochSecond()

        val summe = runCatching { api.getTotalHealthData(anfang(heute), jetzt.epochSecond) }.getOrNull()
        val woche = (6 downTo 0).map { heute.minusDays(it.toLong()) }
        // Der heutige Tag steht nicht in den Tagesaggregaten - er kommt aus der Summe bis jetzt.
        val tageswerte = runCatching { api.getDailyAggregates(anfang(woche.first()), anfang(heute)) }.getOrDefault(emptyList())
            .associate { LocalDate.parse(it.day) to (it.steps ?: 0L) } + (heute to (summe?.steps ?: 0L))

        val naechte = Gesundheitsrechnung.jeTag(
            runCatching { api.getNaechte(anfang(heute.minusDays(VERGLEICHSNAECHTE + 2L)), jetzt.epochSecond) }.getOrDefault(emptyList()), zone,
        )
        val (letzte, frueher) = Gesundheitsrechnung.naechteTeilen(naechte, heute, zone)
        val nacht = letzte?.let { Gesundheitsrechnung.nacht(it, runCatching { api.getNachtAbschnitte(it.beginnUtc) }.getOrDefault(emptyList())) }
        val schlafJeTag = naechte.associate { Gesundheitsrechnung.nachtTag(it.beginnUtc, it.aufwachen, zone) to it.schlafMinuten }

        val vor24 = jetzt.epochSecond - 24 * 3600
        val puls = runCatching { api.getHealthDataForRange(vor24, jetzt.epochSecond) }.getOrDefault(emptyList())
            .filter { it.heartRate > 0 }.map { it.timestamp to it.heartRate }
        // Spaetabends liegt das Einschlafen der letzten Nacht vor dem 24-Stunden-Fenster:
        // ihr Anfang fehlte, und die Herzseite nannte einen anderen Tiefstwert als die Nachtansicht.
        val spo2Von = minOf(vor24, nacht?.spanne?.first ?: vor24)
        val spo2 = runCatching { api.getSpo2Readings(spo2Von, jetzt.epochSecond) }.getOrDefault(emptyList())
            .map { it.timestamp to it.spo2Percent }

        val klient = Akte(context).bereit()
        val wochenAnfang = heute.minusDays(6).atStartOfDay(zone).toInstant()
        val wasser = try {
            klient?.liesOderEigene(context, HydrationRecord::class, TimeRangeFilter.between(wochenAnfang, jetzt)).orEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "Wasser lesen: " + e.message)
            emptyList()
        }
        val wasserJeTag = wasser.groupBy { it.startTime.atZone(zone).toLocalDate() }
            .mapValues { (_, r) -> r.sumOf { it.volume.inMilliliters }.toInt() }
        val heuteWasser = wasser.filter { it.startTime.atZone(zone).toLocalDate() == heute }
        val nahrung = try {
            klient?.liesOderEigene(context, NutritionRecord::class, TimeRangeFilter.between(jetzt.minus(Duration.ofHours(24)), jetzt)).orEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "Ernaehrung lesen: " + e.message)
            emptyList()
        }
        val koffein = nahrung.mapNotNull { r -> r.caffeine?.inMilligrams?.takeIf { it > 0 }?.let { r.startTime to it } }
        val alleEintraege = nahrung.mapNotNull { r ->
            val eigen = r.metadata.dataOrigin.packageName == context.packageName
            val mg = r.caffeine?.inMilligrams?.takeIf { it > 0 }
            val mahlzeit = r.mealType != MealType.MEAL_TYPE_UNKNOWN
            val art = Gesundheitsrechnung.einordnung(eigen, r.metadata.clientRecordId, mg, r.energy?.inKilocalories, mahlzeit) ?: return@mapNotNull null
            val name = r.name?.ifBlank { null } ?: if (mg != null) context.getString(R.string.ks_h_koffein_n) else return@mapNotNull null
            Tageseintrag(r.startTime, art == Eintragsart.PRAEPARAT, name, mg, r.metadata.id, eigen, r.metadata.clientRecordId)
        }
        val eintraege = alleEintraege.filter { it.zeit.atZone(zone).toLocalDate() == heute }
        val vorEinemTag = jetzt.minus(Duration.ofHours(24))
        val gesternWasser = wasser.filter { it.startTime.isAfter(vorEinemTag) && it.startTime.atZone(zone).toLocalDate() < heute }

        // DIE UHR MISST KEINEN BLUTDRUCK - er kommt von einer Manschette und
        // deren App ueber die Akte, deshalb auch fremde Eintraege.
        val blutdruck = try {
            klient?.liesOderEigene(context, BloodPressureRecord::class, TimeRangeFilter.between(jetzt.minus(Duration.ofDays(BLUTDRUCK_TAGE)), jetzt)).orEmpty()
                .map { Blutdruck(it.time, it.systolic.inMillimetersOfMercury.roundToInt(), it.diastolic.inMillimetersOfMercury.roundToInt()) }
                .sortedBy { it.zeit }
        } catch (e: Exception) {
            Log.w(TAG, "Blutdruck lesen: " + e.message)
            emptyList()
        }

        val apps = UhrApps.installiert()
        // Dieselben Grenzen wie bei den Trainings, siehe Herzgrenzen
        val ruhe = Herzgrenzen.ruhepuls(frueher.map { it.ruhepuls } + listOfNotNull(nacht?.ruhepuls)) ?: Trainingsanalyse.RUHEPULS_ERSATZ
        val maxpuls = Herzgrenzen.maxpuls(context, ruhe)
        // Eine Rechnung der Zonen fuer Kurve und Belastung, damit beide dieselbe Grenze ziehen
        val zonen = Gesundheitsrechnung.pulsZonen(ruhe, maxpuls)
        val tagesherz = runCatching {
            val von = anfang(heute.minusDays(14))
            val minutenpuls = api.getHealthDataForRange(von, jetzt.epochSecond).filter { it.heartRate > 0 }.map { it.timestamp to it.heartRate }
            val schlafSpannen = api.getNaechte(von - 86_400, jetzt.epochSecond).map { e ->
                (e.beginnUtc + e.einschlafen * 60L) to (e.beginnUtc + e.aufwachen * 60L)
            }
            Gesundheitsrechnung.tagesherz(minutenpuls, schlafSpannen, zonen, LocalTime.now(), zone, heute)
        }.getOrDefault(emptyList())
        // Das HRV-Band der aeltesten Nacht der Woche braucht noch einmal
        // HRV_FENSTER Naechte davor - die Naechte oben reichen nur eine Woche zurueck.
        val langeNaechte = runCatching {
            Gesundheitsrechnung.jeTag(api.getNaechte(anfang(heute.minusDays(6L + Gesundheitsrechnung.HRV_FENSTER + 1)), jetzt.epochSecond), zone)
        }.getOrDefault(emptyList())
        val hrvTage = runCatching {
            val jeTag = langeNaechte.associate { Gesundheitsrechnung.nachtTag(it.beginnUtc, it.aufwachen, zone) to it.hrv }
            Gesundheitsrechnung.hrvWoche(jeTag, heute)
        }.getOrDefault(emptyList())
        // Ohne Nacht von heute fuehrt nur dieser Weg zu den frueheren Naechten und
        // zu Sleep trends; nach Wochen ohne Uhr reichen die 37 Tage oben nicht.
        val juengste = if (nacht != null) null else langeNaechte.lastOrNull()?.beginnUtc
            ?: runCatching { api.getNaechte(0, jetzt.epochSecond) }.getOrDefault(emptyList()).maxOfOrNull { it.beginnUtc }
        GesundheitsStand(
            schritte = summe?.steps ?: 0,
            aktivMinuten = summe?.activeMinutes ?: 0,
            meter = (summe?.distanceCm ?: 0) / 100.0,
            kcal = (summe?.activeGramCalories ?: 0) / 1000,
            wocheSchritte = Gesundheitsrechnung.reihe(woche, tageswerte, 0L),
            nacht = nacht,
            ruhepulsWoche = frueher.map { it.ruhepuls }.filter { it > 0 },
            hrvWoche = frueher.map { it.hrv }.filter { it > 0 },
            wocheSchlaf = Gesundheitsrechnung.reihe(woche, schlafJeTag, 0),
            puls = puls,
            spo2 = spo2,
            wasserMl = heuteWasser.sumOf { it.volume.inMilliliters }.toInt(),
            glaeser = heuteWasser.filter { it.metadata.dataOrigin.packageName == context.packageName }.map { it.startTime }.sorted(),
            wocheWasser = Gesundheitsrechnung.reihe(woche, wasserJeTag, 0),
            wasserZiel = Drinktervall.ziel(context),
            supplemente = SupCycle.heute(context),
            koffein = koffein.sortedBy { it.first },
            mitDrinktervall = UhrApps.hat(apps, Drinktervall.UUID),
            mitSupCycle = UhrApps.hat(apps, SupCycle.UUID),
            tagesherz = tagesherz,
            eintraege = eintraege.sortedByDescending { it.zeit },
            blutdruck = blutdruck,
            glasEintraege = heuteWasser.map { glaseintrag(context, it) },
            // Die Fake-Bibliothek meldet 0 statt nichts.
            letzteDaten = runCatching { api.getLatestTimestamp() }.getOrNull()?.takeIf { it > 0 }?.let { Instant.ofEpochSecond(it) },
            pulsZonen = zonen,
            hrvTage = hrvTage,
            juengsteNacht = juengste,
            gestern = alleEintraege.filter { it.eigen && !it.praeparat && it.zeit.atZone(zone).toLocalDate() < heute }.sortedBy { it.zeit },
            gesternGlaeser = gesternWasser.map { glaseintrag(context, it) }.filter { it.eigen }.sortedBy { it.zeit },
        )
    }

    private fun glaseintrag(context: Context, r: HydrationRecord) =
        Glaseintrag(r.startTime, r.metadata.id, r.metadata.dataOrigin.packageName == context.packageName, r.volume.inMilliliters.roundToInt())

    /** Eine Nacht mit dem Puls und SpO2 aus ihr, und den Nachbarnaechten zum Blaettern. */
    data class NachtDetail(
        val nacht: Nacht,
        val puls: List<Pair<Long, Int>>,
        val spo2: List<Pair<Long, Int>>,
        val vorige: Long?,
        val naechste: Long?,
        /** Bis zu sieben Naechte vor dieser, aelteste zuerst - fuer Schnitt und Saeulen. */
        val davor: List<NachtEntity> = emptyList(),
    )

    /**
     * EINE NACHT JE TAG UND DIE FRUEHEREN WIE AUF HEALTH: geblaettert und
     * verglichen wird nur mit der laengsten Nacht jedes Tages, die frueheren
     * aus derselben Woche davor ([Gesundheitsrechnung.naechteTeilen]). Roh
     * zaehlte ein Kurzschlaf am Morgen als eigene Nacht - zwei Saeulen "Tu",
     * und der Schnitt fiel auf 5:31 h, waehrend die Trends 7:42 h sagten.
     */
    suspend fun nacht(api: HealthDataApi, beginnUtc: Long): NachtDetail? = withContext(Dispatchers.IO) {
        val zone = ZoneId.systemDefault()
        val roh = runCatching { api.getNaechte(beginnUtc - 30L * 86_400, beginnUtc + 30L * 86_400) }.getOrDefault(emptyList())
        val e = roh.firstOrNull { it.beginnUtc == beginnUtc } ?: return@withContext null
        val umgebung = Gesundheitsrechnung.jeTag(roh, zone)
        val (_, frueher) = Gesundheitsrechnung.naechteTeilen(umgebung, Gesundheitsrechnung.nachtTag(e.beginnUtc, e.aufwachen, zone), zone)
        val n = Gesundheitsrechnung.nacht(e, runCatching { api.getNachtAbschnitte(beginnUtc) }.getOrDefault(emptyList()))
        val von = minOf(n.einschlafen.epochSecond, n.spanne.first) - 1800
        val bis = maxOf(n.aufwachen.epochSecond, n.spanne.last) + 1800
        NachtDetail(
            nacht = n,
            puls = runCatching { api.getHealthDataForRange(von, bis) }.getOrDefault(emptyList())
                .filter { it.heartRate > 0 }.map { it.timestamp to it.heartRate },
            spo2 = runCatching { api.getSpo2Readings(von, bis) }.getOrDefault(emptyList()).map { it.timestamp to it.spo2Percent },
            vorige = umgebung.lastOrNull { it.beginnUtc < beginnUtc }?.beginnUtc,
            naechste = umgebung.firstOrNull { it.beginnUtc > beginnUtc }?.beginnUtc,
            davor = frueher,
        )
    }

    /**
     * Eintraege aus der Akte loeschen - nur Boulders eigene, andere lehnt die
     * Akte ohnehin ab. Die Uhr erfaehrt davon nichts: ein geloeschtes Glas
     * zaehlt Drinktervall auf der Uhr weiter.
     */
    suspend fun loesche(context: Context, klasse: kotlin.reflect.KClass<out androidx.health.connect.client.records.Record>, ids: List<String>): Boolean = withContext(Dispatchers.IO) {
        try {
            Akte(context).bereit()?.deleteRecords(klasse, recordIdsList = ids, clientRecordIdsList = emptyList()) != null
        } catch (e: Exception) {
            Log.w(TAG, "Loeschen: " + e.message)
            false
        }
    }

    /**
     * Ein Getraenk umstellen - etwa wenn auf der Uhr die falsche Sorte
     * gewaehlt war. Zeit und Kennung bleiben, Name, Koffein, kcal und Zucker
     * kommen neu aus der Sorte ([art] wie COFFEE_KIND der Uhr).
     */
    suspend fun aendereGetraenk(context: Context, id: String, art: Int): Boolean = withContext(Dispatchers.IO) {
        val p = Kaffeesorten.portion(art) ?: return@withContext false
        try {
            val klient = Akte(context).bereit() ?: return@withContext false
            val alt = klient.readRecord(NutritionRecord::class, id).record
            // DIESELBEN METADATEN: daran erkennt die Akte den Eintrag, der ersetzt wird.
            klient.updateRecords(listOf(
                Kaffeesorten.satz(p, Kaffeesorten.name(context, p), alt.metadata, alt.startTime, alt.endTime, alt.startZoneOffset ?: versatz(alt.startTime), alt.endZoneOffset ?: versatz(alt.endTime)),
            ))
            true
        } catch (e: Exception) {
            Log.w(TAG, "Getraenk aendern: " + e.message)
            false
        }
    }

    /** Eine Tasse von Hand - mit denselben Werten, die die Uhr fuer diese Sorte eintraegt. */
    suspend fun trinkeKoffein(context: Context, art: Int): Boolean = withContext(Dispatchers.IO) {
        val p = Kaffeesorten.portion(art) ?: return@withContext false
        val jetzt = Instant.now()
        try {
            // Eigene Kennung per manualEntry - manualEntryWithId setzte die der Akte.
            val metadaten = Metadata.manualEntry("koffein-${jetzt.epochSecond}", 0L, Device(type = Device.TYPE_PHONE))
            Akte(context).bereit()?.insertRecords(listOf(Kaffeesorten.satz(p, Kaffeesorten.name(context, p), metadaten, jetzt))) != null
        } catch (e: Exception) {
            Log.w(TAG, "Koffein eintragen: " + e.message)
            false
        }
    }
}
