package io.rebble.libpebblecommon

import io.rebble.libpebblecommon.database.BlobDbDatabaseManager
import io.rebble.libpebblecommon.database.Database
import io.rebble.libpebblecommon.database.dao.HealthDao
import io.rebble.libpebblecommon.database.entity.NachtAbschnittEntity
import io.rebble.libpebblecommon.database.entity.NachtEntity
import io.rebble.libpebblecommon.database.entity.Spo2ReadingEntity
import io.rebble.libpebblecommon.database.testDatenbank
import io.rebble.libpebblecommon.di.LibPebbleCoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * Das Aufraeumen alle 6 Stunden: Minutendaten und SpO2 verfallen nach 90 Tagen
 * (Entscheidung 04.10.2026), die Naechte bleiben fuer immer. Ein Fehler darf
 * weder die App beenden noch die folgenden Durchgaenge.
 */
class HousekeepingTest {
    private var db: Database? = null

    @AfterTest
    fun zu() {
        db?.close()
    }

    private val jetzt = Instant.fromEpochSeconds(1_790_500_000)

    /** Die echte HealthDao; die ersten [fehler] Aufraeumversuche scheitern. */
    private class WackeligeHealthDao(private val echt: HealthDao, var fehler: Int) : HealthDao by echt {
        var versuche = 0
        override suspend fun deleteExpiredHealthData(expirationTimestamp: Long): Int {
            versuche++
            if (fehler-- > 0) throw IllegalStateException("database is locked")
            return echt.deleteExpiredHealthData(expirationTimestamp)
        }
    }

    private fun kotlinx.coroutines.test.TestScope.housekeeping(healthDao: HealthDao, datenbank: Database) = Housekeeping(
        LibPebbleCoroutineScope(backgroundScope.coroutineContext),
        datenbank.notificationsDao(),
        healthDao,
        NotificationConfigFlow(MutableStateFlow(LibPebbleConfig())),
        object : Clock { override fun now() = jetzt },
        object : BlobDbDatabaseManager { override suspend fun deleteSyncRecordsForStaleDevices() {} },
        datenbank.spo2Dao(),
    )

    @Test
    fun spo2VerfaelltNach90TagenDieNaechteBleiben() = runTest {
        val datenbank = testDatenbank(coroutineContext[CoroutineDispatcher]!!).also { db = it }
        val alt = (jetzt - 91.days).epochSeconds
        val neu = (jetzt - 1.days).epochSeconds
        datenbank.spo2Dao().insertSpo2Readings(listOf(Spo2ReadingEntity(alt, 94, 5), Spo2ReadingEntity(neu, 97, 5)))
        val nacht = NachtEntity((jetzt - 400.days).epochSeconds, 7200, 30, 450, 400, 50, 40)
        datenbank.nachtDao().speichereNachtTeil(nacht, ersterTeil = true, listOf(NachtAbschnittEntity(nacht.beginnUtc, 30, 450, 1)))

        housekeeping(datenbank.healthDao(), datenbank).init()
        runCurrent()

        assertEquals(listOf(neu), datenbank.spo2Dao().getSpo2ReadingsAfter(0).map { it.timestamp })
        assertEquals(listOf(nacht.beginnUtc), datenbank.nachtDao().naechte(0, Long.MAX_VALUE).map { it.beginnUtc })
        assertEquals(1, datenbank.nachtDao().abschnitte(nacht.beginnUtc).size)
    }

    @Test
    fun einFehlerBeendetWederAppNochDieFolgendenDurchgaenge() = runTest {
        val datenbank = testDatenbank(coroutineContext[CoroutineDispatcher]!!).also { db = it }
        val healthDao = WackeligeHealthDao(datenbank.healthDao(), fehler = 1)
        housekeeping(healthDao, datenbank).init()
        runCurrent()
        assertEquals(1, healthDao.versuche)
        // Ein Fehler, der den Scope erreichte, liesse runTest am Ende scheitern.
        testScheduler.advanceTimeBy(6.hours)
        runCurrent()
        assertEquals(2, healthDao.versuche)
    }
}
