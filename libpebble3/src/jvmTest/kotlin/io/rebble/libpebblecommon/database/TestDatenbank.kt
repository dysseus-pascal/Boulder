package io.rebble.libpebblecommon.database

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlin.coroutines.CoroutineContext

/**
 * Die echte Room-Datenbank im Speicher, mit dem Treiber der App. Abfragen laufen
 * im [kontext] - im Test der Dispatcher von runTest, damit virtuelle Zeit und
 * Datenbank denselben Takt haben.
 */
internal fun testDatenbank(kontext: CoroutineContext): Database =
    Room.inMemoryDatabaseBuilder<Database>()
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(kontext)
        .build()
