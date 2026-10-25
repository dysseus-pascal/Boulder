package io.rebble.libpebblecommon.datalogging

import io.rebble.libpebblecommon.SystemAppIDs.SYSTEM_APP_UUID
import io.rebble.libpebblecommon.connection.ConnectedPebbleDevice
import io.rebble.libpebblecommon.connection.WebServices
import io.rebble.libpebblecommon.connection.fakeWatch
import io.rebble.libpebblecommon.database.Database
import io.rebble.libpebblecommon.database.boulder.BoulderDatenbank
import io.rebble.libpebblecommon.database.entity.NachtEntity
import io.rebble.libpebblecommon.database.testBoulderDatenbank
import io.rebble.libpebblecommon.database.testDatenbank
import io.rebble.libpebblecommon.di.LibPebbleCoroutineScope
import io.rebble.libpebblecommon.services.WatchInfo
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Welche Datalogging-Tags Boulder als Nacht liest: 88/89 nur mit genau der
 * Itemgroesse der Boulder-Saetze, 0x4001/0x4002 in bekannter Fassung auch aus
 * groesseren Saetzen, eine neuere Fassung und Unbekanntes aus 0x4000-0x40FF roh
 * in die Quarantaene.
 * Echtes Datalogging, echter HealthDataProcessor, echte Room-Datenbanken; nur
 * die Quarantaene liegt in einem Temp-Ordner.
 */
class BoulderDlsTest {
    private val ordner = Files.createTempDirectory("boulder-dls").toFile()
    private var db: Database? = null
    private var boulder: BoulderDatenbank? = null

    @AfterTest
    fun aufraeumen() {
        db?.close()
        boulder?.close()
        ordner.deleteRecursively()
    }

    private var hochgeladen = 0

    private val nichtImNetz = object : WebServices {
        override suspend fun fetchLocker() = error("nicht im Test")
        override suspend fun removeFromLocker(id: Uuid) = error("nicht im Test")
        override suspend fun checkForFirmwareUpdate(watch: WatchInfo, force: Boolean) = error("nicht im Test")
        override fun uploadMemfaultChunk(chunk: ByteArray, watchInfo: WatchInfo) { hochgeladen++ }
        override fun uploadAnalyticsHeartbeat(payload: ByteArray, watchInfo: WatchInfo) { hochgeladen++ }
    }

    private val info by lazy { (fakeWatch(connected = true) as ConnectedPebbleDevice).watchInfo }

    private fun TestScope.datalogging(): Datalogging {
        val dispatcher = coroutineContext[CoroutineDispatcher]!!
        val datenbank = testDatenbank(dispatcher).also { db = it }
        val naechte = testBoulderDatenbank(dispatcher).also { boulder = it }
        val uhr = object : Clock { override fun now() = Instant.fromEpochSeconds(1_790_500_000) }
        val verarbeitung = HealthDataProcessor(
            LibPebbleCoroutineScope(backgroundScope.coroutineContext),
            datenbank.healthDao(), datenbank.healthStatDao(), datenbank.spo2Dao(), naechte.nachtDao(), uhr,
        )
        return Datalogging(nichtImNetz, verarbeitung, DlsQuarantaene(uhr) { Path(ordner.absolutePath) })
    }

    private suspend fun Datalogging.schicke(tag: UInt, itemSize: Int, daten: ByteArray, uuid: Uuid = SYSTEM_APP_UUID): Boolean {
        openSession(1u, tag, uuid, itemSize.toUShort())
        return logData(1u, uuid, tag, 0u, daten, info, itemSize.toUShort(), 0u)
            .also { closeSession(1u, tag, uuid, 0u, itemSize.toUShort(), info.serial) }
    }

    private val beginn = 1_790_445_600L

    /** Ein Nachtsatz wie NachtDlsRecord; [groesse] ueber 328 haengt Bytes einer neueren Fassung an. */
    private fun nachtSatz(version: Int = 3, groesse: Int = 328): ByteArray {
        val b = mutableListOf<Byte>()
        fun u8(v: Int) { b += (v and 0xFF).toByte() }
        fun u16(v: Int) { u8(v); u8(v shr 8) }
        fun u32(v: Long) { for (i in 0 until 4) u8((v shr (8 * i)).toInt()) }
        u16(version); u16(groesse); u32(beginn); u32(7200)
        u16(30); u16(500); u16(450); u8(50); u8(0); u8(1); u16(40)
        u8(2); u16(30); u16(200); u8(1); u16(200); u16(500); u8(2)
        repeat(58) { repeat(5) { u8(0) } }
        if (groesse >= 328) { u8(80); u8(70); u8(1); u8(4) }
        repeat(groesse - b.size) { u8(0xEE) }
        return b.toByteArray()
    }

