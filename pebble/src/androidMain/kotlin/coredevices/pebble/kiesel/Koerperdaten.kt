package coredevices.pebble.kiesel

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import kotlin.reflect.KClass

/** Die Leserechte der Koerperseite - Gewicht und Zusammensetzung schreiben Waagen-Apps. */
internal val KOERPER_RECHTE: Set<String> = setOf(
    HealthPermission.getReadPermission(WeightRecord::class),
    HealthPermission.getReadPermission(BodyFatRecord::class),
    HealthPermission.getReadPermission(LeanBodyMassRecord::class),
    HealthPermission.getReadPermission(BoneMassRecord::class),
    HealthPermission.getReadPermission(BodyWaterMassRecord::class),
    HealthPermission.getReadPermission(BasalMetabolicRateRecord::class),
    HealthPermission.getReadPermission(HeightRecord::class),
)

/**
 * Gewicht und Zusammensetzung aus der Akte, nur gelesen. Eigener Lader neben
 * [Gesundheitsdaten.lies]: ein Jahr Werte braucht nur die Koerperseite, nicht das Widget.
 */
internal object Koerperdaten {
    private const val TAG = "Koerper"
    /** Ein Jahr: genug fuer 90 Tage Verlauf und die letzte Messung derer, die selten auf die Waage steigen. */
    private const val TAGE = 365L
    private const val SEITE = 1000
    /** Schutz vor einer Akte, die immer weiterblaettert. */
    private const val HOECHSTENS_SEITEN = 20

    /**
     * Der zuletzt gelesene Stand, solange der Prozess lebt. Die Ansichten
     * beginnen damit statt mit nichts - sonst rutschte die Zeile "Body" nach
     * jeder Rueckkehr auf die Gesundheitsseite erst spaet in die Liste.
     */
    @Volatile
    var zuletzt: Koerperstand? = null
        private set

    suspend fun lies(context: Context): Koerperstand = withContext(Dispatchers.IO) {
        val klient = Akte(context).bereit() ?: return@withContext Koerperstand().also { zuletzt = it }
        val jetzt = Instant.now()
        val jahr = TimeRangeFilter.between(jetzt.minus(Duration.ofDays(TAGE)), jetzt)
        // Jede Art fuer sich: ein verweigertes Recht kostet nur diese Art, nicht die Seite.
        suspend fun <T : Record> art(name: String, klasse: KClass<T>, wert: (T) -> Koerpermessung): List<Koerpermessung> = try {
            klient.liesAlle(klasse, jahr).map(wert)
        } catch (e: Exception) {
            Log.w(TAG, "$name lesen: " + e.message)
            emptyList()
        }
        Koerperstand(
            gewicht = art("Gewicht", WeightRecord::class) { Koerpermessung(it.time, it.weight.inKilograms) },
            fett = art("Fett", BodyFatRecord::class) { Koerpermessung(it.time, it.percentage.value) },
            magermasse = art("Magermasse", LeanBodyMassRecord::class) { Koerpermessung(it.time, it.mass.inKilograms) },
            knochen = art("Knochen", BoneMassRecord::class) { Koerpermessung(it.time, it.mass.inKilograms) },
            wasser = art("Wasser", BodyWaterMassRecord::class) { Koerpermessung(it.time, it.mass.inKilograms) },
            grundumsatz = art("Grundumsatz", BasalMetabolicRateRecord::class) { Koerpermessung(it.time, it.basalMetabolicRate.inKilocaloriesPerDay) },
            groesse = groesse(klient, jetzt),
        ).also { zuletzt = it }
    }

    /**
     * Die neueste Groesse, gleich wie alt - sie wird einmal eingetragen und steht dann jahrelang.
     * Wie weit zurueck das reicht, entscheidet das Recht auf den Verlauf.
     */
    private suspend fun groesse(klient: HealthConnectClient, jetzt: Instant): Koerpermessung? = try {
        klient.readRecords(ReadRecordsRequest(HeightRecord::class, TimeRangeFilter.before(jetzt), ascendingOrder = false, pageSize = 1))
            .records.firstOrNull()?.let { Koerpermessung(it.time, it.height.inMeters) }
    } catch (e: Exception) {
        Log.w(TAG, "Groesse lesen: " + e.message)
        null
    }

    /** Alle Seiten: eine allein endete nach 1000 Saetzen bei den AELTESTEN und verloere die neuesten. */
    private suspend fun <T : Record> HealthConnectClient.liesAlle(klasse: KClass<T>, zeit: TimeRangeFilter): List<T> {
        val alle = mutableListOf<T>()
        var marke: String? = null
        repeat(HOECHSTENS_SEITEN) {
            val antwort = readRecords(ReadRecordsRequest(klasse, zeit, pageSize = SEITE, pageToken = marke))
            alle += antwort.records
            marke = antwort.pageToken?.ifEmpty { null } ?: return alle
        }
        return alle
    }
}
