package io.rebble.libpebblecommon.database

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.rebble.libpebblecommon.connection.AppContext
import kotlin.time.Clock

internal actual fun getDatabaseBuilder(ctx: AppContext): RoomDatabase.Builder<Database> {
    val appContext = ctx.context.applicationContext
    val dbFile = appContext.getDatabasePath(DATABASE_FILENAME)
    // Boulder: vor Room, das eine Datei einer neueren Fassung leeren wuerde.
    sichereNeuereDatenbank(dbFile.absolutePath, BundledSQLiteDriver(), DATENBANK_VERSION, Clock.System.now())
    return Room.databaseBuilder<Database>(
        context = appContext,
        name = dbFile.absolutePath
    )
}