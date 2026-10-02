package coredevices.pebble.kiesel

import android.content.Context
import android.util.Log
import coredevices.pebble.R
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Mass
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
}

/** Ein Eintrag des heutigen Tages aus der Akte: ein Getraenk oder ein Praeparat. */
/**
 * Ein Eintrag des heutigen Tages aus der Akte: ein Getraenk oder ein
 * Praeparat. [eigen]: von Boulder geschrieben - nur solche Eintraege darf
 * Boulder in der Akte aendern oder loeschen.
 */
internal data class Tageseintrag(val zeit: Instant, val praeparat: Boolean, val name: String, val koffeinMg: Double?, val id: String = "", val eigen: Boolean = false)

/** So weit zurueck zaehlt der Blutdruck: wer selten misst, misst kaum einmal die Woche. */
internal const val BLUTDRUCK_TAGE = 30L

/** Eine Blutdruckmessung aus der Akte, in mmHg. */
internal data class Blutdruck(val zeit: Instant, val oben: Int, val unten: Int)

/** Ein Glas Wasser von heute, mit seiner Kennung in der Akte. */
internal data class Glaseintrag(val zeit: Instant, val id: String, val eigen: Boolean)

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
    /** Die HRV derselben frueheren Naechte, wie [ruhepulsWoche]. */
    val hrvWoche: List<Int>,
    /** Schlafminuten der letzten sieben Naechte, aelteste zuerst; 0 = keine Nacht. */
    val wocheSchlaf: List<Pair<LocalDate, Int>>,
    /** Puls der letzten 24 Stunden: Sekunde seit 1970, Schlaege je Minute. */
    val puls: List<Pair<Long, Int>>,
    val spo2: List<Pair<Long, Int>>,
    val wasserMl: Int,
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
)

/** Fuenf Minuten Puls: der mittlere Wert und die Spanne, in der er lag. */
internal data class Pulsbin(val sekunde: Long, val median: Int, val tiefster: Int, val hoechster: Int)

/** Was sich an den Gesundheitsdaten rechnen laesst - ohne Android, damit es pruefbar ist. */
internal object Gesundheitsrechnung {
    /**
     * Der Puls eines Tages, nur wach und bis zur selben Uhrzeit wie jetzt.
     * [wachpuls]: der Schnitt der wachen Messungen. [belastung]: Minuten mit
     * erhoehtem Puls, gewichtet wie Zone-Minuten - ab 40 % der Pulsreserve
     * einfach, ab 60 % doppelt. Jede Messung steht fuer die Minuten bis zur
     * naechsten, hoechstens zehn: die Uhr misst je nach Messstufe nicht jede
     * Minute.
     */
    data class Tagesherz(val tag: LocalDate, val wachpuls: Int?, val belastung: Int, val messungen: Int)

    /** Weniger wache Messungen tragen keinen Tagesschnitt. */
    const val TAGESHERZ_MINDESTENS = 24
    /** Ab so vielen frueheren Tagen gibt es ein Urteil. */
    const val TAGESHERZ_LERNTAGE = 5

