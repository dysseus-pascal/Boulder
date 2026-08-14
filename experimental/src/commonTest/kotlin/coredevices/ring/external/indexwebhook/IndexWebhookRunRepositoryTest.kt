package coredevices.ring.external.indexwebhook

import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class IndexWebhookRunRepositoryTest {

    private val settings: Settings = MapSettings()
    private val repository = IndexWebhookRunRepository(settings)

    private suspend fun record(gesture: IndexWebhookRecordingTrigger, index: Int) =
        repository.record(
            gesture = gesture,
            ok = true,
            status = "200 OK",
            detail = "run $index",
            byteSize = index.toLong(),
            durationMs = 10L,
            timestamp = Instant.fromEpochMilliseconds(index * 1000L),
        )

    @Test
    fun retentionKeepsTheMostRecentRunsPerGesture() = runTest {
        val extra = 5
        repeat(IndexWebhookRunRepository.MAX_RUNS_PER_GESTURE + extra) {
            record(IndexWebhookRecordingTrigger.SingleClickHold, it)
        }

        val kept = repository.runs(IndexWebhookRecordingTrigger.SingleClickHold).first()
        assertEquals(IndexWebhookRunRepository.MAX_RUNS_PER_GESTURE, kept.size)
        assertEquals(
            IndexWebhookRunRepository.MAX_RUNS_PER_GESTURE + extra - 1,
            kept.first().byteSize.toInt(),
        )
        assertEquals(extra, kept.last().byteSize.toInt())
    }

    @Test
    fun retentionIsCountedPerGesture() = runTest {
        repeat(IndexWebhookRunRepository.MAX_RUNS_PER_GESTURE + 3) {
            record(IndexWebhookRecordingTrigger.SingleClickHold, it)
        }
        repeat(2) { record(IndexWebhookRecordingTrigger.DoubleClickHold, it) }

        assertEquals(
            IndexWebhookRunRepository.MAX_RUNS_PER_GESTURE,
            repository.runs(IndexWebhookRecordingTrigger.SingleClickHold).first().size,
        )
        assertEquals(
            2,
            repository.runs(IndexWebhookRecordingTrigger.DoubleClickHold).first().size,
        )
    }

    @Test
    fun recordedRunKeepsItsDetail() = runTest {
        repository.record(
            gesture = IndexWebhookRecordingTrigger.DoubleClickHold,
            ok = false,
            status = "500 ERROR",
            detail = "server error",
            byteSize = 42L,
            durationMs = 7L,
        )

        val run = repository.runs(IndexWebhookRecordingTrigger.DoubleClickHold).first().single()
        assertEquals("500 ERROR", run.status)
        assertEquals("server error", run.detail)
        assertEquals(42L, run.byteSize)
        assertEquals(7L, run.durationMs)
    }

    @Test
    fun clearingOneGestureDropsItsStoredRunsAndLeavesTheOther() = runTest {
        repeat(3) { record(IndexWebhookRecordingTrigger.SingleClickHold, it) }
        record(IndexWebhookRecordingTrigger.DoubleClickHold, 0)

        repository.clear(IndexWebhookRecordingTrigger.SingleClickHold)

        assertEquals(
            emptyList(),
            repository.runs(IndexWebhookRecordingTrigger.SingleClickHold).first(),
        )
        assertEquals(
            emptyList(),
            IndexWebhookRunRepository(settings)
                .runs(IndexWebhookRecordingTrigger.SingleClickHold).first(),
        )
        assertEquals(
            1,
            repository.runs(IndexWebhookRecordingTrigger.DoubleClickHold).first().size,
        )
    }

    @Test
    fun runsSurviveANewRepositoryOverTheSameSettings() = runTest {
        repeat(3) { record(IndexWebhookRecordingTrigger.SingleClickHold, it) }

        val reloaded = IndexWebhookRunRepository(settings)
            .runs(IndexWebhookRecordingTrigger.SingleClickHold).first()

        assertEquals(3, reloaded.size)
        assertEquals(2, reloaded.first().byteSize.toInt())
    }
}
