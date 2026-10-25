package io.rebble.libpebblecommon.js

import io.rebble.libpebblecommon.WatchConfig
import io.rebble.libpebblecommon.asFlow
import io.rebble.libpebblecommon.connection.PebbleConnectionEvent
import io.rebble.libpebblecommon.connection.PebbleDevice
import io.rebble.libpebblecommon.connection.Watches
import io.rebble.libpebblecommon.database.Database
import io.rebble.libpebblecommon.database.testDatenbank
import io.rebble.libpebblecommon.di.LibPebbleCoroutineScope
import io.rebble.libpebblecommon.permissions.AppPermissions
import io.rebble.libpebblecommon.permissions.LockerInstalledApps
import io.rebble.libpebblecommon.permissions.RunningApps
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.uuid.Uuid
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Boulder: die Timeline-API (timeline-api.rebble.io) im Telefon nachgebildet, mit der echten
 * Pin-Tabelle. Ein DELETE schickt PebbleKit JS ohne Inhalt (xhr.send() ohne Argument): es darf
 * nicht mit 500 enden, der Pin muss weg. (Cores eigener RemoteTimelineEmulatorTest prueft das
 * Umsetzen der Pins.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RemoteTimelineLoeschTest {
    private val app = Uuid.parse("00000000-0000-0000-0000-0000000000ee")
    private val url = "https://timeline-api.rebble.io/v1/user/pins/trinken-1"
    private var db: Database? = null

    @AfterTest
    fun zu() {
        db?.close()
    }

    // Wie in LibPebbleModule.
    private val json = Json { ignoreUnknownKeys = true }

    private val pin = """{"id":"trinken-1","time":"2026-10-05T10:00:00Z","layout":{"type":"genericPin","title":"Drink"}}"""

    private object KeineUhren : Watches {
        override val watches = MutableStateFlow(emptyList<PebbleDevice>())
        override val connectionEvents = emptyFlow<PebbleConnectionEvent>()
        override fun watchesDebugState() = ""
    }

    private fun TestScope.emulator(datenbank: Database) = RemoteTimelineEmulator(
        WatchConfig().asFlow(), json, datenbank.timelinePinDao(), datenbank.timelineReminderDao(),
        AppPermissions(
            datenbank.lockerAppPermissionDao(), LockerInstalledApps(datenbank.lockerEntryDao()), WatchConfig().asFlow(),
            Clock.System, RunningApps(KeineUhren), LibPebbleCoroutineScope(backgroundScope.coroutineContext),
        ),
    )

    @Test
    fun einDeleteOhneInhaltLoeschtDenPin() = runTest {
        val datenbank = testDatenbank(coroutineContext[CoroutineDispatcher]!!).also { db = it }
        val emulator = emulator(datenbank)
        // Direkt eingetragen: das PUT prueft Cores Rechte (mayAddPins), um die es hier nicht geht.
        emulator.insertPin(pin, app)
        assertEquals(listOf("trinken-1"), datenbank.timelinePinDao().getPinsForWatchapp(app).map { it.backingId })

        assertEquals(200, emulator.onIntercepted(url, "DELETE", null, app).status)
        assertEquals(emptyList(), datenbank.timelinePinDao().getPinsForWatchapp(app))
    }

    @Test
    fun einPutOhneInhaltBleibtEinFehler() = runTest {
        val datenbank = testDatenbank(coroutineContext[CoroutineDispatcher]!!).also { db = it }
        assertEquals(500, emulator(datenbank).onIntercepted(url, "PUT", null, app).status)
        assertEquals(emptyList(), datenbank.timelinePinDao().getPinsForWatchapp(app))
    }
}