    /** Ein HRV-Satz wie NachtHrvDlsRecord; [groesse] ueber 251 haengt Bytes einer neueren Fassung an. */
    private fun hrvSatz(version: Int = 1, groesse: Int = 251): ByteArray {
        val b = mutableListOf<Byte>()
        fun u8(v: Int) { b += (v and 0xFF).toByte() }
        fun u16(v: Int) { u8(v); u8(v shr 8) }
        fun u32(v: Long) { for (i in 0 until 4) u8((v shr (8 * i)).toInt()) }
        u16(version); u16(groesse); u32(beginn)
        u8(0); u8(1); u8(2)
        u16(120); u16(57); u8(52); u16(150); u16(61); u8(50)
        repeat(46) { repeat(5) { u8(0) } }
        repeat(groesse - b.size) { u8(0xEE) }
        return b.toByteArray()
    }

    private fun quarantaene() = ordner.listFiles().orEmpty().filter { it.name.endsWith(".bin") }

    private suspend fun naechte() = boulder!!.nachtDao().naechte(0, Long.MAX_VALUE)
    private suspend fun hrv() = boulder!!.nachtDao().hrv(beginn).map { it.minute to it.rmssd }

    private val erwartet = NachtEntity(beginn, 7200, 30, 500, 450, 50, 40, 80, 70, true, 4)

    @Test
    fun dieAltenTagsMitIhrerGroesseSindNaechte() = runTest {
        val d = datalogging()
        assertTrue(d.schicke(88u, 328, nachtSatz()))
        assertTrue(d.schicke(89u, 251, hrvSatz()))
        assertEquals(listOf(erwartet), naechte())
        assertEquals(listOf(120 to 57, 150 to 61), hrv())
        assertTrue(quarantaene().isEmpty())
    }

    @Test
    fun dieNeuenTagsLesenDasselbeWieDieAlten() = runTest {
        val d = datalogging()
        assertTrue(d.schicke(0x4001u, 328, nachtSatz()))
        assertTrue(d.schicke(0x4002u, 251, hrvSatz()))
        assertEquals(listOf(erwartet), naechte())
        assertEquals(listOf(120 to 57, 150 to 61), hrv())
        assertTrue(quarantaene().isEmpty())
    }

    @Test
    fun altesTagMitFremderGroesseGehtDenUpstreamWeg() = runTest {
        // Belegte Upstream einmal Tag 88 oder 89, saehe Boulder dessen Saetze nicht als Nacht.
        val d = datalogging()
        val fremd88 = ByteArray(40) { 1 }
        val fremd89 = ByteArray(2 * 252) { 2 }
        assertTrue(d.schicke(88u, 40, fremd88))
        assertTrue(d.schicke(89u, 252, fremd89))
        // Ein Nachtsatz mit 340 Byte unter 88 ist nicht Boulders alter Vertrag.
        assertTrue(d.schicke(88u, 340, nachtSatz(version = 4, groesse = 340)))
        assertTrue(naechte().isEmpty())
        assertTrue(hrv().isEmpty())
        assertTrue(quarantaene().isEmpty())
        assertEquals(0, hochgeladen)
    }

    @Test
    fun neueTagsLesenAusGroesserenSaetzenDenBekanntenTeilEinerBekanntenFassung() = runTest {
        // Fassung 3 bzw. 1 mit hinten angehaengten Bytes: gelesen wird, was diese App kennt.
        val d = datalogging()
        assertTrue(d.schicke(0x4001u, 340, nachtSatz(groesse = 340) + nachtSatz(groesse = 340)))
        assertTrue(d.schicke(0x4002u, 260, hrvSatz(groesse = 260)))
        assertEquals(listOf(erwartet), naechte())
        assertEquals(listOf(120 to 57, 150 to 61), hrv())
        assertTrue(quarantaene().isEmpty())
    }