    /**
     * Heute gegen die frueheren Tage bis zur selben Uhrzeit. Die Haerte richtet
     * sich nach der Belastung; wo auch frueher kaum Belastung war, nach dem
     * Wachpuls. Ohne Urteil, solange weniger als [TAGESHERZ_LERNTAGE] Tage da sind.
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
        } else when {
            jetzt.belastung >= 20 || abstand >= 5 -> Vergleich.HOEHER
            abstand <= -5 -> Vergleich.TIEFER
            else -> Vergleich.WIE_IMMER
        }
        return Tagesurteil(jetzt, schnitt, abstand, haerte)
    }

    fun tagesherz(
        puls: List<Pair<Long, Int>>,
        schlaf: List<Pair<Long, Long>>,
        ruhepuls: Int,
        maxpuls: Int,
        bisUhrzeit: java.time.LocalTime,
        zone: ZoneId,
    ): List<Tagesherz> {
        val reserve = (maxpuls - ruhepuls).coerceAtLeast(20)
        val sortiert = puls.filter { it.second > 0 }.sortedBy { it.first }
        val jeTag = linkedMapOf<LocalDate, Triple<Long, Int, Double>>()   // Summe, Anzahl, Belastung
        sortiert.forEachIndexed { i, (sek, bpm) ->
            if (schlaf.any { (von, bis) -> sek in von until bis }) return@forEachIndexed
            val zeit = java.time.Instant.ofEpochSecond(sek).atZone(zone)
            if (zeit.toLocalTime().isAfter(bisUhrzeit)) return@forEachIndexed
            val minuten = if (i + 1 < sortiert.size) ((sortiert[i + 1].first - sek) / 60.0).coerceIn(1.0, 10.0) else 1.0
            val anteil = (bpm - ruhepuls).toDouble() / reserve
            val gewicht = when {
                anteil >= 0.6 -> 2.0
                anteil >= 0.4 -> 1.0
                else -> 0.0
            }
            val alt = jeTag[zeit.toLocalDate()] ?: Triple(0L, 0, 0.0)
            jeTag[zeit.toLocalDate()] = Triple(alt.first + bpm, alt.second + 1, alt.third + gewicht * minuten)
        }
        return jeTag.map { (tag, w) ->
            Tagesherz(tag, if (w.second >= TAGESHERZ_MINDESTENS) (w.first.toDouble() / w.second).roundToInt() else null, w.third.roundToInt(), w.second)
        }.sortedBy { it.tag }
    }

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

    /** Die letzte Messung und, ab drei Messungen, der Schnitt der Zeitspanne. */
    data class Blutdruckzeile(val letzte: Blutdruck, val schnitt: Pair<Int, Int>?)

    fun blutdruckzeile(messungen: List<Blutdruck>): Blutdruckzeile? {
        val letzte = messungen.maxByOrNull { it.zeit } ?: return null
        // Unter drei Messungen ist ein Schnitt nur die letzte Messung mit anderem Namen.
        val schnitt = messungen.takeIf { it.size >= 3 }?.let { m ->
            m.map { it.oben }.average().roundToInt() to m.map { it.unten }.average().roundToInt()
        }
        return Blutdruckzeile(letzte, schnitt)
    }

    /** Wieviel Koffein zum Zeitpunkt [jetzt] noch wirkt - jede Dosis halbiert sich alle fuenf Stunden. */
    fun koffeinAktiv(dosen: List<Pair<Instant, Double>>, jetzt: Instant): Double = dosen
        .filter { !it.first.isAfter(jetzt) }
        .sumOf { (wann, mg) -> mg * 0.5.pow(Duration.between(wann, jetzt).toMinutes() / KOFFEIN_HALBWERT_MIN) }

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

    /** Schritte kurz: "4.9k", "10k", darunter die Zahl selbst. */
    fun schritteKurz(schritte: Long, locale: Locale = Locale.getDefault()): String = when {
        schritte < 1000 -> "$schritte"
        schritte % 1000 == 0L -> "${schritte / 1000}k"
        else -> String.format(locale, "%.1fk", schritte / 1000.0).replace(".0k", "k").replace(",0k", "k")
    }

    /** Liter mit einer Nachkommastelle, ohne Einheit: "1.2". */
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

