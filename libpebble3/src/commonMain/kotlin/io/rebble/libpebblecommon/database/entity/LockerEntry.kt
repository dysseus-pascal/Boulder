package io.rebble.libpebblecommon.database.entity

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Relation
import kotlin.uuid.Uuid

@Entity
data class LockerEntry(
    @PrimaryKey val id: Uuid,
    val version: String,
    val title: String,
    val type: String,
    val developerName: String,
    val configurable: Boolean,
    val pbwVersionCode: String,
    val pbwIconResourceId: Int = 0,
    val sideloaded: Boolean = false,
    @Embedded val appstoreData: LockerEntryAppstoreData? = null,
)

data class LockerEntryAppstoreData(
    val hearts: Int,
    val developerId: String,
    val timelineEnabled: Boolean,
    val removeLink: String,
    val shareLink: String,
    val pbwLink: String,
    val userToken: String,
)

data class LockerEntryWithPlatforms(
    @Embedded
    val entry: LockerEntry,
    @Relation(
        parentColumn = "id",
        entityColumn = "lockerEntryId",
    )
    val platforms: List<LockerEntryPlatform>,
)