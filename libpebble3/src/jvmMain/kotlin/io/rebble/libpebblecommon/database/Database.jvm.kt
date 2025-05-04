package io.rebble.libpebblecommon.database

import androidx.room.Room
import androidx.room.RoomDatabase
import io.rebble.libpebblecommon.connection.AppContext
import java.io.File

internal actual fun getDatabaseBuilder(ctx: AppContext): RoomDatabase.Builder<Database> {
    //TODO: This is a temporary solution, we should use a proper path
    val dbFile = File(System.getProperty("java.io.tmpdir"), DATABASE_FILENAME)
    return Room.databaseBuilder<Database>(
        name = dbFile.absolutePath,
    )
}