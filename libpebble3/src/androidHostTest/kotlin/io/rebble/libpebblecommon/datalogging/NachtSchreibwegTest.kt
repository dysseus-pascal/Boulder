package io.rebble.libpebblecommon.datalogging

import androidx.room.execSQL
import androidx.room.useWriterConnection
import io.rebble.libpebblecommon.SystemAppIDs.SYSTEM_APP_UUID
import io.rebble.libpebblecommon.database.Database
import io.rebble.libpebblecommon.database.dao.NachtDao
import io.rebble.libpebblecommon.database.entity.NachtAbschnittEntity
import io.rebble.libpebblecommon.database.entity.NachtEntity
import io.rebble.libpebblecommon.database.boulder.BoulderDatenbank
import io.rebble.libpebblecommon.database.testBoulderDatenbank
import io.rebble.libpebblecommon.database.testDatenbank
import io.rebble.libpebblecommon.di.LibPebbleCoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Eine Nacht mit mehr als 60 Abschnitten kommt in Teilen, jeder in einem eigenen
 * Paket. Teil 0 raeumt die Abschnitte der vorigen Fassung weg - laeuft Teil 1
 * daneben, darf er davon nicht getroffen werden.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NachtSchreibwegTest {
    private var db: Database? = null
    private var boulder: BoulderDatenbank? = null

    @AfterTest
    fun zu() {
        db?.close()
        boulder?.close()
    }

    private val beginn = 1_790_445_600L

    private fun nachtSatz(teil: Int, teile: Int, abschnitte: List<Triple<Int, Int, Int>>): ByteArray {
        val b = mutableListOf<Byte>()
        fun u8(v: Int) { b += (v and 0xFF).toByte() }
        fun u16(v: Int) { u8(v); u8(v shr 8) }
        fun u32(v: Long) { for (i in 0 until 4) u8((v shr (8 * i)).toInt()) }
        u16(3); u16(328); u32(beginn); u32(7200)
        u16(0); u16(400); u16(380); u8(50); u8(teil); u8(teile); u16(40)
        u8(abschnitte.size)
        abschnitte.forEach { (von, bis, phase) -> u16(von); u16(bis); u8(phase) }
        repeat(60 - abschnitte.size) { repeat(5) { u8(0) } }
        u8(80); u8(70); u8(0); u8(3)
        return b.toByteArray()
    }

    /**
     * Die echte NachtDao (mit ihrer Transaktion), nur kommt Teil 0 spaet dran - wie
     * hinter einer belegten Schreibsperre: er wartet, bis Teil 1 gespeichert ist
     * (hoechstens 1,5 s). Den Ablauf eines Teils baut sie nicht nach, sie verzoegert ihn nur.
     */
    private class SpaeterErsterTeil(private val echt: NachtDao) : NachtDao by echt {
        val teil1Gespeichert = CompletableDeferred<Unit>()

        override suspend fun speichereNachtTeil(nacht: NachtEntity, ersterTeil: Boolean, abschnitte: List<NachtAbschnittEntity>) {
            if (ersterTeil) withTimeoutOrNull(1500.milliseconds) { teil1Gespeichert.await() }
            echt.speichereNachtTeil(nacht, ersterTeil, abschnitte)
            if (!ersterTeil) teil1Gespeichert.complete(Unit)
        }
    }

    @Test
    fun zweiTeileGleichzeitigVerlierenKeinenAbschnitt() = runTest {
        val datenbank = testDatenbank(coroutineContext[CoroutineDispatcher]!!).also { db = it }
        val naechte = testBoulderDatenbank(coroutineContext[CoroutineDispatcher]!!).also { boulder = it }
        val nachtDao = SpaeterErsterTeil(naechte.nachtDao())
        val verarbeitung = HealthDataProcessor(
            LibPebbleCoroutineScope(backgroundScope.coroutineContext),
            datenbank.healthDao(), datenbank.healthStatDao(), datenbank.spo2Dao(), nachtDao,
            object : Clock { override fun now() = Instant.fromEpochSeconds(1_790_500_000) },
        )
        val teil0 = nachtSatz(0, 2, List(60) { Triple(it * 5, it * 5 + 5, 1) })
        val teil1 = nachtSatz(1, 2, listOf(Triple(300, 400, 2)))
        val a = launch { verarbeitung.speichere(1u, 88u, SYSTEM_APP_UUID, 328u, teil0) }
        val b = launch { verarbeitung.speichere(1u, 88u, SYSTEM_APP_UUID, 328u, teil1) }
        testScheduler.advanceTimeBy(2.seconds)
        a.join(); b.join()
        assertEquals(61, naechte.nachtDao().abschnitte(beginn).size)
    }

    @Test
    fun einTeilWirdGanzOderGarNichtGeschrieben() = runTest {
        val datenbank = testBoulderDatenbank(coroutineContext[CoroutineDispatcher]!!).also { boulder = it }
        val dao = datenbank.nachtDao()
        val nacht = NachtEntity(beginn, 7200, 30, 400, 350, 50, 40)
        dao.speichereNachtTeil(nacht, ersterTeil = true, listOf(NachtAbschnittEntity(beginn, 30, 200, 1)))
        // Der Speicher scheitert beim Schreiben der neuen Abschnitte (hier: ein Trigger statt voller Platte).
        datenbank.useWriterConnection {
            it.execSQL("CREATE TRIGGER kaputt BEFORE INSERT ON boulder_nacht_abschnitt WHEN NEW.phase = 3 BEGIN SELECT RAISE(ABORT, 'disk full'); END")
        }
        assertFails {
            dao.speichereNachtTeil(nacht.copy(aufwachen = 500), ersterTeil = true, listOf(NachtAbschnittEntity(beginn, 30, 500, 3)))
        }
        // Die alte Fassung steht noch ganz da - die Uhr schickt die neue nach der Wartezeit wieder.
        assertEquals(listOf(30 to 200), dao.abschnitte(beginn).map { it.von to it.bis })
        assertEquals(400, dao.juengsteNacht()?.aufwachen)
    }
}
