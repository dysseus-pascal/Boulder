package coredevices.pebble.kiesel

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.time.TimeRangeFilter
import co.touchlab.kermit.Logger
import coredevices.pebble.health.nachtHrvKennung
import io.rebble.libpebblecommon.connection.HealthDataApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt

/**
 * EINMALIGE REPARATUR (0.52): die HRV der Naechte zurueck, die eine Fassung
 * ohne HRV ueberschrieben hatte.
 *
 * WARUM: Bis Firmware b24 lagen die HRV-Fenster nur im RAM der Uhr. Nach einem
 * Firmware-Update am Morgen rechnete die Uhr die Nacht ohne HRV neu und
 * schickte sie mit demselben Beginn noch einmal; bis 0.51 nahm das Ersetzen
 * hrv = 0 mit (seit 0.52 bleibt die gespeicherte, NachtDao.speichereNachtTeil).
 * Der Wert steht aber noch in der Akte: der Sync hatte ihn vorher als
 * Boulders eigenen Satz geschrieben ([nachtHrvKennung]), und Boulder loescht
 * dort nie. Betroffen waren auf dem Telefon die Naechte zum 1.10. und 3.10.2026.
 *
 * NUR LESEN IN DER AKTE: gelesen werden nur Boulders eigene HRV-Saetze; in
 * der Akte wird nichts geschrieben und nichts geloescht. Geaendert wird nur
 * Boulders eigene Nacht, und nur, wenn sie keine HRV hat (0) - die Bedingung
 * steht auch in der Datenbankabfrage selbst ([HealthDataApi.setzeNachtHrvWennLeer]).
 *
 * EINMAL JE INSTALLATION: die Marke [MARKE] steht in eigenen Einstellungen,
 * erst nach einem ganzen Lauf. Ohne Akte, ohne Recht oder bei einem Fehler
 * bleibt sie offen, und der naechste Start versucht es wieder.
 */
internal object HrvReparatur {
    private const val TAG = "HrvReparatur"
    private val logger = Logger.withTag(TAG)

    private const val DATEI = "boulder-einmalig"
    const val MARKE = "hrv-aus-akte-0.52"

    /** So weit zurueck: die betroffenen Naechte liegen Tage zurueck, 60 Tage lassen Luft. */
    const val TAGE = 60L

    /** Das Recht, HRV zu lesen. Ohne es aendert die Reparatur nichts. */
    val RECHT: String = HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class)

    sealed interface Ausgang {
        /** Ganz gelaufen, Marke gesetzt: [repariert] von [ohneHrv] Naechten ohne HRV. */
        data class Gelaufen(val repariert: Int, val ohneHrv: Int) : Ausgang
        data object SchonGelaufen : Ausgang
        data object OhneAkte : Ausgang
        data object OhneRecht : Ausgang
        data object Fehler : Ausgang
    }

    // Ohne Recht nicht bei jedem Oeffnen von Health wieder fragen: einmal je Prozess,
    // der naechste Start versucht es wieder. Wird ein Lauf abgebrochen (Seite verlassen),
    // bleibt das offen.
    private val versucht = AtomicBoolean(false)
    private val sperre = Mutex()

    /**
     * Beim Oeffnen von Health: hoechstens ein Lauf je Prozess, abseits von
     * Verbindung und Benachrichtigungen. Liefert die Zahl reparierter Naechte.
     */
    suspend fun beimOeffnen(context: Context, api: HealthDataApi): Int {
        if (versucht.get()) return 0
        return sperre.withLock {
            if (versucht.get()) return@withLock 0
            val ausgang = withContext(Dispatchers.IO) {
                einmal(context, api, versuche(TAG, "Health Connect for the HRV repair") { Akte(context).bereit() }, Instant.now())
            }
            versucht.set(true)
            (ausgang as? Ausgang.Gelaufen)?.repariert ?: 0
        }
    }

    /** Der eine Lauf, ohne die Sperre je Prozess - so prueft ihn der Test. */
    suspend fun einmal(context: Context, api: HealthDataApi, akte: HealthConnectClient?, jetzt: Instant): Ausgang {
        val einstellungen = context.getSharedPreferences(DATEI, Context.MODE_PRIVATE)
        if (einstellungen.getBoolean(MARKE, false)) return Ausgang.SchonGelaufen
        if (akte == null) {
            logger.i { "HRV repair: Health Connect not available, nothing changed; trying again on the next start" }
            return Ausgang.OhneAkte
        }
        return try {
            if (RECHT !in akte.permissionController.getGrantedPermissions()) {
                logger.w { "HRV repair: no permission to read HRV, nothing changed; trying again on the next start" }
                return Ausgang.OhneRecht
            }
            val von = jetzt.minus(Duration.ofDays(TAGE))
            // ERST ALLES LESEN, DANN SCHREIBEN: scheitert ein Lesen, ist nichts geaendert.
            val ohneHrv = api.getNaechte(von.epochSecond, jetzt.epochSecond).filter { it.hrv <= 0 }
            val gefunden = if (ohneHrv.isEmpty()) emptyMap()
            else werte(
                akte.lies(context, HeartRateVariabilityRmssdRecord::class, TimeRangeFilter.after(von), Herkunft.EIGENE),
                context.packageName,
                ohneHrv.map { it.beginnUtc },
            )
            var repariert = 0
            for (n in ohneHrv) {
                val wert = gefunden[n.beginnUtc] ?: continue
                if (api.setzeNachtHrvWennLeer(n.beginnUtc, wert)) {
                    repariert++
                    logger.i { "HRV repair: night ${n.beginnUtc} gets its $wert ms back" }
                }
            }
            einstellungen.edit().putBoolean(MARKE, true).apply()
            logger.i { "HRV repair: $repariert of ${ohneHrv.size} nights without HRV in the last $TAGE days repaired from Boulder's own Health Connect records" }
            Ausgang.Gelaufen(repariert, ohneHrv.size)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Scheiterte ein Eintrag mitten im Lauf, stehen die davor richtig da;
            // die Marke bleibt offen, und der naechste Lauf traegt den Rest ein.
            logger.e(e) { "HRV repair failed; trying again on the next start" }
            Ausgang.Fehler
        }
    }

    /**
     * Die HRV je Nacht (Beginn in Sekunden) aus den gelesenen Saetzen: nur
     * Boulders eigene ([paket]), nur mit der Kennung, die der Sync genau
     * dieser Nacht gab, nur 1..200 ms - mehr nimmt auch die Akte nicht an.
     */
    fun werte(saetze: List<HeartRateVariabilityRmssdRecord>, paket: String, naechte: List<Long>): Map<Long, Int> =
        naechte.mapNotNull { beginn ->
            val kennung = nachtHrvKennung(beginn)
            val satz = saetze.lastOrNull { it.metadata.dataOrigin.packageName == paket && it.metadata.clientRecordId == kennung }
                ?: return@mapNotNull null
            val ms = satz.heartRateVariabilityMillis
            if (ms in 1.0..200.0) beginn to ms.roundToInt() else null
        }.toMap()
}
