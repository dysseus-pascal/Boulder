package io.rebble.libpebblecommon.database.dao

import io.rebble.libpebblecommon.database.Database
import io.rebble.libpebblecommon.database.entity.Spo2ReadingEntity
import io.rebble.libpebblecommon.database.testDatenbank
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Boulder: vorher unter androidInstrumentedTest, das das Plugin nicht kennt - der
// Test wurde nie gebaut. Jetzt auf der echten Room-Datenbank im Speicher.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Spo2DaoTest {
    private var db: Database? = null

    @AfterTest
    fun closeDb() {
        db?.close()
    }

    @Test
    fun insertAndRead() = runTest {
        val spo2Dao = testDatenbank(coroutineContext[CoroutineDispatcher]!!).also { db = it }.spo2Dao()
        val reading = Spo2ReadingEntity(
            timestamp = 1600000000L,
            spo2Percent = 97,
            quality = 6,
        )
        spo2Dao.insertSpo2Readings(listOf(reading, reading.copy(timestamp = 1599990000L, spo2Percent = 93)))

        val latest = spo2Dao.getLatestSpo2Reading()
        assertNotNull(latest)
        assertEquals(97, latest.spo2Percent)
        assertEquals(6, latest.quality)
        assertEquals(1600000000L, latest.timestamp)
    }

    @Test
    fun abgelaufeneWerteFallenWeg() = runTest {
        val spo2Dao = testDatenbank(coroutineContext[CoroutineDispatcher]!!).also { db = it }.spo2Dao()
        spo2Dao.insertSpo2Readings(listOf(Spo2ReadingEntity(100, 95, 5), Spo2ReadingEntity(200, 96, 5)))
        assertEquals(1, spo2Dao.deleteExpiredSpo2Data(150))
        assertEquals(listOf(200L), spo2Dao.getSpo2ReadingsAfter(0).map { it.timestamp })
    }
}
