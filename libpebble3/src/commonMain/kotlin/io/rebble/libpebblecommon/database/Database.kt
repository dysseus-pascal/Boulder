package io.rebble.libpebblecommon.database

import androidx.room.ConstructedBy
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.TypeConverters
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.database.dao.BlobDBDao
import io.rebble.libpebblecommon.database.dao.KnownWatchDao
import io.rebble.libpebblecommon.database.dao.LockerEntryDao
import io.rebble.libpebblecommon.database.dao.LockerSyncStatusDao
import io.rebble.libpebblecommon.database.dao.NotificationAppDao
import io.rebble.libpebblecommon.database.entity.BlobDBItem
import io.rebble.libpebblecommon.database.entity.KnownWatchItem
import io.rebble.libpebblecommon.database.entity.LockerEntry
import io.rebble.libpebblecommon.database.entity.LockerEntryPlatform
import io.rebble.libpebblecommon.database.entity.LockerSyncStatus
import io.rebble.libpebblecommon.database.entity.NotificationAppEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

internal const val DATABASE_FILENAME = "libpebble3.db"

@androidx.room.Database(
    entities = [
        BlobDBItem::class,
        KnownWatchItem::class,
        LockerEntry::class,
        LockerEntryPlatform::class,
        LockerSyncStatus::class,
        NotificationAppEntity::class,
    ],
    version = 6,
    autoMigrations = [
    ],
    exportSchema = true,
)
@ConstructedBy(DatabaseConstructor::class)
@TypeConverters(RoomTypeConverters::class)
abstract class Database : RoomDatabase() {
    abstract fun blobDBDao(): BlobDBDao
    abstract fun knownWatchDao(): KnownWatchDao
    abstract fun lockerEntryDao(): LockerEntryDao
    abstract fun lockerSyncStatusDao(): LockerSyncStatusDao
    abstract fun notificationAppDao(): NotificationAppDao
}

@Suppress("NO_ACTUAL_FOR_EXPECT")
expect object DatabaseConstructor : RoomDatabaseConstructor<Database> {
    override fun initialize(): Database
}

fun getRoomDatabase(ctx: AppContext): Database {
    return getDatabaseBuilder(ctx)
        //.addMigrations()
        .fallbackToDestructiveMigrationOnDowngrade(true)
        // V6 required a full re-create.
        .fallbackToDestructiveMigrationFrom(true, 1, 2, 3, 4, 5)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
}

internal expect fun getDatabaseBuilder(ctx: AppContext): RoomDatabase.Builder<Database>