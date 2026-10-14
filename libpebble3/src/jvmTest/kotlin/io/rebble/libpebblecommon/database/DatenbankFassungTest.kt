package io.rebble.libpebblecommon.database

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.database.boulder.BEISEITE_ORDNER
import io.rebble.libpebblecommon.database.boulder.BOULDER_DB_VERSION
import io.rebble.libpebblecommon.database.boulder.DATENBANK_VERSION
import io.rebble.libpebblecommon.database.boulder.DowngradeSchutz
import io.rebble.libpebblecommon.database.boulder.UPSTREAM_VERSATZ
import io.rebble.libpebblecommon.database.boulder.NachtUmzug
import io.rebble.libpebblecommon.database.boulder.getBoulderDatenbank
import io.rebble.libpebblecommon.database.entity.NachtAbschnittEntity
import io.rebble.libpebblecommon.database.entity.NachtEntity
import io.rebble.libpebblecommon.database.entity.Spo2ReadingEntity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

/**
 * Die Datenbanken ueber Fassungen hinweg, mit dem echten getRoomDatabase und
 * getBoulderDatenbank (JVM: die Dateien liegen in java.io.tmpdir, hier ein
 * eigener Ordner).
 *
 * D6: eine Datei einer neueren Fassung leert Room beim Oeffnen; vorher muss
 * sie daneben liegen - fuer libpebble3.db wie fuer boulder.db. Migrationen:
 * eine Datenbank im Schema 45..50 (Boulders alte Zaehlung) oder 1045 (0.50 bis
 * 0.53), gebaut aus den exportierten Schemas, kommt mit allen Zeilen auf
 * DATENBANK_VERSION - so wie es MigrationTestHelper.runMigrationsAndValidate
 * prueft (Room vergleicht nach der Migration das Schema mit den Entities und
 * wirft bei einer Abweichung). Die Nacht-Tabellen bleiben dabei liegen.
 */
class DatenbankFassungTest {
    private val ordner = Files.createTempDirectory("libpebble3-db").toFile()
    private val tmpVorher = System.getProperty("java.io.tmpdir")
    private val datei get() = File(ordner, "libpebble3.db")
    private val boulderDatei get() = File(ordner, "boulder.db")
    private val schemas = File("schema/io.rebble.libpebblecommon.database.Database")

    // Boulders alte Zaehlung (46..50) traegt Cores Nummern; ihre Schemas liegen in den Testdaten.
    private val alteSchemas = File("src/jvmTest/resources/boulder-alte-schemas")

    @BeforeTest
    fun vorher() {
        System.setProperty("java.io.tmpdir", ordner.absolutePath)
    }

    @AfterTest
    fun nachher() {
        System.setProperty("java.io.tmpdir", tmpVorher)
        ordner.deleteRecursively()
    }

    private fun <T> roh(pfad: File = datei, block: (SQLiteConnection) -> T): T =
        BundledSQLiteDriver().open(pfad.absolutePath).use(block)

    private fun SQLiteConnection.zahl(sql: String): Long = prepare(sql).use { it.step(); it.getLong(0) }
    private fun SQLiteConnection.text(sql: String): String = prepare(sql).use { it.step(); it.getText(0) }

    private val beginn = 1_790_445_600L

    private fun schreibeSpo2() = runBlocking {
        val db = getRoomDatabase(AppContext())
        db.spo2Dao().insertSpo2Readings(listOf(Spo2ReadingEntity(beginn, 96, 5), Spo2ReadingEntity(beginn + 60, 95, 5)))
        db.close()
    }

    private fun spo2(db: Database) = runBlocking { db.spo2Dao().getSpo2Readings(0, Long.MAX_VALUE) }

