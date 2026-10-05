package io.rebble.libpebblecommon.database

import androidx.room.AutoMigration
import androidx.room.ConstructedBy
import androidx.room.DeleteTable
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.TypeConverters
import androidx.room.migration.AutoMigrationSpec
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import co.touchlab.kermit.Logger
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.database.dao.CalendarDao
import io.rebble.libpebblecommon.database.dao.ContactDao
import io.rebble.libpebblecommon.database.dao.HealthDao
import io.rebble.libpebblecommon.database.dao.HealthSettingsEntryRealDao
import io.rebble.libpebblecommon.database.dao.KnownWatchDao
import io.rebble.libpebblecommon.database.dao.LockerAppPermissionDao
import io.rebble.libpebblecommon.database.dao.LockerEntryRealDao
import io.rebble.libpebblecommon.database.dao.NotificationAppRealDao
import io.rebble.libpebblecommon.database.dao.NotificationDao
import io.rebble.libpebblecommon.database.dao.NotificationRuleDao
import io.rebble.libpebblecommon.database.dao.Spo2Dao
import io.rebble.libpebblecommon.database.dao.TimelineNotificationRealDao
import io.rebble.libpebblecommon.database.dao.TimelinePinRealDao
import io.rebble.libpebblecommon.database.dao.TimelineReminderRealDao
import io.rebble.libpebblecommon.database.dao.VibePatternDao
import io.rebble.libpebblecommon.database.dao.WatchPrefRealDao
import io.rebble.libpebblecommon.database.dao.WeatherAppRealDao
import io.rebble.libpebblecommon.database.entity.AppPrefsEntryDao
import io.rebble.libpebblecommon.database.entity.AppPrefsEntryEntity
import io.rebble.libpebblecommon.database.entity.AppPrefsEntrySyncEntity
import io.rebble.libpebblecommon.database.entity.CalendarEntity
import io.rebble.libpebblecommon.database.entity.ContactEntity
import io.rebble.libpebblecommon.database.entity.HealthDataEntity
import io.rebble.libpebblecommon.database.entity.HealthSettingsEntryEntity
import io.rebble.libpebblecommon.database.entity.HealthSettingsEntrySyncEntity
import io.rebble.libpebblecommon.database.entity.HealthStatDao
import io.rebble.libpebblecommon.database.entity.HealthStatEntity
import io.rebble.libpebblecommon.database.entity.HealthStatSyncEntity
import io.rebble.libpebblecommon.database.entity.KnownWatchItem
import io.rebble.libpebblecommon.database.entity.LockerAppPermission
import io.rebble.libpebblecommon.database.entity.LockerEntryEntity
import io.rebble.libpebblecommon.database.entity.LockerEntrySyncEntity
import io.rebble.libpebblecommon.database.entity.NotificationAppItemEntity
import io.rebble.libpebblecommon.database.entity.NotificationAppItemSyncEntity
import io.rebble.libpebblecommon.database.entity.NotificationEntity
import io.rebble.libpebblecommon.database.entity.NotificationRuleEntity
import io.rebble.libpebblecommon.database.entity.OverlayDataEntity
import io.rebble.libpebblecommon.database.entity.Spo2ReadingEntity
import io.rebble.libpebblecommon.database.entity.TimelineNotificationEntity
import io.rebble.libpebblecommon.database.entity.TimelineNotificationSyncEntity
import io.rebble.libpebblecommon.database.entity.TimelinePinEntity
import io.rebble.libpebblecommon.database.entity.TimelinePinSyncEntity
import io.rebble.libpebblecommon.database.entity.TimelineReminderEntity
import io.rebble.libpebblecommon.database.entity.TimelineReminderSyncEntity
import io.rebble.libpebblecommon.database.entity.VibePatternEntity
import io.rebble.libpebblecommon.database.entity.WatchPrefItemEntity
import io.rebble.libpebblecommon.database.entity.WatchPrefItemSyncEntity
import io.rebble.libpebblecommon.database.entity.WeatherAppEntryEntity
import io.rebble.libpebblecommon.database.entity.WeatherAppEntrySyncEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.time.Clock
import kotlin.time.Instant

internal const val DATABASE_FILENAME = "libpebble3.db"

