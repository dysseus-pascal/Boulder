package io.rebble.libpebblecommon.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity
data class LockerSyncStatus(
    @PrimaryKey val watchIdentifier: String,
    val lockerDirty: Boolean = true,
)
