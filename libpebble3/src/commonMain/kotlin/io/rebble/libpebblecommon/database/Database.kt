package io.rebble.libpebblecommon.database

import androidx.room.AutoMigration
import androidx.room.ConstructedBy
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.TypeConverters
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.database.dao.CalendarDao
import io.rebble.libpebblecommon.database.dao.KnownWatchDao
import io.rebble.libpebblecommon.database.dao.LockerAppPermissionDao
import io.rebble.libpebblecommon.database.dao.LockerEntryRealDao
import io.rebble.libpebblecommon.database.dao.NotificationAppRealDao
import io.rebble.libpebblecommon.database.dao.TimelinePinRealDao
import io.rebble.libpebblecommon.database.dao.TimelineReminderRealDao
import io.rebble.libpebblecommon.database.entity.CalendarEntity
import io.rebble.libpebblecommon.database.entity.KnownWatchItem
import io.rebble.libpebblecommon.database.entity.LockerAppPermission
import io.rebble.libpebblecommon.database.entity.LockerEntryEntity
import io.rebble.libpebblecommon.database.entity.LockerEntrySyncEntity
import io.rebble.libpebblecommon.database.entity.NotificationAppItemEntity
import io.rebble.libpebblecommon.database.entity.NotificationAppItemSyncEntity
import io.rebble.libpebblecommon.database.entity.TimelineNotificationDao
import io.rebble.libpebblecommon.database.entity.TimelineNotificationEntity
import io.rebble.libpebblecommon.database.entity.TimelineNotificationSyncEntity
import io.rebble.libpebblecommon.database.entity.TimelinePinEntity
import io.rebble.libpebblecommon.database.entity.TimelinePinSyncEntity
import io.rebble.libpebblecommon.database.entity.TimelineReminderEntity
import io.rebble.libpebblecommon.database.entity.TimelineReminderSyncEntity
import io.rebble.libpebblecommon.database.entity.WatchSettingsDao
import io.rebble.libpebblecommon.database.entity.WatchSettingsEntity
import io.rebble.libpebblecommon.database.entity.WatchSettingsSyncEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

internal const val DATABASE_FILENAME = "libpebble3.db"

@androidx.room.Database(
    entities = [
        KnownWatchItem::class,
        LockerEntryEntity::class,
        LockerEntrySyncEntity::class,
        TimelineNotificationEntity::class,
        TimelineNotificationSyncEntity::class,
        TimelinePinEntity::class,
        TimelinePinSyncEntity::class,
        TimelineReminderEntity::class,
        TimelineReminderSyncEntity::class,
        NotificationAppItemEntity::class,
        NotificationAppItemSyncEntity::class,
        CalendarEntity::class,
        WatchSettingsEntity::class,
        WatchSettingsSyncEntity::class,
        LockerAppPermission::class,
    ],
    version = 15,
    autoMigrations = [
        AutoMigration(from = 10, to = 11),
        AutoMigration(from = 11, to = 12),
        AutoMigration(from = 12, to = 13),
        AutoMigration(from = 13, to = 14),
        AutoMigration(from = 14, to = 15),
    ],
    exportSchema = true,
)
@ConstructedBy(DatabaseConstructor::class)
@TypeConverters(RoomTypeConverters::class)
abstract class Database : RoomDatabase() {
    abstract fun knownWatchDao(): KnownWatchDao
    abstract fun lockerEntryDao(): LockerEntryRealDao
    abstract fun notificationAppDao(): NotificationAppRealDao
    abstract fun timelineNotificationDao(): TimelineNotificationDao
    abstract fun timelinePinDao(): TimelinePinRealDao
    abstract fun calendarDao(): CalendarDao
    abstract fun timelineReminderDao(): TimelineReminderRealDao
    abstract fun watchSettingsDao(): WatchSettingsDao
    abstract fun lockerAppPermissionDao(): LockerAppPermissionDao
}

@Suppress("NO_ACTUAL_FOR_EXPECT")
expect object DatabaseConstructor : RoomDatabaseConstructor<Database> {
    override fun initialize(): Database
}

fun getRoomDatabase(ctx: AppContext): Database {
    return getDatabaseBuilder(ctx)
        //.addMigrations()
        .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
        // V7 required a full re-create.
        .fallbackToDestructiveMigrationFrom(dropAllTables = true, 1, 2, 3, 4, 5, 6, 7, 8, 9)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
}

internal expect fun getDatabaseBuilder(ctx: AppContext): RoomDatabase.Builder<Database>