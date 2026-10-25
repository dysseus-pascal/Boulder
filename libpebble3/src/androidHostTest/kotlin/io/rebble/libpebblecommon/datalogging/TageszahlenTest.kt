package io.rebble.libpebblecommon.datalogging

import io.rebble.libpebblecommon.SystemAppIDs.SYSTEM_APP_UUID
import io.rebble.libpebblecommon.database.Database
import io.rebble.libpebblecommon.database.entity.HealthDataEntity
import io.rebble.libpebblecommon.database.boulder.BoulderDatenbank
import io.rebble.libpebblecommon.database.testBoulderDatenbank
import io.rebble.libpebblecommon.database.testDatenbank
import io.rebble.libpebblecommon.di.LibPebbleCoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Die Tageszahlen fuer die Uhr (Durchschnitt, Wochenwerte) rechnet ein Schub der
 * Uhr neu. Die Firmware schickt in jedem Paket items_left = 0xffff
 * (dls_endpoint.c); am Ende eines Schubs muss trotzdem gerechnet werden - und am
 * selben Tag wieder, wenn eine Stunde vergangen ist.
 */
class TageszahlenTest {
    private var db: Database? = null
    private var boulder: BoulderDatenbank? = null

    @AfterTest
    fun zu() {
        db?.close()
        boulder?.close()
    }

    private class StellbareUhr(var jetzt: Instant) : Clock {
        override fun now() = jetzt
    }

    /** Ein Minutensatz der Firmware (Fassung 14, 15 Minuten), jede Minute mit [schritte]. */
    private fun schrittItem(beginn: Long, schritte: Int): ByteArray {
        val b = mutableListOf<Byte>()
        fun u8(v: Int) { b += (v and 0xFF).toByte() }
        fun u16(v: Int) { u8(v); u8(v shr 8) }
        fun u32(v: Long) { for (i in 0 until 4) u8((v shr (8 * i)).toInt()) }
        u16(14); u32(beginn); u8(0); u8(18); u8(15)
        repeat(15) {
            u8(schritte); u8(0); u16(100); u8(0); u8(2)
            u16(10); u16(20); u16(300); u8(70); u16(100); u8(1); u8(0); u8(0)
        }
        return b.toByteArray()
    }

    private fun minute(t: Long, schritte: Int) = HealthDataEntity(
        timestamp = t, steps = schritte, orientation = 0, intensity = 0, lightIntensity = 0, activeMinutes = 0,
        restingGramCalories = 0, activeGramCalories = 0, distanceCm = 0,
    )

    @Test
    fun einSchubRechnetDieTageszahlenAuchBeiItemsLeft0xffff() = runTest {
        val start = Instant.fromEpochSeconds(1_790_500_000)
        val uhr = StellbareUhr(start)
        val datenbank = testDatenbank(coroutineContext[CoroutineDispatcher]!!).also { db = it }
        val verarbeitung = HealthDataProcessor(
            LibPebbleCoroutineScope(backgroundScope.coroutineContext),
            datenbank.healthDao(), datenbank.healthStatDao(), datenbank.spo2Dao(),
            testBoulderDatenbank(coroutineContext[CoroutineDispatcher]!!).also { boulder = it }.nachtDao(), uhr,
        )
        runCurrent()
        suspend fun schub(schritte: Int, nach: Duration) {
            uhr.jetzt = start + nach
            val item = schrittItem(start.epochSeconds - 3600 + nach.inWholeSeconds, schritte)
            verarbeitung.speichere(1u, 81u, SYSTEM_APP_UUID, item.size.toUShort(), item)
            // Die Ruhe des Schubsammlers (5 s) abwarten.
            testScheduler.advanceTimeBy(6.seconds)
            runCurrent()
        }
        val stats = datenbank.healthStatDao()
        assertNull(stats.getEntry("average_dailySteps"))

        schub(10, 0.minutes)
        val erste = assertNotNull(stats.getEntry("average_dailySteps")).payload

        // Spaet nachgelieferte Vortagsdaten, dann ein Schub nach 30 Minuten: noch keine neue Rechnung.
        datenbank.healthDao().insertHealthData(listOf(minute(start.epochSeconds - 86_400, 200)))
        schub(10, 30.minutes)
        assertContentEquals(erste, stats.getEntry("average_dailySteps")!!.payload)

        // Nach mehr als einer Stunde am selben Tag: neu gerechnet, die Vortagsdaten zaehlen mit.
        schub(10, 61.minutes)
        assertFalse(erste.contentEquals(stats.getEntry("average_dailySteps")!!.payload))
    }
}
