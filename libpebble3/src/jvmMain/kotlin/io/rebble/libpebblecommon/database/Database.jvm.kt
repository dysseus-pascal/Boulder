package io.rebble.libpebblecommon.database

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.rebble.libpebblecommon.connection.AppContext
import kotlin.time.Clock
import java.io.File

internal actual fun getDatabaseBuilder(ctx: AppContext): RoomDatabase.Builder<Database> {
    //TODO: This is a temporary solution, we should use a proper path
    val dbFile = File(System.getProperty("java.io.tmpdir"), DATABASE_FILENAME)
    // Boulder: vor Room, das eine Datei einer neueren Fassung leeren wuerde.
    sichereNeuereDatenbank(dbFile.absolutePath, BundledSQLiteDriver(), DATENBANK_VERSION, Clock.System.now())
    return Room.databaseBuilder<Database>(
        name = dbFile.absolutePath,
    )
}