package io.rebble.libpebblecommon.database.boulder

import androidx.room.Room
import androidx.room.RoomDatabase
import io.rebble.libpebblecommon.connection.AppContext
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

// Nur damit iosMain vollstaendig ist; Boulder baut kein iOS.
internal actual fun getBoulderDatenbankBuilder(ctx: AppContext): RoomDatabase.Builder<BoulderDatenbank> {
    val ordner = NSFileManager.defaultManager.URLForDirectory(
        directory = NSDocumentDirectory,
        inDomain = NSUserDomainMask,
        appropriateForURL = null,
        create = false,
        error = null,
    )
    return Room.databaseBuilder<BoulderDatenbank>(name = requireNotNull(ordner?.path) + "/$BOULDER_DB_DATEINAME")
}
