package io.rebble.libpebblecommon.database.boulder

import androidx.room.ConstructedBy
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.database.dao.NachtDao
import io.rebble.libpebblecommon.database.entity.NachtAbschnittEntity
import io.rebble.libpebblecommon.database.entity.NachtEntity
import io.rebble.libpebblecommon.database.entity.NachtHrvEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlin.time.Clock

internal const val BOULDER_DB_DATEINAME = "boulder.db"

// Die Fassung dieser Datenbank, an einer Stelle - der Downgrade-Schutz vergleicht damit.
internal const val BOULDER_DB_VERSION = 1

/**
 * Boulders eigene Datenbank, neben libpebble3.db.
 *
 * EIGENE VERSIONSZAEHLUNG: libpebble3.db zaehlt nur noch Upstream-Schritte
 * (UPSTREAM_VERSATZ), Boulder-Tabellen wachsen hier. So kollidiert kein
 * Upstream-Schritt mehr mit einem eigenen.
 */
@androidx.room.Database(
    entities = [
        NachtEntity::class,
        NachtAbschnittEntity::class,
        NachtHrvEntity::class,
        UmzugEntity::class,
    ],
    version = BOULDER_DB_VERSION,
    exportSchema = true,
)
@ConstructedBy(BoulderDatenbankKonstruktor::class)
abstract class BoulderDatenbank : RoomDatabase() {
    abstract fun nachtDao(): NachtDao
    abstract fun umzugDao(): UmzugDao
}

@Suppress("NO_ACTUAL_FOR_EXPECT")
expect object BoulderDatenbankKonstruktor : RoomDatabaseConstructor<BoulderDatenbank> {
    override fun initialize(): BoulderDatenbank
}

/** Vermerk des einmaligen Umzugs aus libpebble3.db; steht in derselben Transaktion wie die Daten. */
@Entity(tableName = "boulder_umzug")
data class UmzugEntity(
    @PrimaryKey val id: Int = 1,
    val naechte: Long,
    val abschnitte: Long,
    val hrv: Long,
    val zeitMs: Long,
)

/** Nur fuer den Umzug: Einfuegen OHNE Ersetzen, damit jede Kollision auffaellt. */
@Dao
interface UmzugDao {
    @Insert
    suspend fun naechte(naechte: List<NachtEntity>)

    @Insert
    suspend fun abschnitte(abschnitte: List<NachtAbschnittEntity>)

    @Insert
    suspend fun hrv(fenster: List<NachtHrvEntity>)

    @Insert
    suspend fun vermerke(vermerk: UmzugEntity)

    @Query("SELECT * FROM boulder_umzug WHERE id = 1")
    suspend fun vermerk(): UmzugEntity?
}

/**
 * Ohne Rueckfall auf eine leere Datenbank: die Naechte gibt es nur hier. Eine
 * Datei einer neueren Fassung legt der Treiber beiseite, bevor Room sie sieht.
 */
fun getBoulderDatenbank(ctx: AppContext): BoulderDatenbank =
    getBoulderDatenbankBuilder(ctx)
        .setDriver(DowngradeSchutz(BundledSQLiteDriver(), BOULDER_DB_VERSION, Clock.System))
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()

internal expect fun getBoulderDatenbankBuilder(ctx: AppContext): RoomDatabase.Builder<BoulderDatenbank>
