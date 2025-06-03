package io.rebble.libpebblecommon.calls

import kotlinx.datetime.Instant

data class MissedCall(
    val callerNumber: String,
    val callerName: String?,
    val blockedReason: BlockedReason,
    val timestamp: Instant
)