/**
 * Boulder: diese Datenbank zaehlt nur Upstream-Schritte, um diesen Versatz
 * verschoben (Upstream 45 = 1045). Boulders eigene Tabellen liegen in
 * BoulderDatenbank (boulder.db), mit eigener Zaehlung.
 */
const val UPSTREAM_VERSATZ = 1000

// Boulder: die Fassung des Schemas, an einer Stelle - sichereNeuereDatenbank vergleicht damit.
internal const val DATENBANK_VERSION = UPSTREAM_VERSATZ + 45

private val logger = Logger.withTag("Database")

@androidx.room.Database(
    entities = [
        KnownWatchItem::class,
        LockerEntryEntity::class,
        LockerEntrySyncEntity::class,
        TimelineNotificationEntity::class,
        TimelineNotificationSyncEntity::class,
        TimelinePinEntity::class,
        TimelinePinSyncEntity::class,
        TimelineReminderEntity::class,
        TimelineReminderSyncEntity::class,
        NotificationAppItemEntity::class,
        NotificationAppItemSyncEntity::class,
        CalendarEntity::class,
        HealthSettingsEntryEntity::class,
        HealthSettingsEntrySyncEntity::class,
        LockerAppPermission::class,
        NotificationEntity::class,
        ContactEntity::class,
        VibePatternEntity::class,
        HealthDataEntity::class,
        OverlayDataEntity::class,
        Spo2ReadingEntity::class,
        HealthStatEntity::class,
        HealthStatSyncEntity::class,
        WatchPrefItemEntity::class,
        WatchPrefItemSyncEntity::class,
        WeatherAppEntryEntity::class,
        WeatherAppEntrySyncEntity::class,
        AppPrefsEntryEntity::class,
        AppPrefsEntrySyncEntity::class,
        NotificationRuleEntity::class,
    ],
    version = DATENBANK_VERSION,
    autoMigrations = [
        AutoMigration(from = 10, to = 11),
        AutoMigration(from = 11, to = 12),
        AutoMigration(from = 12, to = 13),
        AutoMigration(from = 13, to = 14),
        AutoMigration(from = 14, to = 15),
        AutoMigration(from = 15, to = 16),
        AutoMigration(from = 16, to = 17),
        AutoMigration(from = 17, to = 18),
        AutoMigration(from = 18, to = 19),
        AutoMigration(from = 19, to = 20),
        AutoMigration(from = 20, to = 21),
        AutoMigration(from = 21, to = 22),
        AutoMigration(from = 22, to = 23),
        AutoMigration(from = 23, to = 24),
        AutoMigration(from = 24, to = 25),
        AutoMigration(from = 25, to = 26),
        AutoMigration(from = 26, to = 27),
        AutoMigration(from = 27, to = 28),
        AutoMigration(from = 28, to = 29),
        AutoMigration(from = 29, to = 30, spec = AutoMigration30::class),
        AutoMigration(from = 30, to = 31),
        AutoMigration(from = 31, to = 32),
        AutoMigration(from = 32, to = 33),
        AutoMigration(from = 33, to = 34),
        AutoMigration(from = 34, to = 35),
        AutoMigration(from = 35, to = 36),
        AutoMigration(from = 36, to = 37),
        AutoMigration(from = 37, to = 38),
        AutoMigration(from = 38, to = 39),
        // 39 -> 40 is the manual MIGRATION_39_40 below.
        AutoMigration(from = 40, to = 41),
        AutoMigration(from = 41, to = 42),
        AutoMigration(from = 42, to = 43),
        AutoMigration(from = 43, to = 44),
        AutoMigration(from = 44, to = 45),
        // SpO2 (PR #267): spo2_readings table.
        AutoMigration(from = 45, to = 46),
        // Boulder: overlay_data.manual (selbst gestartet oder erkannt)
        AutoMigration(from = 46, to = 47),
        // Boulder: boulder_nacht und boulder_nacht_abschnitt (Tag 88)
        AutoMigration(from = 47, to = 48),
        // Boulder: Akku-Protokoll und Messstufe je Nacht
        AutoMigration(from = 48, to = 49),
        // Boulder: boulder_nacht_hrv, die HRV-Fenster je Nacht (Tag 89)
        AutoMigration(from = 49, to = 50),
        // Boulder: 50 -> 1045 ist MIGRATION_50_1045 unten. Upstream-Schritte ab hier
        // mit UPSTREAM_VERSATZ: aus Upstreams 45 -> 46 wird 1045 -> 1046.
    ],
    exportSchema = true,
)
@ConstructedBy(DatabaseConstructor::class)
@TypeConverters(RoomTypeConverters::class)
abstract class Database : RoomDatabase() {
    abstract fun knownWatchDao(): KnownWatchDao
    abstract fun lockerEntryDao(): LockerEntryRealDao
    abstract fun notificationAppDao(): NotificationAppRealDao
    abstract fun timelineNotificationDao(): TimelineNotificationRealDao
    abstract fun timelinePinDao(): TimelinePinRealDao
    abstract fun calendarDao(): CalendarDao
    abstract fun timelineReminderDao(): TimelineReminderRealDao
    abstract fun healthSettingsDao(): HealthSettingsEntryRealDao
    abstract fun lockerAppPermissionDao(): LockerAppPermissionDao
    abstract fun notificationsDao(): NotificationDao
    abstract fun contactDao(): ContactDao
    abstract fun vibePatternDao(): VibePatternDao
    abstract fun healthDao(): HealthDao
    abstract fun spo2Dao(): Spo2Dao
    abstract fun healthStatDao(): HealthStatDao
    abstract fun watchPrefDao(): WatchPrefRealDao
    abstract fun weatherAppDao(): WeatherAppRealDao
    abstract fun appPrefsDao(): AppPrefsEntryDao
    abstract fun notificationRuleDao(): NotificationRuleDao
}

