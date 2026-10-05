package coredevices.pebble.timestyle.bild

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.LatestHeartRate
import io.rebble.libpebblecommon.database.entity.NachtAbschnittEntity
import io.rebble.libpebblecommon.database.entity.NachtEntity
import io.rebble.libpebblecommon.database.entity.WeatherAppEntry
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.offsetAt
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/*
 * WAS DIE UHR GERADE ZEIGT, AUS BOULDERS DATEN: das Wetter, das Boulder ihr
 * geschickt hat (Ort 0), und Schritte, Distanz, Puls und Schlaf von heute aus
 * den Gesundheitsdaten, die sie Boulder geschickt hat - jeweils nach der Regel
 * der Firmware. WARUM: vorher zeigte die Vorschau feste Beispielwerte (22
 * Grad, 5523 Schritte) und wich so im Normalbetrieb von der Uhr ab.
 * GRENZE: Gesundheitsdaten kommen von der Uhr in Stapeln - die letzten
 * Minuten kann die Uhr schon gezaehlt haben, Boulder noch nicht.
 */

private val logger = Logger.withTag("TsDaten")

/** Was die Vorschau ausser Zeit, Einheiten und Akku von der Uhr wissen muss. */
data class TsDaten(
    /** null: kein (frisches) Wetter auf der Uhr - "..." ohne Symbol. */
    val wetter: TsWetter?,
    val schritte: Int,
    val distanzMeter: Int,
    val puls: Int,
    val schlafSekunden: Int,
    val ruhigerSchlafSekunden: Int,
    /** Die Uhr ist mit Boulder verbunden (sonst zeigt sie das Trennungssymbol). */
    val verbunden: Boolean,
) {
    companion object {
        /** Solange nichts gelesen ist: so, wie eine Uhr ohne Daten es zeigt. */
        val LEER = TsDaten(null, 0, 0, 0, 0, 0, verbunden = true)
    }
}

/** Woher die Daten kommen - in Boulder LibPebble (tsDatenQuelle), im Test ein Nachbau. */
interface TsDatenQuelle {
    suspend fun wetter(): WeatherAppEntry?

    /** Schritte und Distanz (cm) der Minuten in [von, bis), null ohne Daten. */
    suspend fun schritteUndDistanzCm(von: Long, bis: Long): Pair<Long, Long>?

    suspend fun letzterPuls(): LatestHeartRate?

    /** Boulders Naechte (Tag 88) mit Beginn in [von, bis). */
    suspend fun naechte(von: Long, bis: Long): List<NachtEntity>

    /** Die Phasen der Naechte mit Beginn in [von, bis). */
    suspend fun nachtAbschnitte(von: Long, bis: Long): List<NachtAbschnittEntity>
}

/** Wie lange ein Pulswert "jetzt" bleibt (HS_MAX_AGE_HR_SAMPLE, health_service.c). */
internal const val TS_PULS_HOECHSTALTER_S = 15 * 60

/** Bis zu welcher Uhrzeit ein Schlaf zu heute zaehlt (ACTIVITY_LAST_SLEEP_MINUTE_OF_DAY). */
internal const val TS_SCHLAF_TAGESGRENZE_MIN = 21 * 60

/**
 * health_service_peek_current_value(HeartRateBPM): der letzte Puls, wenn er
 * juenger als 15 Minuten ist, sonst 0 (so zeigt die Uhr dann "0").
 */
internal fun tsPulsJetzt(letzter: LatestHeartRate?, jetztUtc: Long): Int {
    if (letzter == null) return 0
    return if (jetztUtc - letzter.timestampEpochSec >= TS_PULS_HOECHSTALTER_S) 0 else letzter.bpm
}

/**
 * Die Grenzen des Schlaftages, der [jetzt] enthaelt: (gestern, heute] je
 * Mitternacht + 21 h in [zone] - wie die Firmware (time_util_get_midnight_of +
 * ACTIVITY_LAST_SLEEP_MINUTE_OF_DAY).
 */
internal fun tsSchlafFenster(jetzt: Instant, zone: TimeZone): Pair<Long, Long> {
    val heute = jetzt.toLocalDateTime(zone).date
    val grenzeS = TS_SCHLAF_TAGESGRENZE_MIN * 60L
    val bis = heute.atStartOfDayIn(zone).epochSeconds + grenzeS
    val von = heute.minus(1, DateTimeUnit.DAY).atStartOfDayIn(zone).epochSeconds + grenzeS
    return von to bis
}

