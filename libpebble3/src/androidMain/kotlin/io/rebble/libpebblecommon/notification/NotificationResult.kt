package io.rebble.libpebblecommon.io.rebble.libpebblecommon.notification

sealed class NotificationResult {
    data class Processed(
        val notification: LibPebbleNotification
    ) : NotificationResult()
    data object Ignored : NotificationResult()
    data object NotProcessed : NotificationResult()
}