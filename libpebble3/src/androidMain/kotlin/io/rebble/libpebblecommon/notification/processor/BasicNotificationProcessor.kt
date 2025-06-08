package io.rebble.libpebblecommon.notification.processor

import android.app.Notification
import android.service.notification.StatusBarNotification
import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.database.entity.ChannelItem
import io.rebble.libpebblecommon.database.entity.NotificationAppItem
import io.rebble.libpebblecommon.io.rebble.libpebblecommon.notification.LibPebbleNotification
import io.rebble.libpebblecommon.io.rebble.libpebblecommon.notification.NotificationProcessor
import io.rebble.libpebblecommon.io.rebble.libpebblecommon.notification.NotificationResult
import io.rebble.libpebblecommon.io.rebble.libpebblecommon.notification.isGroupSummary
import io.rebble.libpebblecommon.packets.blobdb.TimelineIcon
import io.rebble.libpebblecommon.util.PrivateLogger
import io.rebble.libpebblecommon.util.obfuscate
import kotlinx.datetime.Instant
import kotlin.uuid.Uuid

class BasicNotificationProcessor(
    private val privateLogger: PrivateLogger,
) : NotificationProcessor {
    private val logger = Logger.withTag("BasicNotificationProcessor")

    override fun processNotification(
        sbn: StatusBarNotification,
        app: NotificationAppItem,
        channel: ChannelItem?,
    ): NotificationResult {
        if (sbn.notification.isGroupSummary()) {
            logger.v { "Ignoring group summary notification for ${sbn.packageName.obfuscate(privateLogger)}" }
            return NotificationResult.Ignored
        }
        //TODO: Implement a more sophisticated notification processor
        val actions = LibPebbleNotification.actionsFromStatusBarNotification(sbn, app, channel)
        val title = sbn.notification.extras.getCharSequence(Notification.EXTRA_TITLE) ?: ""
        val text = sbn.notification.extras.getCharSequence(Notification.EXTRA_TEXT)
        val bigText = sbn.notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
        val showWhen = sbn.notification.extras.getBoolean(Notification.EXTRA_SHOW_WHEN) ?: false
        val body = bigText ?: text ?: ""
        val notification = LibPebbleNotification(
            packageName = sbn.packageName,
            uuid = Uuid.random(),
            groupKey = sbn.groupKey,
            key = sbn.key,
            title = title.toString(),
            body = body.toString(),
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