package io.rebble.libpebblecommon.database.boulder

import androidx.room.Room
import androidx.room.RoomDatabase
import io.rebble.libpebblecommon.connection.AppContext

internal actual fun getBoulderDatenbankBuilder(ctx: AppContext): RoomDatabase.Builder<BoulderDatenbank> {
    val appContext = ctx.context.applicationContext
    return Room.databaseBuilder<BoulderDatenbank>(
        context = appContext,
        name = appContext.getDatabasePath(BOULDER_DB_DATEINAME).absolutePath,
    )
}