    @Test
    fun eineNeuereFassungLiegtRohInDerQuarantaeneUndWirdBestaetigt() = runTest {
        // Ihr Aufbau ist dieser App unbekannt: geraten kaemen Akku und Messstufe womoeglich
        // aus Abschnittsbytes. Bestaetigt wird trotzdem, sonst liefe der Speicher der Uhr voll.
        val d = datalogging()
        val fassung4 = nachtSatz(version = 4, groesse = 340) + nachtSatz(version = 4, groesse = 340)
        assertTrue(d.schicke(0x4001u, 340, fassung4))
        // Der Fall aus der Pruefung: 80 Abschnitte, 428 Byte, Fassung 5.
        val achtzig = nachtSatz(version = 5, groesse = 428)
        assertTrue(d.schicke(0x4001u, 428, achtzig))
        val hrv2 = hrvSatz(version = 2, groesse = 260)
        assertTrue(d.schicke(0x4002u, 260, hrv2))
        // Gleich gross wie heute, aber neuere Fassung: ebenso.
        val gleichGross = nachtSatz(version = 4)
        assertTrue(d.schicke(0x4001u, 328, gleichGross))
        assertTrue(naechte().isEmpty())
        assertTrue(hrv().isEmpty())
        val dateien = quarantaene().sortedBy { it.name }
        assertEquals(
            listOf("dls-tag16385-item328-", "dls-tag16385-item340-", "dls-tag16385-item428-", "dls-tag16386-item260-"),
            dateien.map { it.name.substringBefore("1790") },
        )
        assertContentEquals(gleichGross, dateien[0].readBytes())
        assertContentEquals(fassung4, dateien[1].readBytes())
        assertContentEquals(achtzig, dateien[2].readBytes())
        assertContentEquals(hrv2, dateien[3].readBytes())
    }

    @Test
    fun mehrAbschnitteAlsEinSatzFasstLandenInDerQuarantaene() = runTest {
        // Abgeschnitten fehlte der Rest der Nacht ohne Spur.
        val d = datalogging()
        val nacht = nachtSatz().also { it[23] = 61 }
        assertTrue(d.schicke(0x4001u, 328, nacht))
        val fenster = hrvSatz().also { it[10] = 49 }
        assertTrue(d.schicke(0x4002u, 251, fenster))
        assertTrue(naechte().isEmpty())
        assertTrue(hrv().isEmpty())
        val dateien = quarantaene().sortedBy { it.name }
        assertEquals(listOf("dls-tag16385-item328-", "dls-tag16386-item251-"), dateien.map { it.name.substringBefore("1790") })
        assertContentEquals(nacht, dateien[0].readBytes())
        assertContentEquals(fenster, dateien[1].readBytes())
    }

    @Test
    fun neueTagsVerlangenFassung3UndDenAnhang() = runTest {
        // Unter dem neuen Tag ist Fassung 1 (ohne Anhang) kein gueltiger Satz: roh in die Quarantaene, nicht weg.
        val d = datalogging()
        val zuKlein = nachtSatz(version = 1, groesse = 324)
        assertTrue(d.schicke(0x4001u, 324, zuKlein))
        val alteFassung = nachtSatz(version = 2)
        assertTrue(d.schicke(0x4001u, 328, alteFassung))
        assertTrue(naechte().isEmpty())
        val dateien = quarantaene().sortedBy { it.name }
        assertEquals(listOf("dls-tag16385-item324-", "dls-tag16385-item328-"), dateien.map { it.name.substringBefore("1790") })
        assertContentEquals(zuKlein, dateien[0].readBytes())
        assertContentEquals(alteFassung, dateien[1].readBytes())
    }

    @Test
    fun unbekannterBoulderTagLiegtRohInDerQuarantaeneUndWirdBestaetigt() = runTest {
        val d = datalogging()
        val daten = ByteArray(64) { it.toByte() }
        assertTrue(d.schicke(0x4003u, 64, daten))
        assertTrue(d.schicke(0x40FFu, 16, daten.copyOf(16)))
        assertTrue(naechte().isEmpty())
        val dateien = quarantaene().sortedBy { it.name }
        assertEquals(listOf("dls-tag16387-item64-", "dls-tag16639-item16-"), dateien.map { it.name.substringBefore("1790") })
        assertContentEquals(daten, dateien[0].readBytes())
    }

    @Test
    fun boulderTagsAndererAppsGehenDenUpstreamWeg() = runTest {
        // Eine Watchapp darf 0x4001 fuer sich nutzen; nur unter SYSTEM_APP_UUID ist es Boulders Nacht.
        val d = datalogging()
        val app = Uuid.parse("12345678-1234-1234-1234-123456789abc")
        assertTrue(d.schicke(0x4001u, 328, nachtSatz(), uuid = app))
        assertTrue(d.schicke(0x4003u, 64, ByteArray(64), uuid = app))
        // Auch mit Itemgroesse 0: das ist Sache der App, nicht Boulders Quarantaene.
        assertTrue(d.schicke(0x4001u, 0, ByteArray(8), uuid = app))
        assertTrue(naechte().isEmpty())
        assertTrue(quarantaene().isEmpty())
    }

    @Test
    fun angebrochenerSatzAmEndeLandetInDerQuarantaene() = runTest {
        val d = datalogging()
        val daten = nachtSatz() + ByteArray(10) { 3 }
        assertTrue(d.schicke(0x4001u, 328, daten))
        assertEquals(listOf(erwartet), naechte())
        assertContentEquals(daten, quarantaene().single().readBytes())
    }
}
