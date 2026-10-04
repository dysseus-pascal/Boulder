package io.rebble.libpebblecommon.datalogging

import TestPebbleProtocolHandler
import io.rebble.libpebblecommon.SystemAppIDs.SYSTEM_APP_UUID
import io.rebble.libpebblecommon.connection.ConnectedPebbleDevice
import io.rebble.libpebblecommon.connection.WebServices
import io.rebble.libpebblecommon.connection.fakeWatch
import io.rebble.libpebblecommon.database.Database
import io.rebble.libpebblecommon.database.dao.NachtDao
import io.rebble.libpebblecommon.database.entity.NachtAbschnittEntity
import io.rebble.libpebblecommon.database.entity.NachtEntity
import io.rebble.libpebblecommon.database.testDatenbank
import io.rebble.libpebblecommon.di.ConnectionCoroutineScope
import io.rebble.libpebblecommon.di.LibPebbleCoroutineScope
import io.rebble.libpebblecommon.packets.DataLoggingIncomingPacket
import io.rebble.libpebblecommon.packets.DataLoggingOutgoingPacket
import io.rebble.libpebblecommon.services.DataLoggingService
import io.rebble.libpebblecommon.services.WatchInfo
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.io.buffered
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Erst sichern, dann bestaetigen: die Uhr gibt ein Datalogging-Paket nach dem
 * ACK frei. Echter DataLoggingService, echtes Datalogging und echter
 * HealthDataProcessor auf der echten Room-Datenbank; nur die Uhr (Protokoll)
 * und die Quarantaene-Ablage (ein Temp-Ordner) sind gestellt.
 */
class DlsBestaetigungTest {
    private val ordner = Files.createTempDirectory("dls-quarantaene").toString()
    private var db: Database? = null

    @AfterTest
    fun aufraeumen() {
        db?.close()
        java.io.File(ordner).deleteRecursively()
    }

    /** Was an die Uhr ging und was der Speicher tat, in einer Reihe. */
    private val ereignisse = mutableListOf<String>()

    private val nichtImNetz = object : WebServices {
        override suspend fun fetchLocker() = error("nicht im Test")
        override suspend fun removeFromLocker(id: Uuid) = error("nicht im Test")
        override suspend fun checkForFirmwareUpdate(watch: WatchInfo, force: Boolean) = error("nicht im Test")
        override fun uploadMemfaultChunk(chunk: ByteArray, watchInfo: WatchInfo) {}
        override fun uploadAnalyticsHeartbeat(payload: ByteArray, watchInfo: WatchInfo) {}
    }

    /** Die echte NachtDao, deren Speichern eines Teils sich beobachten, bremsen oder scheitern laesst. */
    private class BeobachteteNachtDao(
        private val echt: NachtDao,
        private val ereignisse: MutableList<String>,
    ) : NachtDao by echt {
        var scheitert = false
        var dauer = 0.seconds

        override suspend fun speichereNachtTeil(nacht: NachtEntity, ersterTeil: Boolean, abschnitte: List<NachtAbschnittEntity>) {
            if (dauer.isPositive()) delay(dauer)
            if (scheitert) throw IllegalStateException("database is locked")
            echt.speichereNachtTeil(nacht, ersterTeil, abschnitte)
            ereignisse += "gespeichert"
        }
    }

    private class Aufbau(
        val uhr: TestPebbleProtocolHandler,
        val nachtDao: BeobachteteNachtDao,
        val db: Database,
    )

