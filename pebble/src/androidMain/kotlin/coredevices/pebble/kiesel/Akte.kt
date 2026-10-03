package coredevices.pebble.kiesel

import android.content.Context
import java.time.ZoneOffset
import java.time.ZoneId
import java.time.Instant
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlin.coroutines.cancellation.CancellationException
import kotlin.reflect.KClass

/**
 * Der Zeitzonenversatz zu einem Zeitpunkt - fuer jeden Eintrag in die Akte.
 * Ohne ihn zeigte die Akte auf Reisen die Ortszeit von hier statt von dort.
 */
internal fun versatz(t: Instant): ZoneOffset = ZoneId.systemDefault().rules.getOffset(t)

/**
 * Die Gesundheitsakte (Health Connect), wenn es sie gibt.
 *
 * NULL HEISST: auf diesem Telefon nicht da oder nicht bereit. Wer schreibt,
 * sagt es dann im Protokoll, statt zu scheitern. Ob die Erlaubnis fehlt,
 * zeigt erst das Schreiben selbst - die Akte weist dann ab.
 */
internal class Akte(private val context: Context) {
    fun bereit(): HealthConnectClient? =
        if (HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE) {
            HealthConnectClient.getOrCreate(context)
        } else {
            null
        }
}

/** Wessen Eintraege eine Abfrage der Akte liest. */
internal enum class Herkunft {
    /** Die aller Apps. Ohne das Leserecht weist die Akte die ganze Abfrage ab. */
    ALLE,

    /** Nur Boulders eigene - die gibt die Akte immer heraus, auch im Hintergrund. */
    EIGENE,

    /**
     * Lesen - und im Hintergrund nur das Eigene. Ohne das Recht, im Hintergrund
     * zu lesen, weist Health Connect jede Abfrage ab, die fremde Eintraege
     * treffen koennte; das Widget liest aber im Hintergrund. Beim zweiten
     * Versuch fragt es nur nach Boulders eigenen Eintraegen - Drinktervall,
     * Koffein, Trainings.
     */
    ALLE_SONST_EIGENE,
}

/** So viele Saetze gibt die Akte hoechstens je Seite heraus. */
private const val SEITE = 1000

/** Schutz vor einer Akte, die immer weiterblaettert: 100'000 Saetze sind weit ueber jedem Fenster. */
private const val HOECHSTENS_SEITEN = 100

private const val TAG = "Akte"

/**
 * Alle Eintraege von [klasse] in [zeitraum], aelteste zuerst - DAS EINE LESEN
 * FUER ALLE SEITEN der App.
 *
 * ALLE SEITEN DER ANTWORT: eine allein endet nach 1000 Saetzen bei den
 * AELTESTEN und verloere die neuesten - ein Schrittzaehler schreibt in einem
 * Vierteljahr leicht mehr Distanzen, eine Waage in einem Jahr leicht mehr
 * Wiegungen.
 *
 * SCHEITERT EINE SPAETERE SEITE (etwa am Rate-Limit), bleibt es bei den
 * gelesenen, mit einer Warnung im Log - nie weniger als frueher die eine
 * Seite. Nur die erste wirft; erst dann greift [Herkunft.ALLE_SONST_EIGENE]
 * auf das Eigene zurueck.
 */
internal suspend fun <T : Record> HealthConnectClient.lies(
    context: Context,
    klasse: KClass<T>,
    zeitraum: TimeRangeFilter,
    herkunft: Herkunft = Herkunft.ALLE,
): List<T> = mitHerkunft(context, herkunft) { quellen ->
    val alle = mutableListOf<T>()
    var marke: String? = null
    var seiten = 0
    do {
        val anfrage = ReadRecordsRequest(klasse, zeitraum, dataOriginFilter = quellen, pageSize = SEITE, pageToken = marke)
        // Warf eine spaetere Seite, verloere der Rueckgriff auf das Eigene die fremden Seiten davor.
        val antwort = if (marke == null) readRecords(anfrage)
        else versuche(TAG, "${klasse.simpleName}, Seite ${seiten + 1}") { readRecords(anfrage) } ?: break
        alle += antwort.records
        // Leer oder fehlend heisst: das war die letzte Seite.
        marke = antwort.pageToken?.ifEmpty { null }
    } while (marke != null && ++seiten < HOECHSTENS_SEITEN)
    alle
}

/**
 * Nur ein Eintrag: der juengste in [zeitraum] ([neuester]) oder der
 * aelteste. Eine Seite mit einem Satz - fuer die letzte Groesse, die letzte
 * Wiegung, das erste Glas, wo ein ganzes Jahr zu lesen Verschwendung waere.
 */
internal suspend fun <T : Record> HealthConnectClient.liesEinen(
    context: Context,
    klasse: KClass<T>,
    zeitraum: TimeRangeFilter,
    neuester: Boolean,
    herkunft: Herkunft = Herkunft.ALLE,
): T? = mitHerkunft(context, herkunft) { quellen ->
    readRecords(ReadRecordsRequest(klasse, zeitraum, dataOriginFilter = quellen, ascendingOrder = !neuester, pageSize = 1))
        .records.firstOrNull()
}

/** Liest mit den Quellen, die [herkunft] verlangt - bei [Herkunft.ALLE_SONST_EIGENE] notfalls ein zweites Mal. */
private suspend fun <R> mitHerkunft(context: Context, herkunft: Herkunft, lesen: suspend (Set<DataOrigin>) -> R): R {
    val eigene = setOf(DataOrigin(context.packageName))
    return when (herkunft) {
        Herkunft.ALLE -> lesen(emptySet())
        Herkunft.EIGENE -> lesen(eigene)
        Herkunft.ALLE_SONST_EIGENE -> try {
            lesen(emptySet())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Jeder Fehler, nicht nur das fehlende Recht, wie zuvor: scheitert
            // auch der zweite Versuch, wirft er.
            lesen(eigene)
        }
    }
}