    @Test
    fun eineNeuereDatenbankLiegtVorDemLeerenDaneben() {
        schreibeSpo2()
        // Wie nach dem Zurueckspielen einer aelteren Fassung: die Datei ist neuer als das Schema.
        roh { it.execSQL("PRAGMA user_version = ${DATENBANK_VERSION + 1}") }

        val db = getRoomDatabase(AppContext())
        // Room leert weiterhin (fallbackToDestructiveMigrationOnDowngrade) ...
        assertTrue(spo2(db).isEmpty())
        db.close()
        // ... aber vorher kam die ganze Datei daneben, die Fassung im Namen - im Unterordner,
        // den backup_rules.xml vom Cloud-Backup ausnimmt, nicht neben der Datenbank.
        assertTrue(ordner.listFiles()!!.none { it.name.startsWith("libpebble3-v") })
        val kopie = beiseite().single { it.name.startsWith("libpebble3-v${DATENBANK_VERSION + 1}-") && it.name.endsWith(".db") }
        roh(kopie) {
            assertEquals(DATENBANK_VERSION + 1L, it.zahl("PRAGMA user_version"))
            assertEquals(2, it.zahl("SELECT COUNT(*) FROM spo2_readings"))
        }
    }

    @Test
    fun dieEigeneFassungBleibtOhneKopie() {
        schreibeSpo2()
        val db = getRoomDatabase(AppContext())
        assertEquals(2, spo2(db).size)
        db.close()
        assertEquals(listOf("libpebble3.db"), dbDateien())
    }

    @Test
    fun eineFassungAus50IstKeinDowngrade() {
        // Die Schwelle ist 1045, nicht mehr 50: eine Datei aus 0.49 (50) wird migriert, nicht beiseitegelegt.
        legeAn(50)
        val db = getRoomDatabase(AppContext())
        assertEquals(1, spo2(db).size)
        db.close()
        assertEquals(listOf("libpebble3.db"), dbDateien())
    }

    private fun dbDateien() = ordner.listFiles()!!.map { it.name }.filter { it.endsWith(".db") }.sorted()

    /** Was im Ordner der beiseitegelegten Fassungen liegt (leer, wenn es ihn nicht gibt). */
    private fun beiseite(): List<File> = File(ordner, BEISEITE_ORDNER).listFiles()?.toList().orEmpty()

    @Test
    fun beimBauenBleibtDieDateiUnberuehrt() {
        // In der App baut Koin die Datenbank auf dem Main-Thread (Application.onCreate).
        // Beiseitegelegt wird erst beim ersten Oeffnen durch Room, auf dessen Abfrage-Thread.
        schreibeSpo2()
        roh { it.execSQL("PRAGMA user_version = ${DATENBANK_VERSION + 1}") }
        val db = getRoomDatabase(AppContext())
        assertEquals(listOf("libpebble3.db"), dbDateien())
        assertTrue(spo2(db).isEmpty())
        db.close()
        assertEquals(1, beiseite().count { it.name.startsWith("libpebble3-v${DATENBANK_VERSION + 1}-") && it.name.endsWith(".db") })
    }

    @Test
    fun dieNeuereDateiWirdUmbenanntNichtKopiert() {
        // Auf das erste Oeffnen wartet der Main-Thread (WatchManager, runBlocking): eine
        // Kopie dauerte mit der Groesse der Datei, Umbenennen nicht. Gleicher Inode = umbenannt.
        schreibeSpo2()
        roh { it.execSQL("PRAGMA user_version = ${DATENBANK_VERSION + 1}") }
        val inode = Files.getAttribute(datei.toPath(), "unix:ino")
        val db = getRoomDatabase(AppContext())
        spo2(db)
        db.close()
        val kopie = beiseite().single { it.name.startsWith("libpebble3-v${DATENBANK_VERSION + 1}-") && it.name.endsWith(".db") }
        assertEquals(inode, Files.getAttribute(kopie.toPath(), "unix:ino"))
    }