    private suspend fun TestScope.baue(): Aufbau {
        val dispatcher = coroutineContext[CoroutineDispatcher]!!
        val datenbank = testDatenbank(dispatcher).also { db = it }
        val nachtDao = BeobachteteNachtDao(datenbank.nachtDao(), ereignisse)
        val verarbeitung = HealthDataProcessor(
            LibPebbleCoroutineScope(backgroundScope.coroutineContext),
            datenbank.healthDao(),
            datenbank.healthStatDao(),
            datenbank.spo2Dao(),
            nachtDao,
            object : Clock { override fun now() = Instant.fromEpochSeconds(1_790_500_000) },
        )
        val quarantaene = DlsQuarantaene(object : Clock { override fun now() = Instant.fromEpochSeconds(1_790_500_000) }) { Path(ordner) }
        val uhr = TestPebbleProtocolHandler { paket ->
            when (paket) {
                is DataLoggingOutgoingPacket.ACK -> ereignisse += "ACK ${paket.sessionId.get()}"
                is DataLoggingOutgoingPacket.NACK -> ereignisse += "NACK ${paket.sessionId.get()}"
                else -> Unit
            }
        }
        val dienst = DataLoggingService(
            uhr,
            ConnectionCoroutineScope(backgroundScope.coroutineContext),
            Datalogging(nichtImNetz, verarbeitung, quarantaene),
            testScheduler.timeSource,
        )
        dienst.initialInit()
        runCurrent()
        val info = (fakeWatch(connected = true) as ConnectedPebbleDevice).watchInfo
        dienst.realInit(info)
        runCurrent()
        return Aufbau(uhr, nachtDao, datenbank)
    }

    private suspend fun TestPebbleProtocolHandler.oeffne(sitzung: Int, tag: Int, itemSize: Int) =
        receivePacket(DataLoggingIncomingPacket.OpenSession().apply {
            command.set(1u)
            sessionId.set(sitzung.toUByte())
            applicationUUID.set(SYSTEM_APP_UUID)
            timestamp.set(0u)
            this.tag.set(tag.toUInt())
            dataItemTypeId.set(0u)
            dataItemSize.set(itemSize.toUShort())
        })

    private suspend fun TestPebbleProtocolHandler.schicke(sitzung: Int, daten: ByteArray) =
        receivePacket(DataLoggingIncomingPacket.SendDataItems().apply {
            command.set(2u)
            sessionId.set(sitzung.toUByte())
            itemsLeftAfterThis.set(0xffffu)
            crc.set(0u)
            payload.set(daten.toUByteArray())
        })

    /** Ein Satz auf Tag 88, wie die Boulder-Firmware ihn schickt (Fassung 3, 328 Byte). */
    private fun nachtSatz(beginn: Long, version: Int = 3, abschnitte: List<Triple<Int, Int, Int>>): ByteArray {
        val b = mutableListOf<Byte>()
        fun u8(v: Int) { b += (v and 0xFF).toByte() }
        fun u16(v: Int) { u8(v); u8(v shr 8) }
        fun u32(v: Long) { for (i in 0 until 4) u8((v shr (8 * i)).toInt()) }
        u16(version); u16(328); u32(beginn); u32(7200)
        u16(30); u16(500); u16(450); u8(50); u8(0); u8(1); u16(40)
        u8(abschnitte.size)
        abschnitte.forEach { (von, bis, phase) -> u16(von); u16(bis); u8(phase) }
        repeat(60 - abschnitte.size) { repeat(5) { u8(0) } }
        u8(80); u8(70); u8(0); u8(3)
        return b.toByteArray()
    }

    private val beginn = 1_790_445_600L
    private fun quarantaeneDateien() = java.io.File(ordner).listFiles().orEmpty().filter { it.name.endsWith(".bin") }

    @Test
    fun scheitertDasSpeichernGibtEsKeinAck() = runTest {
        val a = baue()
        a.nachtDao.scheitert = true
        a.uhr.oeffne(3, 88, 328)
        a.uhr.schicke(3, nachtSatz(beginn, abschnitte = listOf(Triple(30, 200, 1))))
        runCurrent()
        // Nur die Sitzung ist bestaetigt; die Uhr behaelt die Nacht und schickt sie nach ihrer Wartezeit wieder.
        assertEquals(listOf("ACK 3"), ereignisse)
        assertTrue(a.db.nachtDao().naechte(0, Long.MAX_VALUE).isEmpty())
    }

