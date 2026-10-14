package coredevices.pebble.kiesel

import android.content.Context
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
import androidx.health.connect.client.time.TimeRangeFilter
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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

    /**
     * Der zuletzt gelesene Stand, solange der Prozess lebt. Die Ansichten
     * beginnen damit statt mit nichts - sonst rutschte die Zeile "Body" nach
     * jeder Rueckkehr auf die Gesundheitsseite erst spaet in die Liste.
     */
    @Volatile
    var zuletzt: Koerperstand? = null
        private set

    /**
     * So lange gilt [zuletzt] beim Neuaufbau der Gesundheitsseite als frisch.
     * Gewicht kommt von der Waage, nie von der Uhr: frueher las die Zeile bei
     * jedem Uhrschub 7 Arten ueber ein Jahr (je 15-50 ms Akte).
     */
    const val FRISCH_MS = 10 * 60 * 1000L

    // Wann [zuletzt] gelesen wurde (elapsedRealtime) und in welcher Generation von [veraltet].
    @Volatile
    private var gelesenUm = 0L
    @Volatile
    private var gelesenGeneration = -1L
    @Volatile
    private var generation = 0L

    /** Die App war verlassen - eine Waagen-App kann inzwischen geschrieben haben. */
    fun veraltet() {
        generation++
    }

    /** Die Werte der Akte; mit [maxAlterMs] > 0 darf es der Stand von eben sein ([FRISCH_MS]). */
    suspend fun lies(context: Context, maxAlterMs: Long = 0): Koerperstand {
        Gesundheitsdaten.beobachte(context)
        zuletzt?.takeIf { gelesenGeneration == generation && SystemClock.elapsedRealtime() - gelesenUm < maxAlterMs }?.let { return it }
        val gen = generation
        val beginn = SystemClock.elapsedRealtime()
        return liesAkte(context).also {
            zuletzt = it
            gelesenUm = beginn
            gelesenGeneration = gen
        }
    }

    private suspend fun liesAkte(context: Context): Koerperstand = withContext(Dispatchers.IO) {
        val klient = Akte(context).bereit() ?: return@withContext Koerperstand()
        val jetzt = Instant.now()
        val jahr = TimeRangeFilter.between(jetzt.minus(Duration.ofDays(TAGE)), jetzt)
        // Jede Art fuer sich: ein verweigertes Recht kostet nur diese Art, nicht die Seite.
        // Alle 7 zugleich: nacheinander wartete die Zeile 7 Akte-Aufrufe ab.
        fun <T : Record> art(name: String, klasse: KClass<T>, wert: (T) -> Koerpermessung) =
            async { versuche(TAG, "$name lesen") { klient.lies(context, klasse, jahr).map(wert) }.orEmpty() }
        val gewicht = art("Gewicht", WeightRecord::class) { Koerpermessung(it.time, it.weight.inKilograms) }
        val fett = art("Fett", BodyFatRecord::class) { Koerpermessung(it.time, it.percentage.value) }
        val magermasse = art("Magermasse", LeanBodyMassRecord::class) { Koerpermessung(it.time, it.mass.inKilograms) }
        val knochen = art("Knochen", BoneMassRecord::class) { Koerpermessung(it.time, it.mass.inKilograms) }
        val wasser = art("Wasser", BodyWaterMassRecord::class) { Koerpermessung(it.time, it.mass.inKilograms) }
        val grundumsatz = art("Grundumsatz", BasalMetabolicRateRecord::class) { Koerpermessung(it.time, it.basalMetabolicRate.inKilocaloriesPerDay) }
        val groesse = async { groesse(context, klient, jetzt) }
        Koerperstand(
            gewicht = gewicht.await(),
            fett = fett.await(),
            magermasse = magermasse.await(),
            knochen = knochen.await(),
            wasser = wasser.await(),
            grundumsatz = grundumsatz.await(),
            groesse = groesse.await(),
        )
    }

    /**
     * Die neueste Groesse, gleich wie alt - sie wird einmal eingetragen und steht dann jahrelang.
     * Wie weit zurueck das reicht, entscheidet das Recht auf den Verlauf.
     */
    private suspend fun groesse(context: Context, klient: HealthConnectClient, jetzt: Instant): Koerpermessung? = versuche(TAG, "Groesse lesen") {
        klient.liesEinen(context, HeightRecord::class, TimeRangeFilter.before(jetzt), neuester = true)
            ?.let { Koerpermessung(it.time, it.height.inMeters) }
    }
}
