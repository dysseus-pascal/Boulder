package io.rebble.libpebblecommon.database

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import io.rebble.libpebblecommon.connection.AppContext

internal actual fun getDatabaseBuilder(ctx: AppContext): RoomDatabase.Builder<Database> {
    val appContext = ctx.context.applicationContext
    val dbFile = appContext.getDatabasePath(DATABASE_FILENAME)
    return Room.databaseBuilder<Database>(
        context = appContext,
        name = dbFile.absolutePath
    )
}