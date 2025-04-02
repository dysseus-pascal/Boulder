package io.rebble.libpebblecommon.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Ignore
import androidx.room.Index
import androidx.room.PrimaryKey
import io.rebble.libpebblecommon.metadata.WatchType
import kotlin.uuid.Uuid

@Entity(
    foreignKeys = [
        ForeignKey(
            entity = LockerEntry::class,
            parentColumns = ["id"],
            childColumns = ["lockerEntryId"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(
            value = ["lockerEntryId"],
            unique = false
        )
    ]
)
data class LockerEntryPlatform(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val lockerEntryId: Uuid,
    val sdkVersion: String,
    val processInfoFlags: Int,
    val name: String
) {
    @get:Ignore
    val watchType get() = WatchType.entries.first { it.codename == name }
}
