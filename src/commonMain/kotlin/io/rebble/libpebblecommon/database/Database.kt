package io.rebble.libpebblecommon.database

import androidx.room.AutoMigration
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
import io.rebble.libpebblecommon.database.entity.BlobDBItem
import io.rebble.libpebblecommon.database.entity.KnownWatchItem
import io.rebble.libpebblecommon.database.entity.LockerEntry
import io.rebble.libpebblecommon.database.entity.LockerEntryPlatform
import io.rebble.libpebblecommon.database.entity.LockerSyncStatus
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
    ],
    version = 5,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
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
}

@Suppress("NO_ACTUAL_FOR_EXPECT")
expect object DatabaseConstructor : RoomDatabaseConstructor<Database> {
    override fun initialize(): Database
}

fun getRoomDatabase(ctx: AppContext): Database {
    return getDatabaseBuilder(ctx)
        //.addMigrations()
        .fallbackToDestructiveMigrationOnDowngrade(true)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
}

internal expect fun getDatabaseBuilder(ctx: AppContext): RoomDatabase.Builder<Database>