@file:OptIn(kotlin.time.ExperimentalTime::class)

package coredevices.ring.ui.viewmodel

import coredevices.indexai.data.entity.LocalRecording
import coredevices.indexai.data.entity.RecordingEntryEntity
import coredevices.indexai.data.entity.RecordingEntryStatus
import coredevices.libindex.database.entity.RingTransfer
import coredevices.libindex.database.entity.RingTransferStatus
import coredevices.mcp.data.SemanticResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

class FullFeedViewModelComputeTest {

    private val recording = LocalRecording(id = 1, firestoreId = "rec-1")

    private fun compute(
        semanticResults: Map<Long, SemanticResult?>,
        entries: List<RecordingEntryEntity> = emptyList(),
    ) = FullFeedViewModel.compute(
        recordings = listOf(recording),
        items = emptyList(),
        lists = emptyList(),
        entries = entries,
        query = "",
        semanticResults = semanticResults,
    )

    private fun rowOf(state: FullFeedViewModel.UiState) =
        state.entries.filterIsInstance<FullFeedViewModel.Entry.RecordingRow>().single()

    @Test
    fun failedActionSurfacesItsErrorMessage() {
        val row = rowOf(
            compute(
                mapOf(
                    1L to SemanticResult.GenericFailure(
                        "Failed to create reminder: Notification permission not granted."
                    )
                )
            )
        )
        assertEquals(
            "Failed to create reminder: Notification permission not granted.",
            row.actionError,
        )
    }

    @Test
    fun failureWithoutMessageLeavesNoActionError() {
        assertNull(rowOf(compute(mapOf(1L to SemanticResult.GenericFailure(null)))).actionError)
    }

    @Test
    fun successfulActionLeavesNoActionError() {
        assertNull(rowOf(compute(mapOf(1L to SemanticResult.GenericSuccess))).actionError)
    }

    @Test
    fun pendingTransferAppendsPlaceholderRow() {
        val transfer = RingTransfer(
            id = 7,
            recordingId = null,
            recordingEntryId = null,
            isCurrentIndexIteration = true,
            transferInfo = null,
            status = RingTransferStatus.Started,
        )
        val state = FullFeedViewModel.compute(
            recordings = listOf(recording),
            items = emptyList(),
            lists = emptyList(),
            entries = emptyList(),
            query = "",
            pendingTransfers = listOf(transfer),
        )
        val last = state.entries.last()
        assertTrue(last is FullFeedViewModel.Entry.PendingTransfer)
        assertEquals(7, last.transfer.id)
    }

    @Test
    fun pendingTransferMergesChronologicallyWithRecordings() {
        val now = kotlin.time.Clock.System.now()
        val transfer = RingTransfer(
            id = 7,
            recordingId = null,
            recordingEntryId = null,
            isCurrentIndexIteration = true,
            transferInfo = null,
            status = RingTransferStatus.Started,
            createdAt = now - 1.hours,
        )
        val newerRecording = LocalRecording(id = 2, firestoreId = "rec-2", localTimestamp = now)
        val state = FullFeedViewModel.compute(
            recordings = listOf(newerRecording),
            items = emptyList(),
            lists = emptyList(),
            entries = emptyList(),
            query = "",
            pendingTransfers = listOf(transfer),
        )
        val rows = state.entries.filter { it !is FullFeedViewModel.Entry.DayDivider }
        assertEquals(
            listOf("transfer-7", "rec-2"),
            rows.map { it.uniqueKey },
        )
    }

    @Test
    fun pendingTransfersAreHiddenWhileSearching() {
        val transfer = RingTransfer(
            id = 7,
            recordingId = null,
            recordingEntryId = null,
            isCurrentIndexIteration = true,
            transferInfo = null,
            status = RingTransferStatus.Started,
        )
        val state = FullFeedViewModel.compute(
            recordings = emptyList(),
            items = emptyList(),
            lists = emptyList(),
            entries = emptyList(),
            query = "milk",
            pendingTransfers = listOf(transfer),
        )
        assertTrue(state.entries.isEmpty())
    }

    @Test
    fun retryableTranscriptionFailureHidesStaleActionError() {
        val row = rowOf(
            compute(
                semanticResults = mapOf(1L to SemanticResult.GenericFailure("Stale failure")),
                entries = listOf(
                    RecordingEntryEntity(
                        id = 1,
                        recordingId = 1,
                        status = RecordingEntryStatus.transcription_error,
                    )
                ),
            )
        )
        assertNull(row.actionError)
    }
}
