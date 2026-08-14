package coredevices.ring.external.indexwebhook

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IndexWebhookPreferencesTest {

    private val urlKey = "index_webhook_url"
    private val tokenKey = "index_webhook_auth_token"
    private val headersKey = "index_webhook_headers"
    private val payloadModeKey = "index_webhook_payload_mode"
    private val triggerKey = "index_webhook_trigger"

    private val legacyConfig = IndexWebhookConfig(
        url = "https://example.com/hook",
        payloadMode = IndexWebhookPayloadMode.Both,
        headers = mapOf("Authorization" to "Bearer abc"),
        saved = true,
    )

    private fun legacySettings(trigger: Int?): MapSettings {
        val settings = MapSettings(
            urlKey to "https://example.com/hook",
            headersKey to """{"Authorization":"Bearer abc"}""",
            payloadModeKey to IndexWebhookPayloadMode.Both.id,
        )
        trigger?.let { settings.putInt(triggerKey, it) }
        return settings
    }

    @Test
    fun aLegacyDoubleClickHoldTriggerLeavesHoldAndTalkUnconfigured() {
        val prefs = IndexWebhookPreferences(legacySettings(trigger = 1))

        assertEquals(legacyConfig, prefs.configFor(IndexWebhookRecordingTrigger.DoubleClickHold))
        assertEquals(
            IndexWebhookConfig(),
            prefs.configFor(IndexWebhookRecordingTrigger.SingleClickHold),
        )
    }

    @Test
    fun aLegacySingleClickTriggerLeavesDoubleClickHoldUnconfigured() {
        val prefs = IndexWebhookPreferences(legacySettings(trigger = 0))

        assertEquals(legacyConfig, prefs.configFor(IndexWebhookRecordingTrigger.SingleClickHold))
        assertEquals(
            IndexWebhookConfig(),
            prefs.configFor(IndexWebhookRecordingTrigger.DoubleClickHold),
        )
    }

    @Test
    fun aLegacyBothTriggerMigratesEveryGesture() {
        val prefs = IndexWebhookPreferences(legacySettings(trigger = 2))

        IndexWebhookRecordingTrigger.entries.forEach {
            assertEquals(legacyConfig, prefs.configFor(it))
        }
    }

    @Test
    fun aMissingLegacyTriggerMigratesAsDoubleClickHoldOnly() {
        val prefs = IndexWebhookPreferences(legacySettings(trigger = null))

        assertEquals(legacyConfig, prefs.configFor(IndexWebhookRecordingTrigger.DoubleClickHold))
        assertEquals(
            IndexWebhookConfig(),
            prefs.configFor(IndexWebhookRecordingTrigger.SingleClickHold),
        )
    }

    @Test
    fun legacyKeysAreRemovedAndMigrationRunsOnlyOnce() {
        val settings = MapSettings(
            urlKey to "https://example.com/hook",
            tokenKey to "secret",
            payloadModeKey to IndexWebhookPayloadMode.TranscriptionOnly.id,
            triggerKey to 0,
        )

        IndexWebhookPreferences(settings)

        listOf(urlKey, tokenKey, headersKey, payloadModeKey, triggerKey).forEach {
            assertFalse(settings.hasKey(it), "legacy key $it should be gone")
        }
        val reloaded = IndexWebhookPreferences(settings)
        assertEquals(
            "https://example.com/hook",
            reloaded.configFor(IndexWebhookRecordingTrigger.SingleClickHold).url,
        )
        assertEquals(
            IndexWebhookPayloadMode.TranscriptionOnly,
            reloaded.configFor(IndexWebhookRecordingTrigger.SingleClickHold).payloadMode,
        )
        assertEquals(
            IndexWebhookConfig(),
            reloaded.configFor(IndexWebhookRecordingTrigger.DoubleClickHold),
        )
    }

    @Test
    fun evenOlderAuthTokenBecomesAWidgetTokenHeader() {
        val settings = MapSettings(urlKey to "https://example.com/hook", tokenKey to "secret")

        val prefs = IndexWebhookPreferences(settings)

        assertEquals(
            mapOf("X-Widget-Token" to "secret"),
            prefs.configFor(IndexWebhookRecordingTrigger.DoubleClickHold).headers,
        )
    }

    @Test
    fun blankLegacyTokenIsDiscarded() {
        val settings = MapSettings(urlKey to "https://example.com/hook", tokenKey to "   ")

        val prefs = IndexWebhookPreferences(settings)

        assertEquals(
            emptyMap(),
            prefs.configFor(IndexWebhookRecordingTrigger.DoubleClickHold).headers,
        )
    }

    @Test
    fun noLegacyUrlLeavesEveryGestureUnconfigured() {
        val settings = MapSettings(triggerKey to 2)

        val prefs = IndexWebhookPreferences(settings)

        IndexWebhookRecordingTrigger.entries.forEach {
            assertEquals(IndexWebhookConfig(), prefs.configFor(it))
            assertFalse(prefs.configFor(it).isActive)
        }
    }

    @Test
    fun configsAreStoredPerGestureAndRoundTrip() {
        val settings = MapSettings()
        val hold = IndexWebhookConfig(
            url = "https://hold.example.com",
            payloadMode = IndexWebhookPayloadMode.TranscriptionOnly,
            headers = mapOf("X-A" to "1"),
            saved = true,
        )

        IndexWebhookPreferences(settings)
            .setConfig(IndexWebhookRecordingTrigger.SingleClickHold, hold)

        val reloaded = IndexWebhookPreferences(settings)
        assertEquals(hold, reloaded.configFor(IndexWebhookRecordingTrigger.SingleClickHold))
        assertEquals(
            IndexWebhookConfig(),
            reloaded.configFor(IndexWebhookRecordingTrigger.DoubleClickHold),
        )
    }

    @Test
    fun clearRemovesOnlyThatGesturesConfig() {
        val settings = MapSettings()
        val prefs = IndexWebhookPreferences(settings)
        val config = IndexWebhookConfig(url = "https://example.com", saved = true)
        prefs.setConfig(IndexWebhookRecordingTrigger.SingleClickHold, config)
        prefs.setConfig(IndexWebhookRecordingTrigger.DoubleClickHold, config)

        prefs.clear(IndexWebhookRecordingTrigger.SingleClickHold)

        assertEquals(
            IndexWebhookConfig(),
            prefs.configFor(IndexWebhookRecordingTrigger.SingleClickHold),
        )
        assertEquals(config, prefs.configFor(IndexWebhookRecordingTrigger.DoubleClickHold))
        assertEquals(
            config,
            IndexWebhookPreferences(settings)
                .configFor(IndexWebhookRecordingTrigger.DoubleClickHold),
        )
    }

    @Test
    fun disablingKeepsUrlAndHeadersSoReEnablingRestoresThem() {
        val settings = MapSettings()
        val prefs = IndexWebhookPreferences(settings)
        val gesture = IndexWebhookRecordingTrigger.SingleClickHold
        val config = IndexWebhookConfig(
            url = "https://example.com/hook",
            payloadMode = IndexWebhookPayloadMode.Both,
            headers = mapOf("X-A" to "1"),
            saved = true,
        )
        prefs.setConfig(gesture, config)

        prefs.setEnabled(gesture, false)

        assertFalse(prefs.configFor(gesture).isActive)
        assertEquals(config.copy(saved = false), prefs.configFor(gesture))
        assertEquals(
            config.copy(saved = false),
            IndexWebhookPreferences(settings).configFor(gesture),
        )

        prefs.setEnabled(gesture, true)

        assertTrue(prefs.configFor(gesture).isActive)
        assertEquals(config, prefs.configFor(gesture))
    }

    @Test
    fun settingEnabledWithoutAUrlKeepsHeadersAndStaysInactive() {
        val settings = MapSettings()
        val prefs = IndexWebhookPreferences(settings)
        val gesture = IndexWebhookRecordingTrigger.SingleClickHold
        val config = IndexWebhookConfig(url = "", headers = mapOf("X-A" to "1"), saved = true)
        prefs.setConfig(gesture, config)

        prefs.setEnabled(gesture, true)

        assertFalse(prefs.configFor(gesture).isActive)
        assertEquals(config.headers, prefs.configFor(gesture).headers)
    }

    @Test
    fun corruptStoredConfigFallsBackToUnconfigured() {
        val settings = MapSettings("index_webhook_config_SingleClickHold" to "not json")

        assertEquals(
            IndexWebhookConfig(),
            IndexWebhookPreferences(settings)
                .configFor(IndexWebhookRecordingTrigger.SingleClickHold),
        )
    }
}
