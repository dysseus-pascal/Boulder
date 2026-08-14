package coredevices.ring.external.indexwebhook

import coredevices.ring.service.ButtonPress
import coredevices.ring.service.recordings.button.RecordingOperationFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IndexWebhookSendDecisionTest {

    private val configured = IndexWebhookConfig(url = "https://example.com/hook", saved = true)

    @Test
    fun aSavedConfigWithAUrlSends() {
        assertTrue(configured.isActive)
    }

    @Test
    fun anUnsavedOrUrllessDraftNeverSends() {
        assertFalse(IndexWebhookConfig().isActive)
        assertFalse(configured.copy(saved = false).isActive)
        assertFalse(configured.copy(url = null).isActive)
        assertFalse(configured.copy(url = "  ").isActive)
    }

    @Test
    fun onlyShortThenLongSendsOnTheDoubleClickHoldConfig() {
        assertEquals(
            IndexWebhookRecordingTrigger.DoubleClickHold,
            RecordingOperationFactory.webhookGestureFor(
                listOf(ButtonPress.Short, ButtonPress.Long)
            ),
        )
        assertEquals(
            IndexWebhookRecordingTrigger.SingleClickHold,
            RecordingOperationFactory.webhookGestureFor(listOf(ButtonPress.Long)),
        )
    }

    @Test
    fun aRecordingWithNoButtonSequenceStillSendsOnHoldAndTalk() {
        assertEquals(
            IndexWebhookRecordingTrigger.SingleClickHold,
            RecordingOperationFactory.webhookGestureFor(null),
        )
    }

    @Test
    fun savingAGestureThatWasSwitchedOffLeavesItOff() {
        assertFalse(
            IndexWebhookSettingsViewModel.enabledAfterSave(configured.copy(saved = false))
        )
        assertTrue(IndexWebhookSettingsViewModel.enabledAfterSave(configured))
    }

    @Test
    fun savingAGestureForTheFirstTimeEnablesIt() {
        assertTrue(IndexWebhookSettingsViewModel.enabledAfterSave(IndexWebhookConfig()))
        assertTrue(
            IndexWebhookSettingsViewModel.enabledAfterSave(
                IndexWebhookConfig(url = "  ", headers = mapOf("X-A" to "1"))
            )
        )
    }
}
