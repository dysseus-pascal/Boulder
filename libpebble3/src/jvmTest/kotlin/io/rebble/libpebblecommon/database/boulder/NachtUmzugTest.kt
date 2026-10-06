package io.rebble.libpebblecommon.database.boulder

import androidx.room.useReaderConnection
import androidx.room.useWriterConnection
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import io.rebble.libpebblecommon.SystemAppIDs.SYSTEM_APP_UUID
import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.database.Database
import io.rebble.libpebblecommon.database.dao.NachtDao
import io.rebble.libpebblecommon.database.entity.NachtAbschnittEntity
import io.rebble.libpebblecommon.database.entity.NachtEntity
import io.rebble.libpebblecommon.database.entity.NachtHrvEntity
import io.rebble.libpebblecommon.database.getRoomDatabase
import io.rebble.libpebblecommon.datalogging.Ablage
import io.rebble.libpebblecommon.datalogging.HealthDataProcessor
import io.rebble.libpebblecommon.di.LibPebbleCoroutineScope
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import org.junit.Assume.assumeTrue
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.TestTimeSource

/**
 * Der einmalige Umzug der Naechte aus libpebble3.db nach boulder.db, mit den
 * echten Datenbanken der App (getRoomDatabase, getBoulderDatenbank) auf dem
 * echten Treiber, in einem eigenen Ordner.
 */
class NachtUmzugTest {
    private val ordner = Files.createTempDirectory("nacht-umzug").toFile()
    private val tmpVorher = System.getProperty("java.io.tmpdir")
    private val altDatei get() = File(ordner, "libpebble3.db")
    private val offen = mutableListOf<androidx.room.RoomDatabase>()
    private val uhr = object : Clock { override fun now() = Instant.fromEpochSeconds(1_790_500_000) }

    @BeforeTest
    fun vorher() {
        System.setProperty("java.io.tmpdir", ordner.absolutePath)
    }

    @AfterTest
    fun nachher() {
        offen.forEach { it.close() }
        System.setProperty("java.io.tmpdir", tmpVorher)
        ordner.deleteRecursively()
    }

    private fun schema(v: Int): JsonObject =
        Json.parseToJsonElement(File("schema/io.rebble.libpebblecommon.database.Database/$v.json").readText())
            .jsonObject.getValue("database").jsonObject

