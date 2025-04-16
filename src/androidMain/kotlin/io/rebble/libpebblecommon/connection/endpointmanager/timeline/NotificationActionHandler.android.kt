package io.rebble.libpebblecommon.connection.endpointmanager.timeline

import android.app.ActivityOptions
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Intent
import android.os.Build
import android.os.Bundle
import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.io.rebble.libpebblecommon.notification.LibPebbleNotificationAction
import io.rebble.libpebblecommon.io.rebble.libpebblecommon.notification.LibPebbleNotificationListenerConnection
import io.rebble.libpebblecommon.packets.blobdb.TimelineAttribute
import io.rebble.libpebblecommon.packets.blobdb.TimelineIcon
import io.rebble.libpebblecommon.packets.blobdb.TimelineItem
import io.rebble.libpebblecommon.services.blobdb.TimelineActionResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.uuid.Uuid

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
    private val platformActions = mutableMapOf<Uuid, Map<UByte, LibPebbleNotificationAction>>()

    //TODO: Set from e.g. notification service
    fun setActionHandlers(itemId: Uuid, actionHandlers: Map<UByte, LibPebbleNotificationAction>) {
        platformActions[itemId] = actionHandlers
    }

    private fun makeFillIntent(action: TimelineItem.Action, notificationAction: LibPebbleNotificationAction): Intent? {
        val responseText = action.attributes.list.firstOrNull {
            it.attributeId.get() == TimelineAttribute.Title.id
        }?.content?.get()?.asByteArray()?.decodeToString() ?: run {
            logger.e { "No response text found for action ID ${action.actionID.get()} while handling: $notificationAction" }
            return null
        }
        val replyInput = notificationAction.remoteInput?.remoteInput ?: run {
            logger.e { "No reply input found for action ${action.actionID.get()} while handling: $notificationAction" }
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

    private suspend fun handleReply(pebbleAction: TimelineItem.Action, notificationAction: LibPebbleNotificationAction): TimelineActionResult {
        val fillIntent = makeFillIntent(pebbleAction, notificationAction) ?: return errorResponse()
        val resultCode = notificationAction.pendingIntent?.let { actionIntent(it, fillIntent) } ?: run {
            logger.e { "No pending intent found while handling: $notificationAction as Reply" }
            return errorResponse()
        }
        logger.d { "handleReply() actionIntent result code: $resultCode" }
        return TimelineActionResult(
            success = true,
            icon = TimelineIcon.ResultSent,
            title = "Replied"
        )
    }

    private suspend fun handleGeneric(notificationAction: LibPebbleNotificationAction): TimelineActionResult {
        val resultCode = notificationAction.pendingIntent?.let { actionIntent(it) } ?: run {
            logger.e { "No pending intent found while handling: $notificationAction as Generic" }
            return errorResponse()
        }
        logger.d { "handleGeneric() actionIntent result code: $resultCode" }
        return TimelineActionResult(
            success = true,
            icon = TimelineIcon.GenericConfirmation,
            title = "Complete"
        )
    }

    private suspend fun handleDismiss(itemId: Uuid): TimelineActionResult {
        LibPebbleNotificationListenerConnection.dismissNotification(itemId)
        return TimelineActionResult(
            success = true,
            icon = TimelineIcon.ResultDismissed,
            title = "Dismissed"
        )
    }

    actual suspend operator fun invoke(
        itemId: Uuid,
        action: TimelineItem.Action,
        attributes: List<TimelineItem.Attribute>
    ): TimelineActionResult {
        val actionId = action.actionID.get()
        val notificationAction = LibPebbleNotificationListenerConnection.getNotificationAction(itemId, actionId)
            ?: run {
                logger.e { "No notification found for action ID $actionId while handling: $itemId" }
                return errorResponse()
            }
        logger.d { "Handling notification action on itemId $itemId: ${notificationAction.type}" }
        return when (notificationAction.type) {
            LibPebbleNotificationAction.ActionType.Reply -> handleReply(action, notificationAction)
            LibPebbleNotificationAction.ActionType.Dismiss -> handleDismiss(itemId)
            else -> handleGeneric(notificationAction)
        }
    }

    private suspend fun actionIntent(intent: PendingIntent, fillIntent: Intent? = null): Int {
        val actionContext = LibPebbleNotificationListenerConnection.notificationListenerContext.first()
        return suspendCancellableCoroutine { continuation ->
            val callback = PendingIntent.OnFinished { pendingIntent, intent, resultCode, resultData, resultExtras ->
                continuation.resume(resultCode)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val activityOptions = ActivityOptions.makeBasic().apply {
                    setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                }
                intent.send(actionContext, 0, fillIntent, callback, null, null, activityOptions.toBundle())
            } else {
                intent.send(actionContext, 0, fillIntent, callback, null)
            }
        }
    }
}