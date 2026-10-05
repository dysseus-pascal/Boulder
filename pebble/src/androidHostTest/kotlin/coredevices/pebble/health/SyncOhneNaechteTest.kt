package coredevices.pebble.health

import com.russhwolf.settings.MapSettings
import com.viktormykhailiv.kmp.health.HealthAggregatedRecord
import com.viktormykhailiv.kmp.health.HealthDataType
import com.viktormykhailiv.kmp.health.HealthManager
import com.viktormykhailiv.kmp.health.HealthRecord
import com.viktormykhailiv.kmp.health.records.ExerciseSessionRecord
import com.viktormykhailiv.kmp.health.records.SleepSessionRecord
import com.viktormykhailiv.kmp.health.region.RegionalPreferences
import coredevices.util.AppResumed
import io.rebble.libpebblecommon.connection.HealthDataApi
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.database.entity.OverlayDataEntity
import io.rebble.libpebblecommon.health.OverlayType
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * BOULDERS NAECHTE NICHT LESBAR (etwa solange der Umzug nach boulder.db
 * scheitert): der Sync zur Gesundheitsakte haelt dann nur an, was ohne die
 * Naechte nicht geht. Frueher brach der ganze Lauf ab, sobald ein Schlaf in den
 * Aktivitaetsdaten stand - auch Trainings und SpO2 blieben liegen.
 *
 * Echter PlatformHealthSync; gestellt sind die Grenzen: die Akte (HealthManager)
 * und Boulders Daten (HealthDataApi).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SyncOhneNaechteTest {
    private val t0 = 1_790_900_000L

    /** Training vor dem Schlaf, der Schlaf, ein Training danach. */
    private val vorher = overlay(t0, OverlayType.OpenWorkout)
    private val schlaf = overlay(t0 + 3_600, OverlayType.Sleep)
    private val nachher = overlay(t0 + 40_000, OverlayType.OpenWorkout)

    private fun overlay(beginn: Long, art: OverlayType) =
        OverlayDataEntity(beginn, 1_800, art.value, 0, 0, 0, 0, 0)

    @Volatile
    private var naechteLesbar = false

    /** Was in der Akte steht, nach Art und Beginn. */
    private val akte = mutableListOf<Pair<String, Long>>()

    private val healthManager = object : HealthManager {
        override fun isAvailable(): Result<Boolean> = Result.success(true)
        override suspend fun isAuthorized(readTypes: List<HealthDataType>, writeTypes: List<HealthDataType>) = Result.success(true)
        override suspend fun requestAuthorization(readTypes: List<HealthDataType>, writeTypes: List<HealthDataType>, background: Boolean): Result<Boolean> = TODO()
        override suspend fun isRevokeAuthorizationSupported(): Result<Boolean> = TODO()
        override suspend fun revokeAuthorization(): Result<Unit> = TODO()
        override suspend fun hasReadHealthDataInBackgroundPermission(): Result<Boolean> = TODO()
        override suspend fun requestReadHealthDataInBackgroundPermission(): Result<Boolean> = TODO()
        override suspend fun readData(startTime: Instant, endTime: Instant, type: HealthDataType): Result<List<HealthRecord>> = TODO()
        override suspend fun aggregate(startTime: Instant, endTime: Instant, type: HealthDataType): Result<HealthAggregatedRecord> = TODO()
        override suspend fun getRegionalPreferences(): Result<RegionalPreferences> = TODO()
        override suspend fun writeData(records: List<HealthRecord>): Result<Unit> {
            records.forEach { r ->
                when (r) {
                    is ExerciseSessionRecord -> akte += "training" to r.startTime.epochSeconds
                    is SleepSessionRecord -> akte += "schlaf" to r.startTime.epochSeconds
                    else -> akte += "sonst" to 0L
                }
            }
            return Result.success(Unit)
        }
    }

    /** Boulders Daten: nur was dieser Sync fragt; alles andere wirft. */
    private val daten = Proxy.newProxyInstance(HealthDataApi::class.java.classLoader, arrayOf(HealthDataApi::class.java)) { _, methode, args ->
        when (methode.name) {
            "getLatestTimestamp", "getLatestSpo2Reading" -> null
            "getOverlayEntriesAfter" -> listOf(vorher, schlaf, nachher).filter { it.startTime > args[0] as Long }
            "getNaechte", "getNachtAbschnitte" ->
                if (naechteLesbar) emptyList<Any>() else throw IllegalStateException("Nachtumzug gescheitert")
            else -> throw UnsupportedOperationException(methode.name)
        }
    } as HealthDataApi

    private val nieGefragt = Proxy.newProxyInstance(LibPebble::class.java.classLoader, arrayOf(LibPebble::class.java)) { _, methode, _ ->
        throw UnsupportedOperationException(methode.name)
    } as LibPebble

    @Test
    fun ohneNaechteWartetNurDerSchlafUndWasDanachKommt() = runBlocking {
        val tracker = HealthSyncTracker(MapSettings()).apply { setEnabled(true) }
        val sync = PlatformHealthSync(nieGefragt, tracker, AppResumed(), healthManager, daten)

        sync.sync()
        // Das Training davor ist drin; der Schlaf wartet - und mit ihm alles danach, denn der
        // Zeiger ist einer, und ein zweites Mal geschrieben stuende es doppelt in der Akte.
        assertEquals(listOf("training" to vorher.startTime), akte)
        assertEquals(vorher.startTime, tracker.lastSyncedOverlayTimestamp)

        // Sind die Naechte wieder lesbar, kommt der Rest - nichts doppelt, nichts verloren.
        naechteLesbar = true
        sync.sync()
        assertEquals(listOf("training" to vorher.startTime, "schlaf" to schlaf.startTime, "training" to nachher.startTime), akte)
        assertEquals(nachher.startTime, tracker.lastSyncedOverlayTimestamp)
    }
}