/** Die Phase "tief" in Boulders Naechten (schlafanalyse.c). */
private const val PHASE_TIEF = 2

/**
 * Schlaf heute aus Boulders Naechten (Tag 88), wie die Uhr ihn kuenftig
 * zeigt: jede Nacht, die im Schlaftag ENDET ([tsSchlafFenster]), mit ihrem
 * Schlaf ohne Wachminuten; ruhig ist die Phase tief. NICHT AUS DEN
 * SCHLAFSITZUNGEN (Tag 84): rechnet die Uhr eine Nacht nach, bleiben dort
 * die alten Bloecke stehen, und eine Summe zaehlte sie doppelt. Ergebnis in
 * Sekunden (gesamt, ruhig).
 */
internal fun tsSchlafHeute(naechte: List<NachtEntity>, abschnitte: List<NachtAbschnittEntity>, fenster: Pair<Long, Long>): Pair<Int, Int> {
    val (von, bis) = fenster
    var gesamtMin = 0L
    var ruhigMin = 0L
    for (n in naechte) {
        if (n.aufwachen <= n.einschlafen) continue
        val ende = n.beginnUtc + n.aufwachen * 60L
        if (ende <= von || ende > bis) continue
        gesamtMin += n.schlafMinuten
        ruhigMin += abschnitte.filter { it.beginnUtc == n.beginnUtc && it.phase == PHASE_TIEF }
            .sumOf { (minOf(it.bis, n.aufwachen) - maxOf(it.von, n.einschlafen)).coerceAtLeast(0).toLong() }
    }
    return (gesamtMin * 60).toInt() to (ruhigMin * 60).toInt()
}

/**
 * Liest alles fuer die Vorschau zum Zeitpunkt [jetzt] in [zone] (= Zone der
 * Uhr). [verbunden]: ob die Uhr gerade verbunden ist; [uvJeStunde]: ob ihre
 * Firmware den UV-Wert der Stunde zeigt (ab b24, sonst den des Tages).
 */
suspend fun tsDatenLesen(
    quelle: TsDatenQuelle,
    jetzt: Instant,
    zone: TimeZone,
    verbunden: Boolean,
    uvJeStunde: Boolean = true,
): TsDaten {
    val jetztUtc = jetzt.epochSeconds
    val mitternacht = jetzt.toLocalDateTime(zone).date.atStartOfDayIn(zone).epochSeconds
    // health_service_sum_today: von Mitternacht bis jetzt (die laufende Minute eingeschlossen).
    val (schritte, distanzCm) = quelle.schritteUndDistanzCm(mitternacht, jetztUtc + 1) ?: (0L to 0L)
    val fenster = tsSchlafFenster(jetzt, zone)
    // Eine Nacht, die im Fenster endet, beginnt hoechstens einen Tag frueher.
    val ab = fenster.first - 24 * 60 * 60
    // Boulders Naechte koennen ausfallen (etwa solange der Umzug nach boulder.db
    // scheitert): dann zeigt das Bild Schlaf 0, Wetter, Schritte und Puls bleiben.
    val (schlaf, ruhig) = try {
        val naechte = quelle.naechte(ab, fenster.second)
        val abschnitte = if (naechte.isEmpty()) emptyList() else quelle.nachtAbschnitte(ab, fenster.second)
        tsSchlafHeute(naechte, abschnitte, fenster)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.w(e) { "TimeStyle preview: Boulder's nights unreadable, sleep shown as 0" }
        0 to 0
    }
    val uhrVersatz = zone.offsetAtSekunden(jetzt)
    return TsDaten(
        wetter = tsWetterAusSatz(quelle.wetter(), jetzt, uhrVersatz, uvJeStunde),
        schritte = schritte.toInt(),
        // WalkedDistanceMeters: die Uhr zaehlt Meter, Boulder speichert cm.
        distanzMeter = (distanzCm / 100).toInt(),
        puls = tsPulsJetzt(quelle.letzterPuls(), jetztUtc),
        schlafSekunden = schlaf,
        ruhigerSchlafSekunden = ruhig,
        verbunden = verbunden,
    )
}

private fun TimeZone.offsetAtSekunden(jetzt: Instant): Long = offsetAt(jetzt).totalSeconds.toLong()
