package io.rebble.libpebblecommon.database.dao

import io.rebble.libpebblecommon.database.Database
import io.rebble.libpebblecommon.database.entity.HealthDataEntity
import io.rebble.libpebblecommon.database.testDatenbank
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * DIE SCHRITTE JE HALBE STUNDE fuer das Tagesband, gezaehlt von der echten
 * Room-Abfrage: volle halbe Stunden, nur Fenster mit Schritten, das Ende
 * ausgeschlossen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SchritthalbstundeTest {
    private var db: Database? = null

    @AfterTest
    fun zu() {
        db?.close()
    }

    private fun minute(t: Long, schritte: Int) = HealthDataEntity(t, schritte, 0, 0, 0, 0, 0, 0, 0)

    /** Eine halbe Stunde ab 1'800'000 (durch 1800 teilbar), Minuten darin und daneben. */
    private val beginn = 1_800_000L
    private val minuten = listOf(
        minute(beginn, 10), minute(beginn + 60, 20), minute(beginn + 1740, 5),
        // Die erste Minute der naechsten halben Stunde gehoert nicht mehr dazu.
        minute(beginn + 1800, 7),
        // Eine Minute ohne Schritte macht kein Fenster.
        minute(beginn + 3600, 0),
        minute(beginn + 5400, 3),
    )

    @Test
    fun dieAbfrageZaehltJeHalbeStunde() = runTest {
        val dao = testDatenbank(coroutineContext[CoroutineDispatcher]!!).also { db = it }.healthDao()
        dao.insertHealthData(minuten)
        val fenster = dao.getSchritteJeHalbstunde(beginn, beginn + 5400).map { it.beginn to it.schritte }
        // Das Ende ist ausgeschlossen: die Minute um beginn + 5400 zaehlt nicht.
        assertEquals(listOf(beginn to 35L, beginn + 1800 to 7L), fenster)
        assertEquals(listOf(beginn to 35L, beginn + 1800 to 7L, beginn + 5400 to 3L), dao.getSchritteJeHalbstunde(beginn, beginn + 7200).map { it.beginn to it.schritte })
    }
}
