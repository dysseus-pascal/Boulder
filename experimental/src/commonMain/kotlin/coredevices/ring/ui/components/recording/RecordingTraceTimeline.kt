package coredevices.ring.ui.components.recording

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coredevices.ring.data.entity.room.TraceEntryEntity
import coredevices.ring.data.entity.room.TraceEventData
import coredevices.ring.database.room.dao.TraceEntryDao
import coredevices.ring.database.room.dao.TraceSessionDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlin.math.absoluteValue
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

data class TraceMilestone(
    val label: String,
    val relativeMs: Long?,
    val isError: Boolean,
    val detail: String? = null
)

class RecordingTraceTimelineViewModel(
    private val recordingId: Long,
    private val traceEntryDao: TraceEntryDao,
    private val traceSessionDao: TraceSessionDao
): ViewModel() {
    private val _traceMilestones = MutableStateFlow<List<TraceMilestone>?>(null)
    val traceMilestones = _traceMilestones.asStateFlow()

    init {
        viewModelScope.launch {
            loadTraceTimeline()
        }
    }

    private fun extractButtonTimestamps(entries: List<TraceEntryEntity>): Pair<Instant?, Instant?> {
        return entries.firstOrNull { it.type == "transfer_completed" }?.data?.let {
            val data = Json.decodeFromString<TraceEventData>(it)
            if (data is TraceEventData.TransferCompleted) {
                data.buttonReleaseTimestamp?.minus((data.audioDurationSeconds * 1000f).roundToInt().milliseconds) to data.buttonReleaseTimestamp
            } else {
                null to null
            }
        } ?: (null to null)
    }

    private suspend fun loadTraceTimeline() = withContext(Dispatchers.IO) {
        var entries = traceEntryDao.getEntriesForRecording(recordingId).toMutableList()
        val transferId = entries.firstOrNull { it.transferId != null }?.transferId
        transferId?.let {
            entries += traceEntryDao.getEntriesForTransfer(it).filter {
                it.recordingId == null || it.recordingId == recordingId
            }
            entries = entries.distinctBy { it.id }.toMutableList()
            entries.sortBy { e -> e.timeMark }
        }
        val sessionIds = entries.map { it.sessionId }.toSet()
        val sessions = traceSessionDao.getSessionsByIds(sessionIds)
        val firstSessionStart = sessions.minOf { it.started }
        val sessionIdToSession = sessions.associateBy { it.id }
        val (buttonPressTimestamp, buttonReleaseTimestamp) = extractButtonTimestamps(entries)
        val firstSessionToButtonPressOffset = buttonPressTimestamp?.let { buttonTs ->
            buttonTs - firstSessionStart
        }
        val firstEntry = entries.firstOrNull()
        val fallbackOffset = firstEntry?.timeMark
        val transferStart = firstEntry?.timeMark?.let { time ->
            traceEntryDao.getEntryBeforeTimeMarkOfType(
                sessionId = firstEntry.sessionId,
                timeMark = time,
                type = "transfer_started"
            )
        }
        // Get extra implied entries that may not be directly linked to the recording but are relevant for the timeline
        val extraEntries = listOfNotNull(
            traceEntryDao.getEntryBetweenTimeMarksOfType(
                sessionId = firstEntry?.sessionId ?: 0L,
                startTimeMark = firstEntry?.timeMark ?: 0L,
                endTimeMark = entries.lastOrNull()?.timeMark ?: 0L,
                type = "stt_early_init_start"
            ),
            traceEntryDao.getEntryBetweenTimeMarksOfType(
                sessionId = firstEntry?.sessionId ?: 0L,
                startTimeMark = firstEntry?.timeMark ?: 0L,
                endTimeMark = entries.lastOrNull()?.timeMark ?: 0L,
                type = "stt_early_init_success"
            ),
            traceEntryDao.getEntryBetweenTimeMarksOfType(
                sessionId = firstEntry?.sessionId ?: 0L,
                startTimeMark = firstEntry?.timeMark ?: 0L,
                endTimeMark = entries.lastOrNull()?.timeMark ?: 0L,
                type = "stt_early_init_failed"
            ),
            transferStart
        )
        entries.addAll(extraEntries)
        val milestones = entries.map { entry ->
            val label = formatEventType(entry.type)
            val sessionStart = sessionIdToSession[entry.sessionId]?.started!!
            val relativeMsSession = entry.timeMark + (sessionStart - firstSessionStart).inWholeMilliseconds
            val relativeMs = firstSessionToButtonPressOffset?.let { offset ->
                relativeMsSession - offset.inWholeMilliseconds
            } ?: (entry.timeMark - fallbackOffset!!)
            TraceMilestone(
                label = label,
                relativeMs = relativeMs,
                isError = entry.type.contains("failed", ignoreCase = true),
                detail = entry.data?.let { dataStr ->
                    try {
                        val data = Json.decodeFromString<TraceEventData>(dataStr)
                        extractDetail(data)
                    } catch (e: Exception) {
                        null
                    }
                }
            )
        }.toMutableList()
        if (milestones.isNotEmpty()) {
            milestones.add(0, TraceMilestone(
                label = "Button Press",
                relativeMs = if (firstSessionToButtonPressOffset != null) 0 else null,
                isError = false
            ))
            milestones.add(1, TraceMilestone(
                label = "Button Release",
                relativeMs = if (buttonReleaseTimestamp != null && buttonPressTimestamp != null) {
                    (buttonReleaseTimestamp - buttonPressTimestamp).inWholeMilliseconds
                } else null,
                isError = false
            ))
        }
        milestones.sortBy { it.relativeMs ?: Long.MIN_VALUE }
        _traceMilestones.value = milestones
    }

    private fun extractDetail(data: TraceEventData): String? {
        return when (data) {
            is TraceEventData.TransferProgress -> "${(data.reportedProgress * 100).toInt()}%"
            is TraceEventData.TransferCompleted -> "${data.audioDurationSeconds}s audio"
            is TraceEventData.TranscriptionEnd -> data.modelUsed
            is TraceEventData.TranscriptionFail -> data.reason
            is TraceEventData.AgentProcessingStart -> data.agent
            is TraceEventData.AgentProcessingEnd -> data.agent
            is TraceEventData.AgentProcessingFailed -> data.reason
            is TraceEventData.AgentConversationUpdate -> "${data.messageCount} msgs"
            else -> null
        }
    }

    private fun formatEventType(type: String): String {
        return when(type) {
            "transfer_started" -> "Transfer Session Started (Packet received)"
            else -> type.split("_").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
        }
    }
}

