package io.rebble.libpebblecommon.connection.endpointmanager.timeline

import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.packets.blobdb.TimelineItem
import io.rebble.libpebblecommon.services.blobdb.TimelineActionResult
import kotlin.uuid.Uuid

actual class PlatformNotificationActionHandler actual constructor(appContext: AppContext){
    actual suspend operator fun invoke(
        itemId: Uuid,
        action: TimelineItem.Action,
        attributes: List<TimelineItem.Attribute>
    ): TimelineActionResult {
        error("Notification actions are not handled by app on iOS")
    }
}