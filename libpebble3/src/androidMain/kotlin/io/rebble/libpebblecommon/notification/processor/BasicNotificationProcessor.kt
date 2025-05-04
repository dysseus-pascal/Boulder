package io.rebble.libpebblecommon.notification.processor

import android.app.Notification
import android.service.notification.StatusBarNotification
import io.rebble.libpebblecommon.database.entity.ChannelItem
import io.rebble.libpebblecommon.database.entity.NotificationAppEntity
import io.rebble.libpebblecommon.io.rebble.libpebblecommon.notification.LibPebbleNotification
import io.rebble.libpebblecommon.io.rebble.libpebblecommon.notification.NotificationProcessor
import io.rebble.libpebblecommon.io.rebble.libpebblecommon.notification.NotificationResult
import io.rebble.libpebblecommon.packets.blobdb.TimelineIcon
import kotlin.time.Instant
import kotlin.uuid.Uuid

class BasicNotificationProcessor : NotificationProcessor {
    override fun processNotification(
        sbn: StatusBarNotification,
        app: NotificationAppEntity,
        channel: ChannelItem?,
    ): NotificationResult {
        //TODO: Implement a more sophisticated notification processor
        val actions = LibPebbleNotification.actionsFromStatusBarNotification(sbn, app, channel)
        val title = sbn.notification.extras.getString(Notification.EXTRA_TITLE) ?: ""
        val body = sbn.notification.extras.getString(Notification.EXTRA_TEXT) ?: ""
        val showWhen = sbn.notification.extras.getBoolean(Notification.EXTRA_SHOW_WHEN) ?: false
        val notification = LibPebbleNotification(
            packageName = sbn.packageName,
            uuid = Uuid.random(),
            groupKey = sbn.groupKey,
            key = sbn.key,
            title = title,
            body = body,
            icon = TimelineIcon.NotificationGeneric, //TODO: Get the icon from package/category
            timestamp = if (showWhen) {
                Instant.fromEpochMilliseconds(sbn.notification.`when`)
            } else {
                Instant.fromEpochMilliseconds(sbn.postTime)
            },
            actions = actions,
        )
        return NotificationResult.Processed(notification)
    }
}