    @Test
    fun dasAckKommtErstNachDemSpeichern() = runTest {
        val a = baue()
        a.uhr.oeffne(3, 88, 328)
        ereignisse.clear()
        a.uhr.schicke(3, nachtSatz(beginn, abschnitte = listOf(Triple(30, 200, 1), Triple(200, 480, 2))))
        runCurrent()
        assertEquals(listOf("gespeichert", "ACK 3"), ereignisse)
        assertEquals(2, a.db.nachtDao().abschnitte(beginn).size)
    }

    @Test
    fun spaetGesichertWirdNichtMehrBestaetigt() = runTest {
        // Nach 30 s gibt die Uhr auf; ein ACK danach gaelte ihrem naechsten Versuch.
        val a = baue()
        a.nachtDao.dauer = 25.seconds
        a.uhr.oeffne(3, 88, 328)
        ereignisse.clear()
        a.uhr.schicke(3, nachtSatz(beginn, abschnitte = listOf(Triple(30, 200, 1))))
        testScheduler.advanceTimeBy(26.seconds)
        runCurrent()
        assertEquals(listOf("gespeichert"), ereignisse)
    }

    @Test
    fun derEmpfangWartetNieAufDenSpeicher() = runTest {
        // Ein Stau im Empfang ueber 5 s trennt die Verbindung (PebbleProtocolRunner).
        val a = baue()
        a.nachtDao.dauer = 10.seconds
        a.uhr.oeffne(3, 88, 328)
        a.uhr.schicke(3, nachtSatz(beginn, abschnitte = listOf(Triple(30, 200, 1))))
        a.uhr.oeffne(4, 81, 279)
        runCurrent()
        assertEquals(listOf("ACK 3", "ACK 4"), ereignisse)
        testScheduler.advanceTimeBy(11.seconds)
        runCurrent()
        assertEquals(listOf("ACK 3", "ACK 4", "gespeichert", "ACK 3"), ereignisse)
    }

    @Test
    fun unlesbaresPaketLiegtRohInDerQuarantaeneUndWirdBestaetigt() = runTest {
        val a = baue()
        // Eine Itemgroesse von 1 Byte: kein Sitzungssatz laesst sich daraus lesen.
        a.uhr.oeffne(5, 84, 1)
        ereignisse.clear()
        a.uhr.schicke(5, byteArrayOf(7))
        runCurrent()
        assertEquals(listOf("ACK 5"), ereignisse)
        val datei = quarantaeneDateien().single()
        assertTrue(datei.name.startsWith("dls-tag84-item1-"))
        assertContentEquals(byteArrayOf(7), datei.readBytes())
    }

    @Test
    fun verworfeneSaetzeLiegenRohInDerQuarantaene() = runTest {
        val a = baue()
        a.uhr.oeffne(3, 88, 328)
        ereignisse.clear()
        // Zwei Saetze in einem Paket: einer gut, einer mit unbekannter Fassung.
        val gut = nachtSatz(beginn, abschnitte = listOf(Triple(30, 200, 1)))
        val fremd = nachtSatz(beginn + 86_400, version = 2, abschnitte = listOf(Triple(30, 200, 1)))
        a.uhr.schicke(3, gut + fremd)
        runCurrent()
        assertEquals(listOf("gespeichert", "ACK 3"), ereignisse)
        assertEquals(listOf(beginn), a.db.nachtDao().naechte(0, Long.MAX_VALUE).map { it.beginnUtc })
        assertContentEquals(gut + fremd, quarantaeneDateien().single().readBytes())
    }

    @Test
    fun ohneQuarantaeneKeinAck() = runTest {
        val a = baue()
        // Der Ordner ist eine Datei: die Ablage scheitert, also bleibt das Paket auf der Uhr.
        java.io.File(ordner).deleteRecursively()
        java.io.File(ordner).writeText("kein Ordner")
        a.uhr.oeffne(5, 84, 1)
        ereignisse.clear()
        a.uhr.schicke(5, byteArrayOf(7))
        runCurrent()
        assertEquals(emptyList(), ereignisse)
    }
}