@DeleteTable(tableName = "WatchSettingsEntity")
@DeleteTable(tableName = "WatchSettingsSyncEntity")
class AutoMigration30 : AutoMigrationSpec


@Suppress("NO_ACTUAL_FOR_EXPECT")
expect object DatabaseConstructor : RoomDatabaseConstructor<Database> {
    override fun initialize(): Database
}

// Force re-sync of all app metadata after fixing SDK version selection
val MIGRATION_39_40 = object : Migration(39, 40) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("UPDATE LockerEntrySyncEntity SET watchSynchHashcode = watchSynchHashcode + 1")
    }
}

/**
 * Boulder: die Naechte ziehen nach boulder.db (NachtUmzug). Ihre Tabellen bleiben
 * in dieser Fassung hier liegen - Room kennt sie nicht mehr und laesst sie in
 * Ruhe -, darum gibt es nichts zu tun. Ziel ist fest Upstream 45: kuenftige
 * Upstream-Schritte setzen hier an.
 */
val MIGRATION_50_1045 = object : Migration(50, UPSTREAM_VERSATZ + 45) {
    override fun migrate(connection: SQLiteConnection) {}
}

fun getRoomDatabase(ctx: AppContext): Database {
    return getDatabaseBuilder(ctx)
        // Boulder: MIGRATION_50_1045
        .addMigrations(MIGRATION_39_40, MIGRATION_50_1045)
        .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
        // V7 required a full re-create.
        .fallbackToDestructiveMigrationFrom(dropAllTables = true, 1, 2, 3, 4, 5, 6, 7, 8, 9)
        // Boulder: der Treiber legt eine neuere Datei beiseite, bevor Room sie leert.
        .setDriver(DowngradeSchutz(BundledSQLiteDriver(), DATENBANK_VERSION, Clock.System))
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
}

internal expect fun getDatabaseBuilder(ctx: AppContext): RoomDatabase.Builder<Database>

/**
 * Boulder: legt eine Datei einer neueren Fassung beiseite, bevor Room sie oeffnet
 * (siehe [sichereNeuereDatenbank]).
 *
 * Das geschieht im ersten open() von Room, nicht beim Bauen der Datenbank: gebaut
 * wird sie in der App auf dem Main-Thread (Application.onCreate, ueber Koin),
 * geoeffnet erst beim ersten Zugriff, auf dem Abfrage-Thread (Dispatchers.IO) und
 * unter Rooms Oeffnungssperre - keine Verbindung von Room ist vorher offen.
 */
