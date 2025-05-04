package io.rebble.libpebblecommon.io.rebble.libpebblecommon.notification

import android.service.notification.StatusBarNotification
import io.rebble.libpebblecommon.database.entity.ChannelItem
import io.rebble.libpebblecommon.database.entity.NotificationAppEntity

interface NotificationProcessor {
    fun processNotification(
        sbn: StatusBarNotification,
        app: NotificationAppEntity,
        channel: ChannelItem?,
    ): NotificationResult
}