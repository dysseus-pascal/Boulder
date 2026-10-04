package coredevices.pebble.kiesel

import android.content.Context
import co.touchlab.kermit.Logger
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.UUID
import kotlin.reflect.KClass

/**
 * Was die Gesundheitsseite von Hand in die Akte schreibt: eine Tasse
 * eintragen, ein Getraenk umstellen, eigene Eintraege loeschen. Getrennt von
 * [Gesundheitsdaten], damit dort nur gelesen wird.
 */
internal object Akteneintraege {
    // Derselbe Log-Name wie beim Lesen: im Log bleibt die Gesundheitsseite eine Quelle.
    private const val TAG = "Gesundheit"
    private val logger = Logger.withTag(TAG)

    /**
     * Eintraege aus der Akte loeschen - nur Boulders eigene, andere lehnt die
     * Akte ohnehin ab. Die Uhr erfaehrt davon nichts: ein geloeschtes Glas
     * zaehlt Drinktervall auf der Uhr weiter.
     */
    suspend fun loesche(context: Context, klasse: KClass<out Record>, ids: List<String>): Boolean = withContext(Dispatchers.IO) {
        try {
            Akte(context).bereit()?.deleteRecords(klasse, recordIdsList = ids, clientRecordIdsList = emptyList()) != null
        } catch (e: Exception) {
            logger.w { "Loeschen: " + e.message }
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
            logger.w { "Getraenk aendern: " + e.message }
            false
        }
    }

    /**
     * Eine Tasse von Hand - mit denselben Werten, die die Uhr fuer diese Sorte eintraegt.
     * [jetzt] nur fuer Tests: zwei Tassen in derselben Sekunde.
     */
    suspend fun trinkeKoffein(context: Context, art: Int, jetzt: Instant = Instant.now()): Boolean = withContext(Dispatchers.IO) {
        val p = Kaffeesorten.portion(art) ?: return@withContext false
        try {
            // Eigene Kennung per manualEntry - manualEntryWithId setzte die der Akte. EINDEUTIG
            // JE TASSE: bei gleicher Kennung ersetzte die Akte die erste. Der Anfang bleibt
            // "koffein-", an ihm erkennt die Gesundheitsseite das Getraenk.
            val metadaten = Metadata.manualEntry("koffein-${jetzt.epochSecond}-${UUID.randomUUID()}", 0L, Device(type = Device.TYPE_PHONE))
            Akte(context).bereit()?.insertRecords(listOf(Kaffeesorten.satz(p, Kaffeesorten.name(context, p), metadaten, jetzt))) != null
        } catch (e: Exception) {
            logger.w { "Koffein eintragen: " + e.message }
            false
        }
    }
}
