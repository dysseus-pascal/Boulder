package coredevices.pebble.timestyle.bild

import co.touchlab.kermit.Logger
import coreapp.pebble.generated.resources.Res
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.offsetAt
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/*
 * WAS DIE VORSCHAU ZEIGT: Uhrzeit und Datum echt (jetzt, in der Zone des
 * Telefons - Boulder stellt die Uhr danach), 24 h und Masseinheit wie auf der
 * Uhr eingestellt, den Akku der Uhr, wenn sie ihn gemeldet hat, und aus
 * Boulders Daten das Wetter, das die Uhr hat, sowie Schritte, Distanz, Puls
 * und Schlaf von heute (TsDaten). Nur den Akku einer Uhr, die nie verbunden
 * war, kennt Boulder nicht - dafuer gilt der Wert des Pruefbaus TS_DEMO.
 */

/** Der Akku, wenn die Uhr ihn nie gemeldet hat (= TS_DEMO: sidebar_widgets.c). */
const val TS_BEISPIEL_AKKU = 80

/**
 * Der Zustand fuer die Vorschau zum Zeitpunkt [jetzt]. [vierundzwanzig] und
 * [metrisch] wie auf der Uhr eingestellt, [akku] der gemeldete Akku der Uhr
 * (null: [TS_BEISPIEL_AKKU]), [daten] was Boulder von der Uhr weiss.
 */
fun tsVorschauZustand(
    jetzt: Instant,
    zone: TimeZone,
    vierundzwanzig: Boolean,
    metrisch: Boolean,
    akku: Int?,
    daten: TsDaten,
): TsZustand {
    val ort = jetzt.toLocalDateTime(zone)
    val utcSekundeDesTages = (((jetzt.epochSeconds % 86400) + 86400) % 86400).toInt()
    return TsZustand(
        stunde = ort.hour,
        minute = ort.minute,
        sekunde = ort.second,
        tag = ort.day,
        monat = ort.month.ordinal,
        jahr = ort.year,
        // tm_wday: 0 = Sonntag; ISO: 1 = Montag ... 7 = Sonntag.
        wochentag = ort.dayOfWeek.ordinal.let { (it + 1) % 7 },
        jahresTag = ort.dayOfYear - 1,
        vierundzwanzig = vierundzwanzig,
        utcVersatzSekunden = zone.offsetAt(jetzt).totalSeconds.toLong(),
        beats = TsTexte.beats(utcSekundeDesTages),
        wetter = daten.wetter,
        schritte = daten.schritte,
        distanzMeter = daten.distanzMeter,
        metrisch = metrisch,
        puls = daten.puls,
        schlafSekunden = daten.schlafSekunden,
        ruhigerSchlafSekunden = daten.ruhigerSchlafSekunden,
        akkuProzent = akku?.coerceIn(0, 100) ?: TS_BEISPIEL_AKKU,
        laedt = false,
        verbunden = daten.verbunden,
    )
}

/**
 * Die Dateien des Bildes aus den Compose-Ressourcen (files/timestyle/), EINMAL
 * gelesen und dann fuer Vorschau und Kachel geteilt.
 */
object TsRessourcenSpeicher {
    private val logger = Logger.withTag("TsRessourcen")
    private val sperre = Mutex()
    private var geladen: TsRessourcen? = null

    /** Die Ressourcen; null, wenn eine Datei fehlt (dann steht der Fehler im Log). */
    suspend fun hole(): TsRessourcen? = sperre.withLock {
        geladen?.let { return@withLock it }
        try {
            val dateien = TsRessourcen.DATEIEN.associateWith { Res.readBytes("files/timestyle/$it") }
            TsRessourcen.aus { dateien.getValue(it) }.also { geladen = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(e) { "TimeStyle resources missing or unreadable" }
            null
        }
    }
}