@Composable
fun RecordingTraceTimeline(
    recordingId: Long
) {
    val viewModel = koinViewModel<RecordingTraceTimelineViewModel>(key = recordingId.toString()) { parametersOf(recordingId) }
    val milestones by viewModel.traceMilestones.collectAsState()
    Column {
        milestones?.let { milestones ->
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "Trace Timeline",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            if (milestones.isEmpty()) {
                Text(
                    "No trace data found for this recording",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                milestones.forEach { milestone ->
                    TraceTimelineMilestone(milestone)
                }
            }
        }
    }
}

@Composable
private fun TraceTimelineMilestone(milestone: TraceMilestone) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = milestone.relativeMs?.let { formatRelativeTime(it) } ?: "T+?",
            style = MaterialTheme.typography.labelMedium,
            color = if (milestone.isError) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp)
        )
        Text(
            text = milestone.label,
            style = MaterialTheme.typography.bodySmall,
            color = if (milestone.isError) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurface
        )
        milestone.detail?.let {
            Text(
                text = " ($it)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun formatRelativeTime(ms: Long): String {
    if (ms == 0L) return "T+0"
    val totalSeconds = ms / 1000
    val millis = ms % 1000
    val sign = if (ms >= 0) "+" else "-"
    if (totalSeconds == 0L) return "$sign${ms.absoluteValue}ms"
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (minutes != 0L) {
        "$sign${minutes.absoluteValue}m${seconds.absoluteValue}s"
    } else if (millis == 0L) {
        "$sign${seconds.absoluteValue}s"
    } else {
        "$sign${seconds.absoluteValue}.${millis.absoluteValue / 100}s"
    }
}