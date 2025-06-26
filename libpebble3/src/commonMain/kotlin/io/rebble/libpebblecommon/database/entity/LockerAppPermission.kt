package io.rebble.libpebblecommon.database.entity

import androidx.room.Entity
import kotlin.uuid.Uuid

@Entity(
    primaryKeys = ["appUuid", "permission"],
)
data class LockerAppPermission(
    val appUuid: Uuid,
    val permission: LockerAppPermissionType,
    val granted: Boolean = true,
)

enum class LockerAppPermissionType {
    Location
}