    @Test
    fun laesstSieSichNichtBeiseitelegenBekommtRoomKeineVerbindung() {
        schreibeSpo2()
        roh { it.execSQL("PRAGMA user_version = ${DATENBANK_VERSION + 1}") }
        val jetzt = Instant.fromEpochMilliseconds(1_790_500_000_000)
        // Am Zielnamen liegt ein Ordner: das Umbenennen scheitert.
        File(File(ordner, BEISEITE_ORDNER).apply { mkdirs() }, "libpebble3-v${DATENBANK_VERSION + 1}-${jetzt.toEpochMilliseconds()}.db")
            .apply { mkdir(); File(this, "x").writeText("x") }
        val schutz = DowngradeSchutz(BundledSQLiteDriver(), DATENBANK_VERSION, object : Clock { override fun now() = jetzt })
        // Auch beim zweiten Versuch nicht: sonst leerte Room die Datei.
        assertFails { schutz.open(datei.absolutePath) }
        assertFails { schutz.open(datei.absolutePath) }
        roh {
            assertEquals(DATENBANK_VERSION + 1L, it.zahl("PRAGMA user_version"))
            assertEquals(2, it.zahl("SELECT COUNT(*) FROM spo2_readings"))
        }
    }

    // --- boulder.db ---

    private fun schreibeNacht() = runBlocking {
        val db = getBoulderDatenbank(AppContext())
        db.nachtDao().speichereNachtTeil(
            NachtEntity(beginn, 7200, 30, 450, 400, 50, 40),
            ersterTeil = true,
            listOf(NachtAbschnittEntity(beginn, 30, 200, 1), NachtAbschnittEntity(beginn, 200, 450, 2)),
        )
        db.close()
    }

    @Test
    fun eineNeuereBoulderDatenbankLiegtDanebenUndNichtsGehtVerloren() {
        schreibeNacht()
        roh(boulderDatei) { it.execSQL("PRAGMA user_version = ${BOULDER_DB_VERSION + 1}") }

        // Ohne Schutz wirft Room hier (keine Migration 2 -> 1); mit Schutz beginnt boulder.db neu.
        val db = getBoulderDatenbank(AppContext())
        assertEquals(null, runBlocking { db.nachtDao().juengsteNacht() })
        db.close()
        assertTrue(ordner.listFiles()!!.none { it.name.startsWith("boulder-v") })
        val kopie = beiseite().single { it.name.startsWith("boulder-v${BOULDER_DB_VERSION + 1}-") && it.name.endsWith(".db") }
        roh(kopie) {
            assertEquals(BOULDER_DB_VERSION + 1L, it.zahl("PRAGMA user_version"))
            assertEquals(1, it.zahl("SELECT COUNT(*) FROM boulder_nacht"))
            assertEquals(2, it.zahl("SELECT COUNT(*) FROM boulder_nacht_abschnitt"))
        }
    }

    @Test
    fun dieEigeneBoulderFassungBleibtOhneKopie() {
        schreibeNacht()
        val db = getBoulderDatenbank(AppContext())
        assertEquals(beginn, runBlocking { db.nachtDao().juengsteNacht()?.beginnUtc })
        db.close()
        assertEquals(listOf("boulder.db"), dbDateien())
    }

    // --- Migrationen ---

    private fun schema(fassung: Int): JsonObject =
        Json.parseToJsonElement(File(if (fassung in 46..50) alteSchemas else schemas, "$fassung.json").readText())
            .jsonObject.getValue("database").jsonObject

    private val JsonObject.tabelle get() = getValue("tableName").jsonPrimitive.content

