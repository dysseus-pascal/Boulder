package coredevices.ring.external.indexwebhook

import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.Instant

/** One webhook delivery attempt, shown in the dialog's "Recent runs" list. */
@Serializable
data class IndexWebhookRun(
    val timestampMs: Long,
    val ok: Boolean,
    val status: String,
    val detail: String,
    val byteSize: Long,
    val durationMs: Long,
) {
    val timestamp: Instant get() = Instant.fromEpochMilliseconds(timestampMs)
}

/**
 * Keeps the last [MAX_RUNS_PER_GESTURE] runs per gesture next to the webhook config. The list is
 * capped and purely diagnostic, so it is stored in settings rather than costing a schema version.
 */
class IndexWebhookRunRepository(private val settings: Settings) {

    companion object {
        const val MAX_RUNS_PER_GESTURE = 20
        private const val RUNS_KEY_PREFIX = "index_webhook_runs_"

        private val json = Json { ignoreUnknownKeys = true }
        private val serializer = ListSerializer(IndexWebhookRun.serializer())
    }

    private val _runs = MutableStateFlow(
        IndexWebhookRecordingTrigger.entries.associateWith { load(it) }
    )

    /** Recording and the test button can finish concurrently; the whole update must be atomic. */
    private val mutex = Mutex()

    fun runs(gesture: IndexWebhookRecordingTrigger): Flow<List<IndexWebhookRun>> =
        _runs.map { it[gesture].orEmpty() }

    suspend fun record(
        gesture: IndexWebhookRecordingTrigger,
        ok: Boolean,
        status: String,
        detail: String,
        byteSize: Long,
        durationMs: Long,
        timestamp: Instant = Clock.System.now(),
    ) {
        val run = IndexWebhookRun(
            timestampMs = timestamp.toEpochMilliseconds(),
            ok = ok,
            status = status,
            detail = detail,
            byteSize = byteSize,
            durationMs = durationMs,
        )
        mutex.withLock {
            val updated = (listOf(run) + _runs.value[gesture].orEmpty())
                .sortedByDescending { it.timestampMs }
                .take(MAX_RUNS_PER_GESTURE)
            settings.putString(runsKey(gesture), json.encodeToString(serializer, updated))
            _runs.value = _runs.value + (gesture to updated)
        }
    }

    suspend fun clear(gesture: IndexWebhookRecordingTrigger) {
        mutex.withLock {
            settings.remove(runsKey(gesture))
            _runs.value = _runs.value + (gesture to emptyList())
        }
    }

    private fun load(gesture: IndexWebhookRecordingTrigger): List<IndexWebhookRun> =
        settings.getStringOrNull(runsKey(gesture))
            ?.let {
                try {
                    json.decodeFromString(serializer, it)
                } catch (_: Exception) {
                    null
                }
            }
            ?: emptyList()

    private fun runsKey(gesture: IndexWebhookRecordingTrigger) = RUNS_KEY_PREFIX + gesture.name
}
