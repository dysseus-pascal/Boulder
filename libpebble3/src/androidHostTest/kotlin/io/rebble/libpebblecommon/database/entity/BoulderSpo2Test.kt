package io.rebble.libpebblecommon.database.entity

import coredev.BlobDatabase
import io.rebble.libpebblecommon.LibPebbleConfig
import io.rebble.libpebblecommon.LibPebbleConfigFlow
import io.rebble.libpebblecommon.database.Database
import io.rebble.libpebblecommon.database.MillisecondInstant
import io.rebble.libpebblecommon.database.dao.HealthSettingsEntryRealDao
import io.rebble.libpebblecommon.database.dao.ValueParams
import io.rebble.libpebblecommon.database.testDatenbank
import io.rebble.libpebblecommon.metadata.WatchType
import io.rebble.libpebblecommon.packets.ProtocolCapsFlag
import io.rebble.libpebblecommon.packets.blobdb.BlobResponse
import io.rebble.libpebblecommon.services.FirmwareVersion
import io.rebble.libpebblecommon.services.blobdb.DbWrite
import io.rebble.libpebblecommon.services.blobdb.WriteType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.coroutines.ContinuationInterceptor
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Boulder: SpO2 zwischen App und Uhr (BoulderSpo2.kt). PebbleOS v4.42 nimmt nur den Satz mit drei
 * Byte an, aeltere Firmware nur ein Byte Intervall und die beiden alten Schalter.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BoulderSpo2Test {
    private var db: Database? = null

    @AfterTest
    fun schliessen() {
        db?.close()
    }

    private fun TestScope.dao(): HealthSettingsEntryRealDao =
        testDatenbank(coroutineContext[ContinuationInterceptor]!!).also { db = it }.healthSettingsDao()

    private fun fw(minor: Int, patch: Int, suffix: String? = null) = FirmwareVersion(
        stringVersion = "v4.$minor.$patch${suffix?.let { "-$it" } ?: ""}",
        timestamp = Instant.fromEpochSeconds(1_790_000_000),
        major = 4,
        minor = minor,
        patch = patch,
        suffix = suffix,
        gitHash = "",
        isRecovery = false,
        isDualSlot = false,
        isSlot0 = false,
    )

    private fun params(firmware: FirmwareVersion) = ValueParams(
        platform = WatchType.EMERY,
        capabilities = setOf(ProtocolCapsFlag.SupportsHealthInsights),
        firmwareVersion = firmware,
        libPebbleConfigFlow = LibPebbleConfigFlow(MutableStateFlow(LibPebbleConfig())),
    )

    private val b39 = params(fw(42, 0, "b39"))
    private val b38 = params(fw(41, 2, "b38"))

    private suspend fun HealthSettingsEntryRealDao.wert(id: String, p: ValueParams): List<UByte>? =
        getEntry(id)!!.value(p)?.toList()

    private suspend fun HealthSettingsEntryRealDao.vonDerUhr(key: String, vararg bytes: Int, sekunden: Long) =
        handleWrite(
            DbWrite(
                token = 1u,
                database = BlobDatabase.WatchPrefs,
                timestamp = sekunden.toUInt(),
                key = key.encodeToByteArray().toUByteArray(),
                value = UByteArray(bytes.size) { bytes[it].toUByte() },
                writeType = WriteType.Write,
            ),
            UHR,
            b39,
        )

    private fun spaeter() = Clock.System.now().epochSeconds + 60

    @Test
    fun jedeFirmwareBekommtIhreLaenge() = runTest {
        val dao = dao()
        dao.setzeSpo2Einstellungen(Spo2Einstellungen(an = true, intervall = HRMonitoringInterval.ThirtyMin, imSport = true))

        assertEquals(listOf<UByte>(1u, 1u, 1u), dao.wert(SATZ, b39))
        assertNull(dao.wert(AN, b39))
        assertNull(dao.wert(SPORT, b39))

        assertEquals(listOf<UByte>(1u), dao.wert(SATZ, b38))
        assertEquals(listOf<UByte>(1u), dao.wert(AN, b38))
        assertEquals(listOf<UByte>(1u), dao.wert(SPORT, b38))
    }

    @Test
    fun eineZeileVon059GehtNichtAnV442() = runTest {
        val dao = dao()
        val zeit = MillisecondInstant(Instant.fromEpochSeconds(1_780_000_000))
        dao.insertOrReplace(HealthSettingsEntry(SATZ, """{"measurementInterval":"OneHour"}""", zeit))
        dao.insertOrReplace(HealthSettingsEntry(AN, """{"enabled":true}""", zeit))
        dao.insertOrReplace(HealthSettingsEntry(SPORT, "{}", zeit))

        assertEquals(Spo2Einstellungen(an = true, intervall = HRMonitoringInterval.OneHour, imSport = false), dao.spo2Einstellungen().first())
        // Die Uhr hat die Schalter beim Start selbst aus ihren alten Schluesseln uebernommen.
        assertNull(dao.wert(SATZ, b39))
        assertEquals(listOf<UByte>(2u), dao.wert(SATZ, b38))
        assertEquals(listOf<UByte>(1u), dao.wert(AN, b38))
    }

    @Test
    fun derSatzVonDerUhrSetztAlleDreiZeilenUndGiltAlsSynchron() = runTest {
        val dao = dao()
        dao.setzeSpo2Einstellungen(Spo2Einstellungen(an = true, intervall = HRMonitoringInterval.TenMin, imSport = false))

        assertEquals(BlobResponse.BlobStatus.Success, dao.vonDerUhr(SATZ, 0, 3, 1, sekunden = spaeter()))

        assertEquals(Spo2Einstellungen(an = false, intervall = HRMonitoringInterval.Disabled, imSport = true), dao.spo2Einstellungen().first())
        assertEquals(listOf<UByte>(0u), dao.wert(AN, b38))
        assertEquals(listOf<UByte>(1u), dao.wert(SPORT, b38))
        assertEquals(emptyList(), dao.dirtyRecordsForWatchInsert(UHR, 0, 0).first().map { it.record.id }.filter { it in BOULDER_SPO2_SCHLUESSEL })
    }

    @Test
    fun einByteVonAltenUhrenBehaeltDieSchalter() = runTest {
        val dao = dao()
        dao.setzeSpo2Einstellungen(Spo2Einstellungen(an = true, intervall = HRMonitoringInterval.TenMin, imSport = true))

        assertEquals(BlobResponse.BlobStatus.Success, dao.vonDerUhr(SATZ, 1, sekunden = spaeter()))

        assertEquals(Spo2Einstellungen(an = true, intervall = HRMonitoringInterval.ThirtyMin, imSport = true), dao.spo2Einstellungen().first())
    }

    @Test
    fun einAlterSchalterVonDerUhrAendertDenSatzFuerV442() = runTest {
        val dao = dao()
        dao.setzeSpo2Einstellungen(Spo2Einstellungen(an = true, intervall = HRMonitoringInterval.OneHour, imSport = true))

        assertEquals(BlobResponse.BlobStatus.Success, dao.vonDerUhr(AN, 0, sekunden = spaeter()))

        assertEquals(listOf<UByte>(0u, 2u, 1u), dao.wert(SATZ, b39))
    }

    @Test
    fun aeltereSchreibenGewinnenNicht() = runTest {
        val dao = dao()
        val vorher = Spo2Einstellungen(an = true, intervall = HRMonitoringInterval.OneHour, imSport = true)
        dao.setzeSpo2Einstellungen(vorher)

        assertEquals(BlobResponse.BlobStatus.DataStale, dao.vonDerUhr(SATZ, 0, 0, 0, sekunden = 1_000))

        assertEquals(vorher, dao.spo2Einstellungen().first())
    }

    @Test
    fun eineUnbekannteLaengeIstUngueltig() = runTest {
        val dao = dao()

        assertEquals(BlobResponse.BlobStatus.InvalidData, dao.vonDerUhr(SATZ, 1, 0, sekunden = spaeter()))
        assertEquals(BlobResponse.BlobStatus.InvalidData, dao.vonDerUhr(AN, 1, 1, 1, sekunden = spaeter()))

        assertEquals(Spo2Einstellungen(), dao.spo2Einstellungen().first())
    }

    private companion object {
        const val UHR = "uhr"
        const val SATZ = "spo2Preferences"
        const val AN = "bloodOxygenPreferences"
        const val SPORT = "bloodOxygenActivityPreferences"
    }
}
