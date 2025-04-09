package io.rebble.libpebblecommon.connection.endpointmanager.timeline

import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.packets.blobdb.TimelineItem
import io.rebble.libpebblecommon.services.blobdb.TimelineActionResult
import io.rebble.libpebblecommon.services.blobdb.TimelineService
import kotlin.uuid.Uuid

expect class PlatformNotificationActionHandler(appContext: AppContext) {
    suspend operator fun invoke(
        itemId: Uuid,
        action: TimelineItem.Action,
        attributes: List<TimelineItem.Attribute>
    ): TimelineActionResult
}