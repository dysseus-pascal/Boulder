package io.rebble.libpebblecommon.database.entity

import coredev.BlobDatabase
import io.rebble.libpebblecommon.LibPebbleConfig
import io.rebble.libpebblecommon.LibPebbleConfigFlow
import io.rebble.libpebblecommon.database.dao.ValueParams
import io.rebble.libpebblecommon.metadata.WatchType
import io.rebble.libpebblecommon.services.FirmwareVersion
import io.rebble.libpebblecommon.services.blobdb.DbWrite
import io.rebble.libpebblecommon.services.blobdb.WriteType
import io.rebble.libpebblecommon.structmapper.SNullTerminatedString
import io.rebble.libpebblecommon.structmapper.StructMapper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * Boulder: was die Uhr ueber ihre eigenen Einstellungen meldet, landet in der
 * Datenbank des Telefons. Die Messstufe nimmt die Firmware ausserhalb 1..5 als
 * Vorgabe 3 (messstufe.c); TimeStyle Bedrock hat genau 32 Byte.
 */
class WatchPrefEmpfangTest {
    private val params = ValueParams(
        platform = WatchType.EMERY,
        capabilities = emptySet(),
        firmwareVersion = FirmwareVersion(
            stringVersion = "v4.38.4-b23", timestamp = Instant.DISTANT_PAST, major = 4, minor = 38, patch = 4,
            suffix = "b23", gitHash = "", isRecovery = false, isDualSlot = false, isSlot0 = false,
        ),
        libPebbleConfigFlow = LibPebbleConfigFlow(MutableStateFlow(LibPebbleConfig())),
    )

    private fun meldung(schluessel: String, wert: UByteArray) = DbWrite(
        token = 1u,
        database = BlobDatabase.WatchPrefs,
        timestamp = 1_790_000_000u,
        key = SNullTerminatedString(StructMapper(), schluessel).toBytes(),
        value = wert,
        writeType = WriteType.Write,
    ).asWatchPrefItem(params)

    @Test
    fun messstufeAusserhalbGiltAlsVorgabe() {
        assertEquals("4", meldung("boulderMessstufe", ubyteArrayOf(4u))?.value)
        assertEquals("3", meldung("boulderMessstufe", ubyteArrayOf(9u))?.value)
        assertEquals("3", meldung("boulderMessstufe", ubyteArrayOf(0u))?.value)
    }

    @Test
    fun timeStyleNurMit32Byte() {
        assertNull(meldung("tsBedrock", ubyteArrayOf(1u, 2u, 3u)))
        assertEquals(64, meldung("tsBedrock", UByteArray(32) { it.toUByte() })?.value?.length)
    }
}
