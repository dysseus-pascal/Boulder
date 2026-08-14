package coredevices.ring.external.indexwebhook

import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Payload mode controls what data is sent to the webhook endpoint.
 */
enum class IndexWebhookPayloadMode(val id: Int) {
    RecordingOnly(0),
    TranscriptionOnly(1),
    Both(2);

    companion object {
        fun fromId(id: Int): IndexWebhookPayloadMode =
            entries.firstOrNull { it.id == id } ?: RecordingOnly
    }
}

/** Webhook configuration for a single recording gesture. */
@Serializable
data class IndexWebhookConfig(
    val url: String? = null,
    val payloadMode: IndexWebhookPayloadMode = IndexWebhookPayloadMode.RecordingOnly,
    val headers: Map<String, String> = emptyMap(),
    val saved: Boolean = false,
) {
    /** A config only sends — and only counts as configured — once saved with a url. */
    val isActive: Boolean get() = saved && !url.isNullOrBlank()
}

/**
 * Stores one [IndexWebhookConfig] per recording gesture.
 */
class IndexWebhookPreferences(private val settings: Settings) {

    companion object {
        private const val CONFIG_KEY_PREFIX = "index_webhook_config_"

        // Legacy single-webhook keys, migrated into per-gesture configs on first load.
        private const val LEGACY_URL_KEY = "index_webhook_url"
        private const val LEGACY_TOKEN_KEY = "index_webhook_auth_token"
        private const val LEGACY_HEADERS_KEY = "index_webhook_headers"
        private const val LEGACY_PAYLOAD_MODE_KEY = "index_webhook_payload_mode"
        private const val LEGACY_TRIGGER_KEY = "index_webhook_trigger"

        // Header name the auth token used to be hardcoded to before headers were user-settable.
        private const val LEGACY_TOKEN_HEADER = "X-Widget-Token"

        // Ids of the old IndexWebhookTrigger enum, which defaulted to DoubleClickHold when unset.
        private const val LEGACY_TRIGGER_SINGLE_CLICK = 0
        private const val LEGACY_TRIGGER_DOUBLE_CLICK_HOLD = 1
        private const val LEGACY_TRIGGER_BOTH = 2

        private val json = Json { ignoreUnknownKeys = true }
        private val legacyHeadersSerializer = MapSerializer(String.serializer(), String.serializer())
    }

    private val _configs = MutableStateFlow(migrateAndLoad())
    val configs = _configs.asStateFlow()

    fun configFor(gesture: IndexWebhookRecordingTrigger): IndexWebhookConfig =
        _configs.value[gesture] ?: IndexWebhookConfig()

    fun setConfig(gesture: IndexWebhookRecordingTrigger, config: IndexWebhookConfig) {
        settings.putString(configKey(gesture), json.encodeToString(config))
        _configs.value = _configs.value + (gesture to config)
    }

    fun clear(gesture: IndexWebhookRecordingTrigger) {
        settings.remove(configKey(gesture))
        _configs.value = _configs.value + (gesture to IndexWebhookConfig())
    }

    /** Toggles sending without losing the stored url/headers, so re-enabling restores them. */
    fun setEnabled(gesture: IndexWebhookRecordingTrigger, enabled: Boolean) {
        setConfig(gesture, configFor(gesture).copy(saved = enabled))
    }

    private fun configKey(gesture: IndexWebhookRecordingTrigger) = CONFIG_KEY_PREFIX + gesture.name

    private fun migrateAndLoad(): Map<IndexWebhookRecordingTrigger, IndexWebhookConfig> {
        migrateLegacySingleConfig()
        return IndexWebhookRecordingTrigger.entries.associateWith { gesture ->
            settings.getStringOrNull(configKey(gesture))
                ?.let {
                    try {
                        json.decodeFromString<IndexWebhookConfig>(it)
                    } catch (_: Exception) {
                        null
                    }
                }
                ?: IndexWebhookConfig()
        }
    }

    /** The single legacy webhook only carries over to the gestures its old trigger fired on. */
    private fun migrateLegacySingleConfig() {
        val url = settings.getStringOrNull(LEGACY_URL_KEY)
        if (url != null) {
            val config = IndexWebhookConfig(
                url = url,
                payloadMode = IndexWebhookPayloadMode.fromId(
                    settings.getInt(LEGACY_PAYLOAD_MODE_KEY, IndexWebhookPayloadMode.RecordingOnly.id)
                ),
                headers = legacyHeaders(),
                saved = true,
            )
            legacyGestures().forEach {
                settings.putString(configKey(it), json.encodeToString(config))
            }
        }
        listOf(
            LEGACY_URL_KEY,
            LEGACY_TOKEN_KEY,
            LEGACY_HEADERS_KEY,
            LEGACY_PAYLOAD_MODE_KEY,
            LEGACY_TRIGGER_KEY,
        ).forEach { settings.remove(it) }
    }

    private fun legacyGestures(): List<IndexWebhookRecordingTrigger> =
        when (settings.getInt(LEGACY_TRIGGER_KEY, LEGACY_TRIGGER_DOUBLE_CLICK_HOLD)) {
            LEGACY_TRIGGER_SINGLE_CLICK -> listOf(IndexWebhookRecordingTrigger.SingleClickHold)
            LEGACY_TRIGGER_BOTH -> IndexWebhookRecordingTrigger.entries
            else -> listOf(IndexWebhookRecordingTrigger.DoubleClickHold)
        }

    private fun legacyHeaders(): Map<String, String> {
        val raw = settings.getStringOrNull(LEGACY_HEADERS_KEY)
        if (raw != null) {
            try {
                return json.decodeFromString(legacyHeadersSerializer, raw)
            } catch (_: Exception) {
                // fall through to the even older single-token key
            }
        }
        val token = settings.getStringOrNull(LEGACY_TOKEN_KEY)?.takeIf { it.isNotBlank() }
        return token?.let { mapOf(LEGACY_TOKEN_HEADER to it) } ?: emptyMap()
    }
}
