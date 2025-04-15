package io.rebble.libpebblecommon.io.rebble.libpebblecommon.notification

import android.service.notification.StatusBarNotification

interface NotificationProcessor {
    fun processNotification(sbn: StatusBarNotification): NotificationResult
}