    /** Die Rasterlinien ohne die, die dem Ruhepuls naeher als [abstand] kaemen - seine Linie ersetzt sie. */
    fun rasterOhne(raster: List<Int>, ruhepuls: Int?, abstand: Float): List<Int> =
        if (ruhepuls == null) raster else raster.filter { abs(it - ruhepuls) >= abstand }
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
        val spo2 = runCatching { api.getSpo2Readings(vor24, jetzt.epochSecond) }.getOrDefault(emptyList())
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
        // WAS ES WAR, SAGT DIE KENNUNG: SupCycle schreibt "supcycle-...", alles
        // andere mit Namen oder Koffein ist ein Getraenk.
        val eintraege = nahrung.filter { it.startTime.atZone(zone).toLocalDate() == heute }.mapNotNull { r ->
            // PRAEPARAT IST, WAS NICHTS ENTHAELT: ohne Koffein und ohne Energie
            // ist ein Eintrag kein Getraenk. Die Kennung "supcycle-" allein trug
            // nicht: auf dem Telefon standen SupCycles Haken als Getraenke da.
            val praeparat = r.metadata.clientRecordId?.startsWith("supcycle-") == true ||
                (r.caffeine == null && r.energy == null)
            val mg = r.caffeine?.inMilligrams?.takeIf { it > 0 }
            val name = r.name?.ifBlank { null } ?: if (mg != null) context.getString(R.string.ks_h_koffein_n) else return@mapNotNull null
            Tageseintrag(r.startTime, praeparat, name, mg, r.metadata.id, r.metadata.dataOrigin.packageName == context.packageName)
        }

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
        val tagesherz = runCatching {
            val von = anfang(heute.minusDays(14))
            val minutenpuls = api.getHealthDataForRange(von, jetzt.epochSecond).filter { it.heartRate > 0 }.map { it.timestamp to it.heartRate }
            val schlafSpannen = api.getNaechte(von - 86_400, jetzt.epochSecond).map { e ->
                (e.beginnUtc + e.einschlafen * 60L) to (e.beginnUtc + e.aufwachen * 60L)
            }
            val ruhe = nacht?.ruhepuls ?: frueher.map { it.ruhepuls }.filter { it > 0 }.takeIf { it.isNotEmpty() }?.average()?.roundToInt() ?: 60
            Gesundheitsrechnung.tagesherz(minutenpuls, schlafSpannen, ruhe, maxpulsFuer(context), LocalTime.now(), zone)
        }.getOrDefault(emptyList())
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
            glaeser = heuteWasser.map { it.startTime }.sorted(),
            wocheWasser = Gesundheitsrechnung.reihe(woche, wasserJeTag, 0),
            wasserZiel = Drinktervall.ziel(context),
            supplemente = SupCycle.heute(context),
            koffein = koffein.sortedBy { it.first },
            mitDrinktervall = UhrApps.hat(apps, Drinktervall.UUID),
            mitSupCycle = UhrApps.hat(apps, SupCycle.UUID),
            tagesherz = tagesherz,
            eintraege = eintraege.sortedByDescending { it.zeit },
            blutdruck = blutdruck,
            glasEintraege = heuteWasser.map { Glaseintrag(it.startTime, it.metadata.id, it.metadata.dataOrigin.packageName == context.packageName) },
            // Die Fake-Bibliothek meldet 0 statt nichts.
            letzteDaten = runCatching { api.getLatestTimestamp() }.getOrNull()?.takeIf { it > 0 }?.let { Instant.ofEpochSecond(it) },
        )
    }

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

    suspend fun nacht(api: HealthDataApi, beginnUtc: Long): NachtDetail? = withContext(Dispatchers.IO) {
        val umgebung = runCatching { api.getNaechte(beginnUtc - 30L * 86_400, beginnUtc + 30L * 86_400) }.getOrDefault(emptyList())
        val e = umgebung.firstOrNull { it.beginnUtc == beginnUtc } ?: return@withContext null
        val n = Gesundheitsrechnung.nacht(e, runCatching { api.getNachtAbschnitte(beginnUtc) }.getOrDefault(emptyList()))
        val von = n.einschlafen.epochSecond - 1800
        val bis = n.aufwachen.epochSecond + 1800
        NachtDetail(
            nacht = n,
            puls = runCatching { api.getHealthDataForRange(von, bis) }.getOrDefault(emptyList())
                .filter { it.heartRate > 0 }.map { it.timestamp to it.heartRate },
            spo2 = runCatching { api.getSpo2Readings(von, bis) }.getOrDefault(emptyList()).map { it.timestamp to it.spo2Percent },
            vorige = umgebung.lastOrNull { it.beginnUtc < beginnUtc }?.beginnUtc,
            naechste = umgebung.firstOrNull { it.beginnUtc > beginnUtc }?.beginnUtc,
            davor = umgebung.filter { it.beginnUtc < beginnUtc }.sortedBy { it.beginnUtc }.takeLast(7),
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
     * kommen neu aus der Sorte.
     */
    suspend fun aendereGetraenk(context: Context, id: String, sorte: Int, milch: Boolean, zucker: Boolean, koffeinfrei: Boolean): Boolean = withContext(Dispatchers.IO) {
        val p = Kaffeesorten.portion(Kaffeesorten.art(sorte, milch, zucker, koffeinfrei)) ?: return@withContext false
        try {
            val klient = Akte(context).bereit() ?: return@withContext false
            val alt = klient.readRecord(NutritionRecord::class, id).record
            klient.updateRecords(listOf(NutritionRecord(
                startTime = alt.startTime, startZoneOffset = alt.startZoneOffset,
                endTime = alt.endTime, endZoneOffset = alt.endZoneOffset,
                name = Kaffeesorten.name(context, p),
                caffeine = if (p.koffeinMg > 0) Mass.milligrams(p.koffeinMg) else null,
                energy = androidx.health.connect.client.units.Energy.kilocalories(p.kcal),
                sugar = if (p.zuckerG > 0) Mass.grams(p.zuckerG) else null,
                // DIESELBEN METADATEN: daran erkennt die Akte den Eintrag, der ersetzt wird.
                metadata = alt.metadata,
            )))
            true
        } catch (e: Exception) {
            Log.w(TAG, "Getraenk aendern: " + e.message)
            false
        }
    }

    /**
     * Der Maximalpuls fuer die Belastung: der eingestellte, wenn er nicht die
     * Vorgabe ist, sonst Tanaka nach dem Alter aus den Gesundheitseinstellungen
     * der Uhr - wie bei den Kalorien eines Trainings.
     */
    private suspend fun maxpulsFuer(context: Context): Int {
        val eingestellt = KieselEinstellungen.stand(context).maxpuls
        if (eingestellt != KieselEinstellungen.MAXPULS_VORGABE) return eingestellt
        val alter = runCatching {
            org.koin.mp.KoinPlatform.getKoin().getOrNull<io.rebble.libpebblecommon.connection.LibPebble>()
                ?.healthSettings?.first()?.ageYears
        }.getOrNull()
        return alter?.takeIf { it in 10..100 }?.let { (208 - 0.7 * it).roundToInt() } ?: KieselEinstellungen.MAXPULS_VORGABE
    }

    /** Eine Tasse von Hand - als Ernaehrungseintrag mit Koffein, wie in Kiesel-Helper. */
    suspend fun trinkeKoffein(context: Context, name: String, mg: Int): Boolean = withContext(Dispatchers.IO) {
        val jetzt = Instant.now()
        try {
            Akte(context).bereit()?.insertRecords(listOf(NutritionRecord(
                startTime = jetzt, startZoneOffset = versatz(jetzt),
                endTime = jetzt.plusSeconds(60), endZoneOffset = versatz(jetzt),
                name = name,
                caffeine = Mass.milligrams(mg.toDouble()),
                // Eigene Kennung per manualEntry - manualEntryWithId setzte die der Akte.
                metadata = Metadata.manualEntry("koffein-${jetzt.epochSecond}", 0L, Device(type = Device.TYPE_PHONE)),
            ))) != null
        } catch (e: Exception) {
            Log.w(TAG, "Koffein eintragen: " + e.message)
            false
        }
    }
}