    /** Baut die Datei im Schema [fassung] und legt in jede Tabelle eine Zeile. */
    private fun legeAn(fassung: Int): List<String> {
        val entities = schema(fassung).getValue("entities").jsonArray.map { it.jsonObject }
        roh { c ->
            for (e in entities) {
                c.execSQL(e.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", e.tabelle))
                e["indices"]?.jsonArray?.forEach {
                    c.execSQL(it.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", e.tabelle))
                }
            }
            schema(fassung).getValue("setupQueries").jsonArray.forEach { c.execSQL(it.jsonPrimitive.content) }
            // Erst die Tabellen ohne Fremdschluessel: die Sync-Saetze verweisen auf sie, mit denselben Werten.
            for (e in entities.sortedBy { (it["foreignKeys"]?.jsonArray?.size ?: 0) > 0 }) {
                val felder = e.getValue("fields").jsonArray.map { it.jsonObject }
                val spalten = felder.joinToString { "`${it.getValue("columnName").jsonPrimitive.content}`" }
                val werte = felder.joinToString {
                    when (it.getValue("affinity").jsonPrimitive.content) {
                        "INTEGER" -> "1"
                        "REAL" -> "1.5"
                        "BLOB" -> "X'01'"
                        else -> "'x'"
                    }
                }
                c.execSQL("INSERT INTO `${e.tabelle}` ($spalten) VALUES ($werte)")
            }
            c.execSQL("PRAGMA user_version = $fassung")
        }
        return entities.map { it.tabelle }
    }

    private fun migriereVon(fassung: Int) {
        val tabellen = legeAn(fassung)
        val db = getRoomDatabase(AppContext())
        // Der erste Zugriff oeffnet: Room migriert auf DATENBANK_VERSION und prueft das Schema.
        spo2(db)
        // Danach der Umzug, wie beim Start der App: die Nacht aus der alten Tabelle kommt nach boulder.db.
        val boulder = getBoulderDatenbank(AppContext())
        runBlocking { NachtUmzug(db, boulder).sicherstellen() }
        val umgezogen = runBlocking { boulder.nachtDao().naechte(0, Long.MAX_VALUE) }
        boulder.close()
        db.close()
        val ziel = schema(DATENBANK_VERSION)
        val zielTabellen = ziel.getValue("entities").jsonArray.map { it.jsonObject.tabelle }.toSet()
        roh { c ->
            assertEquals(DATENBANK_VERSION.toLong(), c.zahl("PRAGMA user_version"))
            assertEquals(
                ziel.getValue("identityHash").jsonPrimitive.content,
                c.text("SELECT identity_hash FROM room_master_table WHERE id = 42"),
            )
            for (t in tabellen.filter { it in zielTabellen }) {
                assertEquals(1, c.zahl("SELECT COUNT(*) FROM `$t`"), "Zeile in $t nach Migration $fassung -> $DATENBANK_VERSION")
            }
            // Die Nacht-Tabellen kennt Room seit 1045 nicht mehr; sie bleiben mit ihren Zeilen liegen.
            for (t in tabellen.filter { it.startsWith("boulder_nacht") }) {
                assertEquals(1, c.zahl("SELECT COUNT(*) FROM `$t`"), "$t nach Migration $fassung -> $DATENBANK_VERSION")
            }
            if ("overlay_data" in tabellen && fassung < 47) {
                // 46 -> 47 fuegt manual mit Vorgabe 0 an.
                assertEquals(0, c.zahl("SELECT manual FROM overlay_data"))
            }
        }
        assertEquals(if ("boulder_nacht" in tabellen) listOf(1L) else emptyList(), umgezogen.map { it.beginnUtc })
        assertTrue(ordner.listFiles()!!.none { it.name.startsWith("libpebble3-v") })
        assertTrue(beiseite().isEmpty())
    }

    @Test fun migration45() = migriereVon(45)
    @Test fun migration46() = migriereVon(46)
    @Test fun migration47() = migriereVon(47)
    @Test fun migration48() = migriereVon(48)
    @Test fun migration49() = migriereVon(49)
    @Test fun migration50() = migriereVon(50)

    // Der Weg von 0.53 auf Cores Grundlage: Upstream 45 -> 49 ab Boulders 1045.
    @Test fun migration1045() = migriereVon(UPSTREAM_VERSATZ + 45)
}
