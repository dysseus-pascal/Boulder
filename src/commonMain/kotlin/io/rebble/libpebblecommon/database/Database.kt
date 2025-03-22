package io.rebble.libpebblecommon.database

import androidx.room.ConstructedBy
import androidx.room.InvalidationTracker
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.TypeConverters
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.database.dao.BlobDBDao
import io.rebble.libpebblecommon.database.entity.BlobDBItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

internal const val DATABASE_FILENAME = "libpebble3.db"

@androidx.room.Database(
    entities = [
        BlobDBItem::class
    ],
    version = 1
)
@ConstructedBy(DatabaseConstructor::class)
@TypeConverters(RoomTypeConverters::class)
abstract class Database : RoomDatabase() {
    abstract fun blobDBDao(): BlobDBDao
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