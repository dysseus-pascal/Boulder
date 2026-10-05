package io.rebble.libpebblecommon.js

import io.rebble.libpebblecommon.WatchConfig
import io.rebble.libpebblecommon.asFlow
import io.rebble.libpebblecommon.database.Database
import io.rebble.libpebblecommon.database.testDatenbank
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

/**
 * Die Timeline-API (timeline-api.rebble.io) im Telefon nachgebildet, mit der
 * echten Pin-Tabelle. Ein DELETE schickt PebbleKit JS ohne Inhalt
 * (xhr.send() ohne Argument): es darf nicht mit 500 enden, der Pin muss weg.
 */
class RemoteTimelineEmulatorTest {
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

    @Test
    fun einDeleteOhneInhaltLoeschtDenPin() = runTest {
        val datenbank = testDatenbank(coroutineContext[CoroutineDispatcher]!!).also { db = it }
        val emulator = RemoteTimelineEmulator(WatchConfig().asFlow(), json, datenbank.timelinePinDao(), datenbank.timelineReminderDao())
        assertEquals(200, emulator.onIntercepted(url, "PUT", pin, app).status)
        assertEquals(listOf("trinken-1"), datenbank.timelinePinDao().getPinsForWatchapp(app).map { it.backingId })

        assertEquals(200, emulator.onIntercepted(url, "DELETE", null, app).status)
        assertEquals(emptyList(), datenbank.timelinePinDao().getPinsForWatchapp(app))
    }

    @Test
    fun einPutOhneInhaltBleibtEinFehler() = runTest {
        val datenbank = testDatenbank(coroutineContext[CoroutineDispatcher]!!).also { db = it }
        val emulator = RemoteTimelineEmulator(WatchConfig().asFlow(), json, datenbank.timelinePinDao(), datenbank.timelineReminderDao())
        assertEquals(500, emulator.onIntercepted(url, "PUT", null, app).status)
        assertEquals(emptyList(), datenbank.timelinePinDao().getPinsForWatchapp(app))
    }
}
