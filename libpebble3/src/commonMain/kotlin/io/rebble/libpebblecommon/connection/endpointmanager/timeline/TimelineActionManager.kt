package io.rebble.libpebblecommon.connection.endpointmanager.timeline

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.Transport
import io.rebble.libpebblecommon.database.dao.BlobDBDao
import io.rebble.libpebblecommon.di.ConnectionCoroutineScope
import io.rebble.libpebblecommon.packets.blobdb.BlobCommand
import io.rebble.libpebblecommon.packets.blobdb.TimelineIcon
import io.rebble.libpebblecommon.packets.blobdb.TimelineItem
import io.rebble.libpebblecommon.services.blobdb.TimelineActionResult
import io.rebble.libpebblecommon.services.blobdb.TimelineService
import io.rebble.libpebblecommon.util.DataBuffer
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlin.uuid.Uuid

class TimelineActionManager(
    private val watchTransport: Transport,
    private val timelineService: TimelineService,
    private val blobDBDao: BlobDBDao,
    private val notifActionHandler: PlatformNotificationActionHandler,
    private val scope: ConnectionCoroutineScope,
) {
    companion object {
        private val logger = Logger.withTag(TimelineActionManager::class.simpleName!!)
    }
    private val actionHandlerOverrides = mutableMapOf<Uuid, Map<UByte, CustomTimelineActionHandler>>()

    fun init() {
        timelineService.actionInvocations.onEach {
            handleAction(it)
        }.launchIn(scope)
    }

    private suspend fun handleTimelineAction(itemId: Uuid, action: TimelineItem.Action, attributes: List<TimelineItem.Attribute>): TimelineActionResult {
        TODO()
    }

    private suspend fun handleAction(
        invocation: TimelineService.TimelineActionInvocation
    ) {
        val itemId = invocation.itemId
        val actionId = invocation.actionId
        val attributes = invocation.attributes
        val item = blobDBDao.get(itemId.toString(), watchTransport.identifier.asString) ?: run {
            logger.w {
                "Received action for item $itemId, but it doesn't exist in the database"
            }
            return
        }
        val pin = TimelineItem().apply { fromBytes(DataBuffer(item.data.asUByteArray())) }
        val action = pin.actions.list.firstOrNull { it.actionID.get() == actionId } ?: run {
            logger.w {
                "Received action for item $itemId, but it doesn't exist in the pin (action ID $actionId not in ${pin.actions.list.map { it.actionID.get() }})"
            }
            return
        }
        val result = try {
            actionHandlerOverrides[itemId]?.get(actionId)?.let {
                it(attributes)
            } ?: when (item.watchDatabase) {
                BlobCommand.BlobDatabase.Pin -> {
                    handleTimelineAction(itemId, action, attributes)
                }
                BlobCommand.BlobDatabase.Notification -> {
                    notifActionHandler(itemId, action, attributes)
                }
                else -> error(
                    "Received action for item $itemId, but it is not a notification or pin (${item.watchDatabase})"
                )
            }
        } catch (e: Exception) {
            logger.e(e) {
                "Error handling action for item $itemId: ${e.message}"
            }
            TimelineActionResult(
                success = false,
                icon = TimelineIcon.ResultFailed,
                title = "Failed"
            )
        }
        invocation.respond(result)
    }

    fun setActionHandlers(itemId: Uuid, actionHandlers: Map<UByte, CustomTimelineActionHandler>) {
        actionHandlerOverrides[itemId] = actionHandlers
    }
}

typealias CustomTimelineActionHandler = (List<TimelineItem.Attribute>) -> TimelineActionResult