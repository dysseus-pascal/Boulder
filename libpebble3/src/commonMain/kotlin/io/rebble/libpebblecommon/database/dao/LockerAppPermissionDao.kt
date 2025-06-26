package io.rebble.libpebblecommon.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.rebble.libpebblecommon.database.entity.LockerAppPermission
import io.rebble.libpebblecommon.database.entity.LockerAppPermissionType
import kotlin.uuid.Uuid

@Dao
interface LockerAppPermissionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplace(permission: LockerAppPermission)

    @Query("DELETE FROM LockerAppPermission WHERE appUuid = :appUuid")
    suspend fun deleteByAppUuid(appUuid: Uuid)

    @Query("SELECT * FROM LockerAppPermission WHERE appUuid = :appUuid")
    suspend fun getByAppUuid(appUuid: Uuid): List<LockerAppPermission>

    @Query("SELECT * FROM LockerAppPermission WHERE appUuid = :appUuid AND permission = :permission")
    suspend fun getByAppUuidAndPermission(appUuid: Uuid, permission: LockerAppPermissionType): LockerAppPermission?
}