@file:OptIn(ExperimentalTime::class)

package io.rebble.libpebblecommon.io.rebble.libpebblecommon.notification

import kotlin.time.ExperimentalTime

sealed class NotificationResult {
    data class Processed(
        val notification: LibPebbleNotification
    ) : NotificationResult()
    data object Ignored : NotificationResult()
    data object NotProcessed : NotificationResult()
}