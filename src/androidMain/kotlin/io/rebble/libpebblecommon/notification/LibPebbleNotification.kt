package io.rebble.libpebblecommon.io.rebble.libpebblecommon.notification

import android.service.notification.StatusBarNotification
import io.rebble.libpebblecommon.packets.blobdb.TimelineIcon
import io.rebble.libpebblecommon.packets.blobdb.TimelineItem
import io.rebble.libpebblecommon.packets.blobdb.buildTimelineItem
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.uuid.Uuid

@OptIn(ExperimentalTime::class)
data class LibPebbleNotification(
    val packageName: String,
    val uuid: Uuid,
    val groupKey: String?,
    val key: String,
    val timestamp: Instant,
    val title: String?,
    val body: String?,
    val icon: TimelineIcon,
    val actions: List<LibPebbleNotificationAction>
) {
    fun displayDataEquals(other: LibPebbleNotification): Boolean {
        return packageName == other.packageName &&
                title == other.title &&
                body == other.body
    }

    companion object {
        fun actionsFromStatusBarNotification(
            sbn: StatusBarNotification
        ): List<LibPebbleNotificationAction> {
            val dismissAction = LibPebbleNotificationAction.dismissActionFromNotification(
                packageName = sbn.packageName,
                notification = sbn.notification
            )
            val contentAction = LibPebbleNotificationAction.contentActionFromNotification(
                packageName = sbn.packageName,
                notification = sbn.notification
            )
            val actions = sbn.notification.actions?.mapNotNull {
                LibPebbleNotificationAction.fromNotificationAction(
                    packageName = sbn.packageName,
                    action = it
                )
            } ?: emptyList()
            return buildList {
                dismissAction?.let { add(it) }
                contentAction?.let { add(it) }
                addAll(actions)
            }
        }
    }

    fun toTimelineItem() = buildTimelineItem(uuid) {
        type = TimelineItem.Type.Notification
        layout = TimelineItem.Layout.GenericNotification
        timestamp = this@LibPebbleNotification.timestamp.epochSeconds.toUInt()

        attributes {
            title?.let {
                title { it }
            }
            body?.let {
                body { it }
            }
            tinyIcon { icon }
        }
        actions {
            actions.forEachIndexed { i, action ->
                action {
                    actionID = i.toUByte()
                    type = action.type.toProtocolType()
                    attributes {
                        title { action.title }
                        action.remoteInput?.suggestedResponses?.let {
                            cannedResponse { it.take(8) }
                        }
                    }
                }
            }
        }
    }
}