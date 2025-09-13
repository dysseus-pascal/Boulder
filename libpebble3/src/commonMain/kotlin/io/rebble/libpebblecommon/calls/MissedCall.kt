package io.rebble.libpebblecommon.calls

import io.rebble.libpebblecommon.SystemAppIDs.MISSED_CALLS_APP_UUID
import io.rebble.libpebblecommon.database.entity.buildTimelinePin
import io.rebble.libpebblecommon.packets.blobdb.TimelineIcon
import io.rebble.libpebblecommon.packets.blobdb.TimelineItem
import kotlin.time.Instant
import kotlin.time.Duration

data class MissedCall(
    val callerNumber: String,
    val callerName: String?,
    val blockedReason: BlockedReason,
    val timestamp: Instant,
    val duration: Duration
)

fun MissedCall.toTimelinePin() = buildTimelinePin(
    parentId = MISSED_CALLS_APP_UUID,
    timestamp = timestamp
) {
    duration = Duration.ZERO
    layout = TimelineItem.Layout.GenericPin
    attributes {
        tinyIcon { TimelineIcon.TimelineMissedCall }
        title { "Missed Call" }
        subtitle { callerName ?: callerNumber }
        body { "Missed call from $callerNumber" }
    }
}