    /** Legt libpebble3.db so an, wie Room sie in Fassung [v] hinterliess (Schema aus dem Export). */
    private fun legeAlteDbAn(v: Int, fuellen: (SQLiteConnection) -> Unit) {
        BundledSQLiteDriver().open(altDatei.absolutePath).use { c ->
            for (e in schema(v).getValue("entities").jsonArray.map { it.jsonObject }) {
                val name = e.getValue("tableName").jsonPrimitive.content
                c.execSQL(e.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", name))
                e["indices"]?.jsonArray?.forEach {
                    c.execSQL(it.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", name))
                }
            }
            schema(v).getValue("setupQueries").jsonArray.forEach { c.execSQL(it.jsonPrimitive.content) }
            c.execSQL("PRAGMA user_version = $v")
            fuellen(c)
        }
    }

    private val n1 = 1_759_100_000L
    private val n2 = 1_759_186_400L

    private fun fuelleNaechte(c: SQLiteConnection) {
        c.execSQL("INSERT INTO boulder_nacht VALUES ($n1, 7200, 30, 500, 450, 52, 41, 80, 71, 0, 3)")
        c.execSQL("INSERT INTO boulder_nacht VALUES ($n2, 7200, 25, 480, 440, 50, 0, NULL, NULL, 1, NULL)")
        for (k in 0 until 61) c.execSQL("INSERT INTO boulder_nacht_abschnitt VALUES ($n1, ${k * 8}, ${k * 8 + 8}, ${k % 4})")
        for (k in 0 until 50) c.execSQL("INSERT INTO boulder_nacht_hrv VALUES ($n1, ${k * 5}, ${30 + k}, ${50 + k % 7})")
        c.execSQL("INSERT INTO spo2_readings VALUES (${n1 + 100}, 96, 5)")
        c.execSQL("INSERT INTO overlay_data VALUES (1759000000, 1800, 8, 900, 0, 50, 0, 0, 1)")
    }

    private fun alt(): Database = getRoomDatabase(AppContext()).also { offen += it }
    private fun neu(): BoulderDatenbank = getBoulderDatenbank(AppContext()).also { offen += it }
    private fun umzug(alt: Database, neu: BoulderDatenbank, zeit: TestTimeSource = TestTimeSource()) =
        NachtUmzug(alt, neu, uhr, zeit)

    private fun neustart() {
        offen.forEach { it.close() }
        offen.clear()
    }

    private suspend fun zahl(db: androidx.room.RoomDatabase, sql: String): Long = db.useReaderConnection { t ->
        t.usePrepared(sql) { it.step(); it.getLong(0) }
    }

    @Test
    fun umzugUebernimmtAllesUndLaesstAltesStehen() = runBlocking<Unit> {
        legeAlteDbAn(50, ::fuelleNaechte)
        val alt = alt()
        val neu = neu()
        umzug(alt, neu).sicherstellen()

        val dao = neu.nachtDao()
        val naechte = dao.naechte(0, Long.MAX_VALUE)
        assertEquals(
            listOf(
                NachtEntity(n1, 7200, 30, 500, 450, 52, 41, 80, 71, false, 3),
                NachtEntity(n2, 7200, 25, 480, 440, 50, 0, null, null, true, null),
            ),
            naechte,
        )
        assertEquals((0 until 61).map { NachtAbschnittEntity(n1, it * 8, it * 8 + 8, it % 4) }, dao.abschnitte(n1))
        assertEquals((0 until 50).map { NachtHrvEntity(n1, it * 5, 30 + it, 50 + it % 7) }, dao.hrv(n1))
        val v = assertNotNull(neu.umzugDao().vermerk())
        assertEquals(listOf(2L, 61L, 50L, uhr.now().toEpochMilliseconds()), listOf(v.naechte, v.abschnitte, v.hrv, v.zeitMs))

        // Die alten Tabellen stehen noch; Upstream-Tabellen mit Boulder-Resten sind heil.
        assertEquals(2, zahl(alt, "SELECT COUNT(*) FROM boulder_nacht"))
        assertEquals(61, zahl(alt, "SELECT COUNT(*) FROM boulder_nacht_abschnitt"))
        assertEquals(50, zahl(alt, "SELECT COUNT(*) FROM boulder_nacht_hrv"))
        assertEquals(1, alt.spo2Dao().getSpo2Readings(0, Long.MAX_VALUE).size)
        assertTrue(alt.healthDao().getAllOverlayEntries().single().manual)
        assertEquals(1045L, zahl(alt, "PRAGMA user_version"))
        assertEquals(1L, zahl(neu, "PRAGMA user_version"))
    }

    @Test
    fun abbruchVorDemVermerkLaesstBoulderDbLeerUndDerNaechsteStartHoltAllesNach() = runBlocking<Unit> {
        legeAlteDbAn(50, ::fuelleNaechte)
        val alt = alt()
        val neu = neu()
        val abgebrochen = umzug(alt, neu).apply { vorDemVermerk = { throw IllegalStateException("Abbruch") } }
        assertFailsWith<IllegalStateException> { abgebrochen.sicherstellen() }
        assertNull(neu.umzugDao().vermerk())
        assertEquals(0, zahl(neu, "SELECT COUNT(*) FROM boulder_nacht"))
        assertEquals(0, zahl(neu, "SELECT COUNT(*) FROM boulder_nacht_abschnitt"))
        assertEquals(0, zahl(neu, "SELECT COUNT(*) FROM boulder_nacht_hrv"))

        neustart()
        val neu2 = neu()
        umzug(alt(), neu2).sicherstellen()
        assertEquals(2, neu2.nachtDao().naechte(0, Long.MAX_VALUE).size)
        assertEquals(61, neu2.nachtDao().abschnitte(n1).size)
        assertEquals(50, neu2.nachtDao().hrv(n1).size)
    }

    @Test
    fun neustartZiehtNichtNochmalUndNeueNaechteBleiben() = runBlocking<Unit> {
        legeAlteDbAn(50, ::fuelleNaechte)
        val neu = neu()
        val dao = UmgezogenerNachtDao(neu.nachtDao(), umzug(alt(), neu))
        dao.speichereNachtTeil(NachtEntity(1_759_272_800, 7200, 20, 470, 430, 49, 38), true, emptyList())
        assertEquals(3, dao.naechte(0, Long.MAX_VALUE).size)

        // Neustart: frische Instanzen; die alten Tabellen sind unveraendert, der Vermerk bleibt der erste.
        val vermerk = neu.umzugDao().vermerk()
        neustart()
        val alt2 = alt()
        val neu2 = neu()
        val spaeter = object : Clock { override fun now() = Instant.fromEpochSeconds(1_790_600_000) }
        NachtUmzug(alt2, neu2, spaeter).sicherstellen()
        assertEquals(vermerk, neu2.umzugDao().vermerk())
        assertEquals(3, neu2.nachtDao().naechte(0, Long.MAX_VALUE).size)
        assertEquals(2, zahl(alt2, "SELECT COUNT(*) FROM boulder_nacht"))
    }

    @Test
    fun frischeInstallationOhneAlteTabellen() = runBlocking<Unit> {
        val alt = alt()
        val neu = neu()
        umzug(alt, neu).sicherstellen()
        val v = assertNotNull(neu.umzugDao().vermerk())
        assertEquals(listOf(0L, 0L, 0L), listOf(v.naechte, v.abschnitte, v.hrv))
        assertEquals(0, zahl(alt, "SELECT COUNT(*) FROM sqlite_master WHERE name LIKE 'boulder_nacht%'"))
    }

    @Test
    fun fassung48WirdErstMigriertDannUmgezogen() = runBlocking<Unit> {
        legeAlteDbAn(48) { c ->
            c.execSQL("INSERT INTO boulder_nacht VALUES ($n1, 7200, 30, 500, 450, 52, 41)")
            c.execSQL("INSERT INTO boulder_nacht_abschnitt VALUES ($n1, 0, 30, 0)")
        }
        val neu = neu()
        umzug(alt(), neu).sicherstellen()
        assertEquals(listOf(NachtEntity(n1, 7200, 30, 500, 450, 52, 41)), neu.nachtDao().naechte(0, Long.MAX_VALUE))
        assertEquals(1, neu.nachtDao().abschnitte(n1).size)
        assertEquals(listOf(1L, 1L, 0L), neu.umzugDao().vermerk()!!.let { listOf(it.naechte, it.abschnitte, it.hrv) })
    }

    @Test
    fun schreibenVorDemUmzugWartetAufIhn() = runBlocking<Unit> {
        legeAlteDbAn(50, ::fuelleNaechte)
        val neu = neu()
        val dao = UmgezogenerNachtDao(neu.nachtDao(), umzug(alt(), neu))
        // Die Uhr schickt Nacht n1 gewachsen neu, bevor jemand gelesen hat.
        dao.speichereNachtTeil(
            NachtEntity(n1, 7200, 30, 520, 470, 52, 41),
            ersterTeil = true,
            (0 until 3).map { NachtAbschnittEntity(n1, it * 100, it * 100 + 100, 1) },
        )
        assertEquals(3, dao.abschnitte(n1).size)
        assertEquals(520, dao.naechte(0, Long.MAX_VALUE).first().aufwachen)
        assertEquals(2, dao.naechte(0, Long.MAX_VALUE).size)
        assertEquals(50, dao.hrv(n1).size)
    }

    @Test
    fun gescheiterterUmzugWirftBisZurPauseUndKlapptDanach() = runBlocking<Unit> {
        legeAlteDbAn(50, ::fuelleNaechte)
        val neu = neu()
        // Eine Zeile, die es in boulder.db vor dem Umzug nicht geben darf: das Einfuegen kollidiert.
        neu.useWriterConnection { t -> t.usePrepared("INSERT INTO boulder_nacht_abschnitt VALUES ($n1, 0, 1, 0)") { it.step() } }
        val zeit = TestTimeSource()
        val umzug = umzug(alt(), neu, zeit)
        val dao = UmgezogenerNachtDao(neu.nachtDao(), umzug)
        assertFailsWith<Exception> { dao.naechte(0, Long.MAX_VALUE) }
        assertNull(neu.umzugDao().vermerk())
        assertEquals(0, zahl(neu, "SELECT COUNT(*) FROM boulder_nacht"))

        // Ursache weg, aber vor Ablauf der Pause kein neuer Versuch: der alte Fehler kommt sofort.
        neu.useWriterConnection { t -> t.usePrepared("DELETE FROM boulder_nacht_abschnitt") { it.step() } }
        zeit += 30.seconds
        val schnell = assertFailsWith<IllegalStateException> { dao.naechte(0, Long.MAX_VALUE) }
        assertEquals("Night move failed recently", schnell.message)
        assertNull(neu.umzugDao().vermerk())

        zeit += 31.seconds
        assertEquals(2, dao.naechte(0, Long.MAX_VALUE).size)
        assertNotNull(neu.umzugDao().vermerk())
    }

    @Test
    fun scheitertDerUmzugBehaeltDieUhrDieNacht() = runBlocking<Unit> {
        // Der Datalogging-Weg: speichern wirft, also NichtGespeichert - kein ACK, die Uhr schickt erneut.
        legeAlteDbAn(50, ::fuelleNaechte)
        val neu = neu()
        neu.useWriterConnection { t -> t.usePrepared("INSERT INTO boulder_nacht VALUES ($n2, 0, 0, 0, 0, 0, 0, NULL, NULL, 0, NULL)") { it.step() } }
        val alt = alt()
        val verarbeitung = HealthDataProcessor(
            LibPebbleCoroutineScope(CoroutineName("test")),
            alt.healthDao(), alt.healthStatDao(), alt.spo2Dao(),
            UmgezogenerNachtDao(neu.nachtDao(), umzug(alt, neu)),
            uhr,
        )
        val satz = nachtSatz(1_790_445_600)
        assertEquals(Ablage.NichtGespeichert, verarbeitung.speichere(1u, 88u, SYSTEM_APP_UUID, 328u, satz))
        assertEquals(1, zahl(neu, "SELECT COUNT(*) FROM boulder_nacht"))
    }

    /** Ein Nachtsatz der Firmware (Fassung 3, 328 Byte) mit einem Abschnitt. */
    private fun nachtSatz(beginn: Long): ByteArray {
        val b = mutableListOf<Byte>()
        fun u8(v: Int) { b += (v and 0xFF).toByte() }
        fun u16(v: Int) { u8(v); u8(v shr 8) }
        fun u32(v: Long) { for (i in 0 until 4) u8((v shr (8 * i)).toInt()) }
        u16(3); u16(328); u32(beginn); u32(7200)
        u16(30); u16(500); u16(450); u8(50); u8(0); u8(1); u16(40)
        u8(1); u16(30); u16(200); u8(1)
        repeat(59) { repeat(5) { u8(0) } }
        u8(80); u8(70); u8(0); u8(3)
        return b.toByteArray()
    }

    @Test
    fun echteTelefonKopie() {
        // Nur von Hand: UMZUG_ECHTE_DB zeigt auf eine Kopie der Datenbank vom Telefon.
        // Ohne sie meldet JUnit den Test als uebersprungen, nicht als bestanden.
        val pfad = System.getenv("UMZUG_ECHTE_DB")
        assumeTrue("UMZUG_ECHTE_DB not set: manual check against a phone copy", pfad != null)
        runBlocking { pruefeEchteKopie(checkNotNull(pfad)) }
    }

    private suspend fun pruefeEchteKopie(pfad: String) {
        File(pfad).copyTo(altDatei, overwrite = true)
        val soll = BundledSQLiteDriver().open(altDatei.absolutePath).use { c ->
            listOf("boulder_nacht", "boulder_nacht_abschnitt", "boulder_nacht_hrv", "spo2_readings").map { t ->
                c.prepare("SELECT COUNT(*) FROM sqlite_master WHERE name = '$t'").use { it.step(); it.getLong(0) }
                    .let { da -> if (da == 0L) 0L else c.prepare("SELECT COUNT(*) FROM $t").use { it.step(); it.getLong(0) } }
            } + c.prepare("PRAGMA user_version").use { it.step(); it.getLong(0) }
        }
        val alt = alt()
        val neu = neu()
        umzug(alt, neu).sicherstellen()
        val v = assertNotNull(neu.umzugDao().vermerk())
        println("ECHT: Fassung ${soll[4]}, alt=${soll.take(4)}, Vermerk=${listOf(v.naechte, v.abschnitte, v.hrv)}")
        assertEquals(soll.take(3), listOf(v.naechte, v.abschnitte, v.hrv))
        assertEquals(soll[3], alt.spo2Dao().getSpo2Readings(0, Long.MAX_VALUE).size.toLong())
    }

    /** Die Methoden von NachtDao, die der Wrapper alle ueberschreiben muss. */
    private val methoden = NachtDao::class.java.declaredMethods.filter { !it.isSynthetic && !Modifier.isStatic(it.modifiers) }

    /** Ein NachtDao, der nur aufzeichnet, welche Methode ihn erreicht. */
    private fun aufzeichnenderDao(aufgerufen: MutableList<String>) =
        Proxy.newProxyInstance(NachtDao::class.java.classLoader, arrayOf(NachtDao::class.java)) { _, m, _ ->
            aufgerufen += m.name
            null
        } as NachtDao

    // Ruft eine suspend-Methode per Reflexion wie ein Kotlin-Aufrufer: wartet, wenn sie
    // aussetzt, und wirft, was sie wirft.
    private suspend fun rufe(m: Method, ziel: Any): Any? = try {
        suspendCoroutineUninterceptedOrReturn { weiter ->
            m.invoke(ziel, *m.parameterTypes.dropLast(1).map(::platzhalter).toTypedArray(), weiter)
        }
    } catch (e: InvocationTargetException) {
        throw e.targetException
    }

    @Test
    fun jedeMethodeGehtAnDieselbeMethodeDesEchtenDao() = runBlocking<Unit> {
        // Eine nicht ueberschriebene Methode mit Rumpf (etwa eine @Transaction) liefe ueber
        // die Einzelmethoden des Wrappers statt an die des echten Dao - ohne dessen Transaktion.
        val aufgerufen = mutableListOf<String>()
        // Frisch und schon umgezogen: der Wrapper ruft danach ohne Warten durch.
        val umzug = umzug(alt(), neu()).also { it.sicherstellen() }
        val wrapper = UmgezogenerNachtDao(aufzeichnenderDao(aufgerufen), umzug)
        for (m in methoden) {
            aufgerufen.clear()
            rufe(m, wrapper)
            assertEquals(listOf(m.name), aufgerufen, m.name)
        }
        assertTrue("speichereNachtTeil" in methoden.map { it.name })
        assertEquals(14, methoden.size)
    }

    @Test
    fun ohneUmzugErreichtKeineMethodeDenEchtenDao() = runBlocking<Unit> {
        // Jede Methode wartet zuerst auf den Umzug. Schriebe etwa speichereHrvTeil vorher in
        // boulder.db, scheiterte der Umzug danach bei jedem Versuch an seinen Pruefsummen.
        legeAlteDbAn(50, ::fuelleNaechte)
        val neu = neu()
        val aufgerufen = mutableListOf<String>()
        // Der Umzug bricht vor dem Vermerk ab; die Zeit steht, also wirft jeder Zugriff erneut.
        val umzug = umzug(alt(), neu).apply { vorDemVermerk = { throw IllegalStateException("Abbruch") } }
        val wrapper = UmgezogenerNachtDao(aufzeichnenderDao(aufgerufen), umzug)
        for (m in methoden) {
            assertFailsWith<IllegalStateException>(m.name) { rufe(m, wrapper) }
            assertEquals(emptyList(), aufgerufen, m.name)
        }
        assertEquals(14, methoden.size)
        assertNull(neu.umzugDao().vermerk())
        assertEquals(0, zahl(neu, "SELECT COUNT(*) FROM boulder_nacht_hrv"))
    }

    private fun platzhalter(typ: Class<*>): Any? = when (typ) {
        java.lang.Long.TYPE -> 0L
        Integer.TYPE -> 0
        java.lang.Boolean.TYPE -> false
        List::class.java -> emptyList<Any>()
        NachtEntity::class.java -> NachtEntity(0, 0, 0, 0, 0, 0, 0)
        else -> error("kein Platzhalter fuer $typ")
    }
}
