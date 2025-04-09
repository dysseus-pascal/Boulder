package io.rebble.libpebblecommon.connection.endpointmanager

import io.rebble.libpebblecommon.connection.ConnectedPebble
import io.rebble.libpebblecommon.connection.endpointmanager.blobdb.NotificationBlobDB
import io.rebble.libpebblecommon.connection.endpointmanager.timeline.CustomTimelineActionHandler
import io.rebble.libpebblecommon.connection.endpointmanager.timeline.TimelineActionManager
import io.rebble.libpebblecommon.packets.blobdb.TimelineItem

class NotificationManager(
    private val timelineActionManager: TimelineActionManager,
    private val notificationBlobDB: NotificationBlobDB,
) : ConnectedPebble.Notifications {
    override suspend fun sendNotification(
        notification: TimelineItem,
        actionHandlers: Map<UByte, CustomTimelineActionHandler>
    ) {
        notificationBlobDB.insert(notification)
        timelineActionManager.setActionHandlers(notification.itemId.get(), actionHandlers)
    }
}