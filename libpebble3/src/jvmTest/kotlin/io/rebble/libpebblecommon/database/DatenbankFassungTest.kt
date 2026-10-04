package io.rebble.libpebblecommon.database

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.database.entity.NachtAbschnittEntity
import io.rebble.libpebblecommon.database.entity.NachtEntity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Die Datenbank ueber Fassungen hinweg, mit dem echten getRoomDatabase (JVM:
 * die Datei liegt in java.io.tmpdir, hier ein eigener Ordner).
 *
 * D6: eine Datei einer neueren Fassung leert Room beim Oeffnen; vorher muss
 * eine Kopie daneben liegen. Migrationen: eine Datenbank im Schema 45..49,
 * gebaut aus den exportierten Schemas, kommt mit allen Zeilen auf 50 - so wie
 * es MigrationTestHelper.runMigrationsAndValidate prueft (Room vergleicht nach
 * der Migration das Schema mit den Entities und wirft bei einer Abweichung).
 */
class DatenbankFassungTest {
    private val ordner = Files.createTempDirectory("libpebble3-db").toFile()
    private val tmpVorher = System.getProperty("java.io.tmpdir")
    private val datei get() = File(ordner, "libpebble3.db")
    private val schemas = File("schema/io.rebble.libpebblecommon.database.Database")

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

    private fun schreibeNacht() = runBlocking {
        val db = getRoomDatabase(AppContext())
        db.nachtDao().speichereNachtTeil(
            NachtEntity(beginn, 7200, 30, 450, 400, 50, 40),
            ersterTeil = true,
            listOf(NachtAbschnittEntity(beginn, 30, 200, 1), NachtAbschnittEntity(beginn, 200, 450, 2)),
        )
        db.close()
    }

    @Test
    fun eineNeuereDatenbankLiegtVorDemLeerenDaneben() {
        schreibeNacht()
        // Wie nach dem Zurueckspielen einer aelteren Fassung: die Datei ist neuer als das Schema.
        roh { it.execSQL("PRAGMA user_version = ${DATENBANK_VERSION + 1}") }

        val db = getRoomDatabase(AppContext())
        // Room leert weiterhin (fallbackToDestructiveMigrationOnDowngrade) ...
        assertNull(runBlocking { db.nachtDao().juengsteNacht() })
        db.close()
        // ... aber vorher kam die ganze Datei daneben, die Fassung im Namen.
        val kopie = ordner.listFiles()!!.single { it.name.startsWith("libpebble3-v${DATENBANK_VERSION + 1}-") }
        roh(kopie) {
            assertEquals(DATENBANK_VERSION + 1L, it.zahl("PRAGMA user_version"))
            assertEquals(1, it.zahl("SELECT COUNT(*) FROM boulder_nacht"))
            assertEquals(2, it.zahl("SELECT COUNT(*) FROM boulder_nacht_abschnitt"))
        }
    }

    @Test
    fun dieEigeneFassungBleibtOhneKopie() {
        schreibeNacht()
        val db = getRoomDatabase(AppContext())
        assertEquals(beginn, runBlocking { db.nachtDao().juengsteNacht()?.beginnUtc })
        db.close()
        assertEquals(listOf("libpebble3.db"), ordner.listFiles()!!.map { it.name }.filter { it.endsWith(".db") })
    }

    private fun schema(fassung: Int): JsonObject =
        Json.parseToJsonElement(File(schemas, "$fassung.json").readText()).jsonObject.getValue("database").jsonObject

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
        // Der erste Zugriff oeffnet: Room migriert auf 50 und prueft das Schema.
        runBlocking { db.nachtDao().juengsteNacht() }
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
            if ("overlay_data" in tabellen && fassung < 47) {
                // 46 -> 47 fuegt manual mit Vorgabe 0 an.
                assertEquals(0, c.zahl("SELECT manual FROM overlay_data"))
            }
        }
        assertTrue(ordner.listFiles()!!.none { it.name.startsWith("libpebble3-v") })
    }

    @Test fun migration45Bis50() = migriereVon(45)
    @Test fun migration46Bis50() = migriereVon(46)
    @Test fun migration47Bis50() = migriereVon(47)
    @Test fun migration48Bis50() = migriereVon(48)
    @Test fun migration49Bis50() = migriereVon(49)
}
