package io.rebble.libpebblecommon.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Entity
data class NotificationAppEntity(
    @PrimaryKey
    val packageName: String,
    val name: String,
    val muteState: MuteState,
    val channelGroups: List<ChannelGroup>,
    /**
     * Last time [muteState] was changed. Used to resolve conflicts with watch on iOS.
     */
    val stateUpdated: Instant,
    val lastNotified: Instant,
)

fun NotificationAppEntity.everNotified(): Boolean = lastNotified > Instant.DISTANT_PAST

@Serializable
enum class MuteState(private val value: UByte) {
    Always(127u),
    Weekends(65u),
    Weekdays(62u),
    Never(0u),
    ;

    companion object {
        fun fromValue(value: UByte): MuteState = entries.firstOrNull { it.value == value } ?: Never
    }
}

@Serializable
data class ChannelGroup(
    val id: String,
    val name: String,
    val channels: List<ChannelItem>,
)

@Serializable
data class ChannelItem(
    val id: String,
    val name: String,
    val muteState: MuteState,
)
