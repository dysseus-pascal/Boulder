package io.rebble.libpebblecommon.database.entity

import coredev.BlobDatabase
import io.rebble.libpebblecommon.LibPebbleConfig
import io.rebble.libpebblecommon.LibPebbleConfigFlow
import io.rebble.libpebblecommon.database.asMillisecond
import io.rebble.libpebblecommon.database.dao.ValueParams
import io.rebble.libpebblecommon.metadata.WatchType
import io.rebble.libpebblecommon.services.FirmwareVersion
import io.rebble.libpebblecommon.services.blobdb.DbWrite
import io.rebble.libpebblecommon.services.blobdb.WriteType
import io.rebble.libpebblecommon.structmapper.SNullTerminatedString
import io.rebble.libpebblecommon.structmapper.StructMapper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

/** Boulder: der Bytes-Typ der Uhreinstellungen (TimeStyle Bedrock, Schluessel "tsBedrock"). */
class BytesWatchPrefTest {
    private val v1 = "01f4c1dfff02010203090dfb2c8f03004e594300000000000000000000000000"

    private fun params(sync: Boolean = true) = ValueParams(
        platform = WatchType.EMERY,
        capabilities = emptySet(),
        firmwareVersion = FirmwareVersion(
            stringVersion = "v4.38.4-b23", timestamp = Instant.DISTANT_PAST, major = 4, minor = 38, patch = 4,
            suffix = "b23", gitHash = "", isRecovery = false, isDualSlot = false, isSlot0 = false,
        ),
        libPebbleConfigFlow = LibPebbleConfigFlow(
            MutableStateFlow(LibPebbleConfig().let { it.copy(watchConfig = it.watchConfig.copy(enableWatchSettingsSync = sync)) }),
        ),
    )

    private fun item(wert: String) = WatchPrefItem(
        id = BytesWatchPref.TimeStyleBedrock.id,
        value = wert,
        timestamp = Instant.fromEpochSeconds(1_790_000_000).asMillisecond(),
    )

    @Test
    fun hexZuBytesZuHexVerlustfrei() {
        val bytes = item(v1).value(params())!!
        assertEquals(32, bytes.size)
        assertContentEquals(v1.hexAlsBytes()!!, bytes.asByteArray())
        // Zurueck, wie die Uhr den Wert einem neuen Telefon meldet.
        val write = DbWrite(
            token = 1u,
            database = BlobDatabase.WatchPrefs,
            timestamp = 1_790_000_000u,
            key = SNullTerminatedString(StructMapper(), "tsBedrock").toBytes(),
            value = bytes,
            writeType = WriteType.Write,
        )
        val zurueck = write.asWatchPrefItem(params())!!
        assertEquals("tsBedrock", zurueck.id)
        assertEquals(v1, zurueck.value)
    }

    @Test
    fun grossOderKleinBeimLesenEgal() {
        assertContentEquals(item(v1).value(params()), item(v1.uppercase()).value(params()))
        assertEquals(v1, v1.uppercase().hexAlsBytes()!!.alsHex())
        assertEquals(v1, BytesWatchPref.TimeStyleBedrock.decodeValue(v1.uppercase()))
    }

    @Test
    fun ungueltigesHexGehtNichtZurUhr() {
        assertNull(item("xyz0").value(params()))
        assertNull(item("abc").value(params()))
        assertNull(item("").value(params()))
        assertNull("0g".hexAlsBytes())
    }

    @Test
    fun ohneSyncGehtNichtsZurUhr() {
        assertNull(item(v1).value(params(sync = false)))
    }

    @Test
    fun schluesselIstBekannt() {
        assertEquals(BytesWatchPref.TimeStyleBedrock, WatchPref.from("tsBedrock"))
        assertNull(WatchPref.from("tsBedrockX"))
        assertEquals(WatchPrefType.TypeBytes, BytesWatchPref.TimeStyleBedrock.type)
        assertEquals(false, BytesWatchPref.TimeStyleBedrock.isDebugSetting)
        assertNull(BytesWatchPref.TimeStyleBedrock.description)
    }

    @Test
    fun ungueltigesInDerDatenbankGiltAlsVorgabe() {
        assertEquals(BytesWatchPref.TimeStyleBedrock.defaultValue, BytesWatchPref.TimeStyleBedrock.decodeValue("kaputt"))
    }
}
