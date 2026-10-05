package io.rebble.libpebblecommon.database.boulder

import androidx.room.Room
import androidx.room.RoomDatabase
import io.rebble.libpebblecommon.connection.AppContext
import java.io.File

// Neben libpebble3.db, wie Database.jvm.kt sie ablegt.
internal actual fun getBoulderDatenbankBuilder(ctx: AppContext): RoomDatabase.Builder<BoulderDatenbank> {
    val datei = File(System.getProperty("java.io.tmpdir"), BOULDER_DB_DATEINAME)
    return Room.databaseBuilder<BoulderDatenbank>(name = datei.absolutePath)
}