internal class DowngradeSchutz(
    private val treiber: SQLiteDriver,
    private val version: Int,
    private val uhr: Clock,
) : SQLiteDriver, SynchronizedObject() {
    // Nur unter der Sperre: alle Verbindungen warten, bis die Datei geprueft ist.
    private var geprueft = false

    override val hasConnectionPool: Boolean get() = treiber.hasConnectionPool

    override fun open(fileName: String): SQLiteConnection {
        synchronized(this) {
            if (!geprueft) {
                // Wirft es, bleibt geprueft false: Room bekommt keine Verbindung und leert nichts.
                sichereNeuereDatenbank(fileName, treiber, version, uhr.now())
                geprueft = true
            }
        }
        return treiber.open(fileName)
    }
}

/**
 * Boulder: stammt [datei] von einer neueren Fassung (user_version groesser als
 * [version]), leerte Room sie beim Oeffnen (fallbackToDestructiveMigrationOnDowngrade).
 * Vorher wird sie umbenannt, die Versionsnummer im Namen: Room findet keine Datei
 * und legt eine neue an, die alte bleibt Byte fuer Byte erhalten - so geht beim
 * Zurueckspielen einer aelteren Fassung nichts still verloren, und eine neuere
 * Fassung kann sie wieder einlesen. Liefert den neuen Pfad.
 *
 * Umbenennen statt Kopieren (VACUUM INTO): es dauert unabhaengig von der Groesse
 * nur Millisekunden. Auf das erste Oeffnen wartet in der App der Main-Thread
 * (WatchManager liest die bekannten Uhren mit runBlocking), eine Kopie der ganzen
 * Datei kaeme dort einem ANR nahe.
 *
 * Laesst sie sich nicht beiseitelegen, wirft es: lieber keine Datenbank als still
 * geloeschte Naechte.
 */
internal fun sichereNeuereDatenbank(datei: String, treiber: SQLiteDriver, version: Int, jetzt: Instant): String? {
    val pfad = Path(datei)
    if (!SystemFileSystem.exists(pfad)) return null
    val gefunden = try {
        treiber.open(datei).use { verbindung ->
            verbindung.prepare("PRAGMA user_version").use { it.step(); it.getLong(0).toInt() }
        }
    } catch (e: Exception) {
        // Nicht lesbar: Room entscheidet wie bisher.
        logger.e(e) { "Reading the database version failed" }
        return null
    }
    if (gefunden <= version) return null
    try {
        // Das WAL in die Datei schreiben: danach liegt meist alles in einer Datei.
        treiber.open(datei).use { it.prepare("PRAGMA wal_checkpoint(TRUNCATE)").use { s -> s.step() } }
    } catch (e: Exception) {
        // Nicht schlimm: die Begleitdateien gehen unten mit.
        logger.w(e) { "Checkpoint of database version $gefunden failed; moving its WAL along" }
    }
    val ordner = pfad.parent ?: Path(".")
    // Der Name der Datei ohne .db, damit libpebble3.db und boulder.db unterscheidbar beiseite liegen.
    val stamm = pfad.name.removeSuffix(".db")
    val ziel = Path(ordner, "$stamm-v$gefunden-${jetzt.toEpochMilliseconds()}.db")
    // Begleitdateien zuerst, die Datenbank zuletzt. Scheitert ein Schritt, gehen die
    // schon verschobenen zurueck: dann liegt alles wie vorher, und open() wirft.
    val verschoben = mutableListOf<Pair<Path, Path>>()
    try {
        for (endung in listOf("-wal", "-journal", "-shm", "")) {
            val von = Path("$datei$endung")
            if (!SystemFileSystem.exists(von)) continue
            val nach = Path("$ziel$endung")
            SystemFileSystem.atomicMove(von, nach)
            verschoben += von to nach
        }
    } catch (e: Exception) {
        logger.e(e) { "Moving database version $gefunden aside failed; not opening it" }
        for ((von, nach) in verschoben.asReversed()) {
            try {
                SystemFileSystem.atomicMove(nach, von)
            } catch (z: Exception) {
                logger.e(z) { "Moving $nach back to $von failed" }
            }
        }
        throw IllegalStateException("Database version $gefunden could not be kept before the downgrade", e)
    }
    logger.w { "Database version $gefunden is newer than $version (downgrade): moved to $ziel, Room starts a new one" }
    return ziel.toString()
}
