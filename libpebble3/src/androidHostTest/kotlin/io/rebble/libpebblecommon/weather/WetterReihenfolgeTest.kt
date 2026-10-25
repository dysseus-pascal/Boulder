package io.rebble.libpebblecommon.weather

import androidx.room.execSQL
import androidx.room.useWriterConnection
import io.rebble.libpebblecommon.database.Database
import io.rebble.libpebblecommon.database.dao.WeatherAppRealDao
import io.rebble.libpebblecommon.database.entity.WeatherAppEntry
import io.rebble.libpebblecommon.database.entity.getWeatherSettings
import io.rebble.libpebblecommon.database.testDatenbank
import io.rebble.libpebblecommon.di.LibPebbleCoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Zwei Wetter-Updates kurz nacheinander: am Ende gilt das zweite, auch wenn
 * das erste laenger an der Datenbank haengt.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WetterReihenfolgeTest {
    private var db: Database? = null

    @AfterTest
    fun zu() {
        db?.close()
    }

    /** Die echte Wetter-Tabelle; das erste Ersetzen braucht laenger als das zweite. */
    private class LangsameWetterDao(private val echt: WeatherAppRealDao) : WeatherAppRealDao by echt {
        var aufrufe = 0
        override suspend fun ersetzeOrte(behalten: Set<Uuid>, neue: List<WeatherAppEntry>) {
            delay(if (aufrufe++ == 0) 200.milliseconds else 10.milliseconds)
            echt.ersetzeOrte(behalten, neue)
        }
    }

    private fun ort(key: Uuid, name: String) = WeatherLocationData.WeatherLocationDataPopulated(
        key = key, currentTemp = 12, currentWeatherType = WeatherType.Sun, todayHighTemp = 15, todayLowTemp = 5,
        tomorrowWeatherType = WeatherType.CloudyDay, tomorrowHighTemp = 14, tomorrowLowTemp = 4,
        lastUpdateTimeUtcSecs = 1_790_500_000, isCurrentLocation = false, locationName = name, forecastShort = "",
    )

    @Test
    fun dasZweiteUpdateGewinnt() = runTest {
        val datenbank = testDatenbank(coroutineContext[CoroutineDispatcher]!!).also { db = it }
        val wetter = WeatherManager(
            LangsameWetterDao(datenbank.weatherAppDao()),
            LibPebbleCoroutineScope(backgroundScope.coroutineContext),
            datenbank.appPrefsDao(),
        )
        val bern = Uuid.parse("00000000-0000-0000-0000-00000000000b")
        val chur = Uuid.parse("00000000-0000-0000-0000-00000000000c")
        wetter.updateWeatherData(listOf(ort(bern, "Bern")))
        wetter.updateWeatherData(listOf(ort(chur, "Chur")))
        testScheduler.advanceTimeBy(1.seconds)
        runCurrent()
        assertEquals(listOf("Chur"), datenbank.weatherAppDao().getAll().map { it.locationName })
        assertEquals(listOf(chur), datenbank.appPrefsDao().getWeatherSettings()?.locationUuids)
    }

    @Test
    fun ersetzenGeschiehtGanzOderGarNicht() = runTest {
        val datenbank = testDatenbank(coroutineContext[CoroutineDispatcher]!!).also { db = it }
        val dao = datenbank.weatherAppDao()
        val bern = Uuid.parse("00000000-0000-0000-0000-00000000000b")
        val chur = Uuid.parse("00000000-0000-0000-0000-00000000000c")
        dao.ersetzeOrte(setOf(bern), listOf(ort(bern, "Bern").toWeatherAppEntry()))
        // Das Einfuegen scheitert (hier ein Trigger statt voller Platte).
        datenbank.useWriterConnection {
            it.execSQL("CREATE TRIGGER kaputt BEFORE INSERT ON WeatherAppEntryEntity WHEN NEW.locationName = 'Chur' BEGIN SELECT RAISE(ABORT, 'disk full'); END")
        }
        assertFails { dao.ersetzeOrte(setOf(chur), listOf(ort(chur, "Chur").toWeatherAppEntry())) }
        // Bern ist nicht zum Loeschen markiert - die Uhr behaelt ihren Ort.
        assertEquals(listOf("Bern"), dao.getAll().map { it.locationName })
    }
}
