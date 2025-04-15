package io.rebble.libpebblecommon.io.rebble.libpebblecommon.notification

import android.app.Notification
import android.app.Notification.Action
import android.app.PendingIntent
import android.app.RemoteInput
import android.os.Build
import io.rebble.libpebblecommon.packets.blobdb.TimelineItem

data class ActionRemoteInput(
    val remoteInput: RemoteInput,
    val suggestedResponses: List<String>? = null,
)

data class LibPebbleNotificationAction(
    val packageName: String,
    val title: String,
    val semanticAction: SemanticAction,
    val pendingIntent: PendingIntent?,
    val remoteInput: ActionRemoteInput?,
    val type: ActionType
) {
    enum class ActionType {
        Generic,
        OpenOnPhone,
        Dismiss,
        Reply;

        fun toProtocolType(): TimelineItem.Action.Type {
            return when (this) {
                Generic -> TimelineItem.Action.Type.Generic
                OpenOnPhone -> TimelineItem.Action.Type.Generic
                Dismiss -> TimelineItem.Action.Type.Generic
                Reply -> TimelineItem.Action.Type.Response
            }
        }
    }
    companion object {
        fun fromNotificationAction(packageName: String, action: Action): LibPebbleNotificationAction? {
            val semanticAction = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                SemanticAction.fromId(action.semanticAction)
            } else {
                SemanticAction.None
            }
            val title = action.title?.toString() ?: return null
            val pendingIntent = action.actionIntent ?: return null
            val input = action.remoteInputs?.firstOrNull {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    !it.isDataOnly
                } else {
                    true
                }
            }
            val suggestedResponses = input?.choices?.map { it.toString() }
            return LibPebbleNotificationAction(
                packageName = packageName,
                title = title,
                semanticAction = semanticAction,
                pendingIntent = pendingIntent,
                remoteInput = input?.let { ActionRemoteInput(input, suggestedResponses) },
                type = if (input != null) {
                    ActionType.Reply
                } else {
                    ActionType.Generic
                }
            )
        }

        fun contentActionFromNotification(packageName: String, notification: Notification): LibPebbleNotificationAction? {
            val pendingIntent = notification.contentIntent ?: return null
            return LibPebbleNotificationAction(
                packageName = packageName,
                title = "Open on phone",
                semanticAction = SemanticAction.None,
                pendingIntent = pendingIntent,
                type = ActionType.OpenOnPhone,
                remoteInput = null,
            )
        }

        fun dismissActionFromNotification(packageName: String, notification: Notification): LibPebbleNotificationAction? {
            return LibPebbleNotificationAction(
                packageName = packageName,
                title = "Dismiss",
                semanticAction = SemanticAction.None,
                pendingIntent = null,
                type = ActionType.Dismiss,
                remoteInput = null,
            )
        }
    }

    enum class SemanticAction(val id: Int) {
        None(0),
        Reply(1),
        MarkAsRead(2),
        MarkAsUnread(3),
        Delete(4),
        Archive(5),
        Mute(6),
        Unmute(7),
        ThumbsUp(8),
        ThumbsDown(9),
        Call(10);

        companion object {
            fun fromId(id: Int): SemanticAction {
                return entries.firstOrNull { it.id == id } ?: None
            }
        }
    }
}