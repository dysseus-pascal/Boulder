package io.rebble.libpebblecommon.connection.endpointmanager.timeline

import android.app.ActivityOptions
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Intent
import android.os.Build
import android.os.Bundle
import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.packets.blobdb.TimelineAttribute
import io.rebble.libpebblecommon.packets.blobdb.TimelineIcon
import io.rebble.libpebblecommon.packets.blobdb.TimelineItem
import io.rebble.libpebblecommon.services.blobdb.TimelineActionResult
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.uuid.Uuid

data class AndroidNotificationAction(
    val packageName: String,
    val pendingIntent: PendingIntent,
    val input: RemoteInput?,
)

actual class PlatformNotificationActionHandler actual constructor(private val appContext: AppContext) {
    companion object {
        private val logger = Logger.withTag(PlatformNotificationActionHandler::class.simpleName!!)

        private fun errorResponse(): TimelineActionResult {
            return TimelineActionResult(
                success = false,
                icon = TimelineIcon.ResultFailed,
                title = "Failed"
            )
        }
    }
    private val platformActions = mutableMapOf<Uuid, Map<UByte, AndroidNotificationAction>>()

    //TODO: Set from e.g. notification service
    fun setActionHandlers(itemId: Uuid, actionHandlers: Map<UByte, AndroidNotificationAction>) {
        platformActions[itemId] = actionHandlers
    }

    private fun makeFillIntent(action: TimelineItem.Action, notification: AndroidNotificationAction): Intent? {
        val responseText = action.attributes.list.firstOrNull {
            it.attributeId.get() == TimelineAttribute.Title.id
        }?.content?.get()?.asByteArray()?.decodeToString() ?: run {
            logger.e { "No response text found for action ID ${action.actionID.get()} while handling: $notification" }
            return null
        }
        val replyInput = notification.input ?: run {
            logger.e { "No reply input found for action ${action.actionID.get()} while handling: $notification" }
            return null
        }
        val fillIntent = Intent()
        RemoteInput.addResultsToIntent(
            arrayOf(replyInput),
            fillIntent,
            Bundle().apply {
                putString(replyInput.resultKey, responseText)
            }
        )
        return fillIntent
    }

    actual suspend operator fun invoke(
        itemId: Uuid,
        action: TimelineItem.Action,
        attributes: List<TimelineItem.Attribute>
    ): TimelineActionResult {
        val actionId = action.actionID.get()
        val actionType = TimelineItem.Action.Type.fromValue(action.type.get())
        val notification = platformActions[itemId]?.get(actionId)
            ?: run {
                logger.e { "No notification found for action ID $actionId while handling: $itemId" }
                return errorResponse()
            }
        val isReply = actionType == TimelineItem.Action.Type.Response
        val fillIntent = if (isReply) {
            makeFillIntent(action, notification) ?: return errorResponse()
        } else {
            null
        }
        val resultCode = actionIntent(notification.pendingIntent, fillIntent)
        if (resultCode != 0) {
            logger.e { "Failed to send action intent for item $itemId (PendingIntent result code $resultCode)" }
            return errorResponse()
        } else {
            val icon = if (isReply) {
                TimelineIcon.ResultSent
            } else {
                TimelineIcon.GenericConfirmation
            }
            val text = if (isReply) {
                "Replied"
            } else {
                "Success"
            }
            return TimelineActionResult(
                success = true,
                icon = icon,
                title = text
            )
        }
    }

    private suspend fun actionIntent(intent: PendingIntent, fillIntent: Intent? = null) = suspendCancellableCoroutine { continuation ->
        val callback = PendingIntent.OnFinished { pendingIntent, intent, resultCode, resultData, resultExtras ->
            continuation.resume(resultCode)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val activityOptions = ActivityOptions.makeBasic().apply {
                setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            }
            intent.send(appContext.context, 0, fillIntent, callback, null, null, activityOptions.toBundle())
        } else {
            intent.send(appContext.context, 0, fillIntent, callback, null)
        }
    }
}