package io.rebble.libpebblecommon.database

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.rebble.libpebblecommon.database.boulder.BoulderDatenbank
import java.io.File
import kotlin.coroutines.CoroutineContext

/**
 * Ein Context nur fuer die Pfade der Datenbanken: [ordner] spielt die Rolle, die bis zum Wegfall
 * des JVM-Ziels java.io.tmpdir hatte. Die Basis fehlt wie in Cores WatchManagerTest
 * (AppContext(ContextWrapper(null))); Room fragt beim Bauen nur nach dem ActivityManager, um das
 * Journal zu waehlen, und nimmt ohne ihn TRUNCATE.
 */
internal class TestKontext(
    private val ordner: File = File(System.getProperty("java.io.tmpdir")),
) : ContextWrapper(null) {
    override fun getApplicationContext(): Context = this
    // Wie Androids ContextImpl: ein absoluter Name bleibt, wie er ist. Room loest den Namen, den die
    // Builder schon absolut uebergeben, noch einmal hierueber auf.
    override fun getDatabasePath(name: String): File = File(name).takeIf { it.isAbsolute } ?: File(ordner, name)
    override fun getSystemService(name: String): Any? = null
}

/**
 * Die echte Room-Datenbank im Speicher, mit dem Treiber der App. Abfragen laufen
 * im [kontext] - im Test der Dispatcher von runTest, damit virtuelle Zeit und
 * Datenbank denselben Takt haben.
 */
internal fun testDatenbank(kontext: CoroutineContext): Database =
    Room.inMemoryDatabaseBuilder<Database>(TestKontext())
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(kontext)
        .build()

/** Boulders Datenbank (die Naechte) im Speicher, wie [testDatenbank]. */
internal fun testBoulderDatenbank(kontext: CoroutineContext): BoulderDatenbank =
    Room.inMemoryDatabaseBuilder<BoulderDatenbank>(TestKontext())
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(kontext)
        .build()
