package io.rebble.libpebblecommon.notification

import kotlinx.serialization.Serializable

@Serializable
enum class NotificationDecision {
    SendToWatch,
    NotSentLocalOnly,
    NotSentGroupSummary,
    NotSentAppMuted,
    NotSendChannelMuted,
    NotSentDuplicate,
}