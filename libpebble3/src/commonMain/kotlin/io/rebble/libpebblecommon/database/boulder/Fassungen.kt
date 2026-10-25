package io.rebble.libpebblecommon.database.boulder

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.execSQL
import co.touchlab.kermit.Logger
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Boulder: libpebble3.db zaehlt nur Upstream-Schritte, um diesen Versatz verschoben
 * (Upstream 50 = 1050). Boulders eigene Tabellen liegen in BoulderDatenbank (boulder.db),
 * mit eigener Zaehlung. Aus Cores AutoMigration(from = 50, to = 51) wird
 * from = UPSTREAM_VERSATZ + 50, to = UPSTREAM_VERSATZ + 51.
 */
const val UPSTREAM_VERSATZ = 1000

// Die Fassung des Schemas an einer Stelle: sichereNeuereDatenbank vergleicht damit.
internal const val DATENBANK_VERSION = UPSTREAM_VERSATZ + 50

private val logger = Logger.withTag("BoulderFassungen")

/*
 * Boulders eigene Zaehlung vor 0.50: 45 -> 50, damals AutoMigrations. Ihre Schemas trugen
 * dieselben Nummern wie Cores 46 bis 49 und liegen darum nicht im Schema-Ordner (dort stehen
 * Cores); die Schritte stehen hier als das SQL, das Room fuer sie erzeugt hatte. Die
 * Nacht-Tabellen kennt Room seit 1045 nicht mehr, NachtUmzug liest sie in der Form von 50.
 */
private val BOULDER_45_46 = object : Migration(45, 46) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `spo2_readings` (`timestamp` INTEGER NOT NULL, " +
                "`spo2Percent` INTEGER NOT NULL, `quality` INTEGER NOT NULL, PRIMARY KEY(`timestamp`))"
        )
    }
}

private val BOULDER_46_47 = object : Migration(46, 47) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE `overlay_data` ADD COLUMN `manual` INTEGER NOT NULL DEFAULT 0")
    }
}

private val BOULDER_47_48 = object : Migration(47, 48) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `boulder_nacht` (`beginnUtc` INTEGER NOT NULL, " +
                "`utcZuLokal` INTEGER NOT NULL, `einschlafen` INTEGER NOT NULL, `aufwachen` INTEGER NOT NULL, " +
                "`schlafMinuten` INTEGER NOT NULL, `ruhepuls` INTEGER NOT NULL, `hrv` INTEGER NOT NULL, " +
                "PRIMARY KEY(`beginnUtc`))"
        )
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `boulder_nacht_abschnitt` (`beginnUtc` INTEGER NOT NULL, " +
                "`von` INTEGER NOT NULL, `bis` INTEGER NOT NULL, `phase` INTEGER NOT NULL, " +
                "PRIMARY KEY(`beginnUtc`, `von`))"
        )
    }
}

private val BOULDER_48_49 = object : Migration(48, 49) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE `boulder_nacht` ADD COLUMN `akkuBeginn` INTEGER DEFAULT NULL")
        connection.execSQL("ALTER TABLE `boulder_nacht` ADD COLUMN `akkuEnde` INTEGER DEFAULT NULL")
        connection.execSQL("ALTER TABLE `boulder_nacht` ADD COLUMN `geladen` INTEGER NOT NULL DEFAULT 0")
        connection.execSQL("ALTER TABLE `boulder_nacht` ADD COLUMN `messstufe` INTEGER DEFAULT NULL")
    }
}

private val BOULDER_49_50 = object : Migration(49, 50) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `boulder_nacht_hrv` (`beginnUtc` INTEGER NOT NULL, " +
                "`minute` INTEGER NOT NULL, `rmssd` INTEGER NOT NULL, `puls` INTEGER NOT NULL, " +
                "PRIMARY KEY(`beginnUtc`, `minute`))"
        )
    }
}

/**
 * Die Naechte ziehen nach boulder.db (NachtUmzug). Ihre Tabellen bleiben hier liegen - Room
 * kennt sie nicht mehr und laesst sie in Ruhe -, darum gibt es nichts zu tun. Ziel ist fest
 * Upstream 45: die Upstream-Schritte setzen hier an.
 */
private val BOULDER_50_1045 = object : Migration(50, UPSTREAM_VERSATZ + 45) {
    override fun migrate(connection: SQLiteConnection) {}
}

/** Die Schritte aus Boulders alter Zaehlung, fuer Dateien aus Fassungen vor 0.50. */
val BOULDER_ALTE_MIGRATIONEN: Array<Migration> =
    arrayOf(BOULDER_45_46, BOULDER_46_47, BOULDER_47_48, BOULDER_48_49, BOULDER_49_50, BOULDER_50_1045)


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
 *
 * Die Kopie liegt im Unterordner [BEISEITE_ORDNER] neben der Datei: die Regeln
 * fuer das Cloud-Backup kennen keine Platzhalter (libpebble3-v*.db), einen Ordner
 * schliessen sie aus. Jede Kopie ist eine ganze Datenbank; mitgesichert kippte sie
 * die 25-MB-Grenze, und das System sicherte dann gar nichts mehr von der App.
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
    val ordner = Path(pfad.parent ?: Path("."), BEISEITE_ORDNER)
    // Der Name der Datei ohne .db, damit libpebble3.db und boulder.db unterscheidbar beiseite liegen.
    val stamm = pfad.name.removeSuffix(".db")
    val ziel = Path(ordner, "$stamm-v$gefunden-${jetzt.toEpochMilliseconds()}.db")
    // Begleitdateien zuerst, die Datenbank zuletzt. Scheitert ein Schritt, gehen die
    // schon verschobenen zurueck: dann liegt alles wie vorher, und open() wirft.
    val verschoben = mutableListOf<Pair<Path, Path>>()
    try {
        // Derselbe Datentraeger wie die Datei: das Umbenennen bleibt atomar.
        SystemFileSystem.createDirectories(ordner, mustCreate = false)
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

/**
 * Boulder: Unterordner neben den Datenbanken fuer die beiseitegelegten Fassungen
 * ([sichereNeuereDatenbank]). backup_rules.xml nimmt ihn vom Cloud-Backup aus -
 * wer ihn umbenennt, muss die Regeln mitziehen (BackupRegelnTest prueft es).
 */
const val BEISEITE_ORDNER = "beiseite"
