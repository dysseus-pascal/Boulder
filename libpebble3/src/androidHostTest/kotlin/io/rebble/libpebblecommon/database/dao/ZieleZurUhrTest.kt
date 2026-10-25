package io.rebble.libpebblecommon.database.dao

import io.rebble.libpebblecommon.database.Database
import io.rebble.libpebblecommon.database.testDatenbank
import io.rebble.libpebblecommon.health.BoulderZiele
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Boulder: die Ziele auf dem Weg zur Uhr - die Zeile in der Datenbank Health,
 * wie die Synchronisation (BlobDB) sie abholt. Derselbe Wert noch einmal darf
 * nicht erneut gehen, sonst schickte jeder Start der App ihn wieder.
 */
class ZieleZurUhrTest {
    private var db: Database? = null

    @AfterTest
    fun schliessen() {
        db?.close()
    }

    @Test
    fun neuerWertGehtZurUhrDerselbeNichtNochmal() = runTest {
        val dao = testDatenbank(coroutineContext[CoroutineDispatcher]!!).also { db = it }.healthStatDao()
        suspend fun offen() = dao.dirtyRecordsForWatchInsert(UHR, 0, 0).first()

        dao.insertOrReplace(BoulderZiele.zeile(8_000, 450))
        val zeile = offen().single()
        assertEquals("boulder_ziele", zeile.record.key().toByteArray().decodeToString())
        assertContentEquals(BoulderZiele.satz(8_000, 450), zeile.record.payload)

        dao.markSyncedToWatch(UHR, zeile, zeile.recordHashcode)
        dao.insertOrReplace(BoulderZiele.zeile(8_000, 450))
        assertEquals(emptyList(), offen())

        dao.insertOrReplace(BoulderZiele.zeile(12_000, 450))
        assertContentEquals(BoulderZiele.satz(12_000, 450), offen().single().record.payload)
    }

    private companion object {
        const val UHR = "uhr"
    }
}
