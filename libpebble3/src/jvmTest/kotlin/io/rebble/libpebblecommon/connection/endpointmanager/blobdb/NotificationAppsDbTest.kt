package io.rebble.libpebblecommon.connection.endpointmanager.blobdb

import io.rebble.libpebblecommon.packets.blobdb.BlobDB2Command
import io.rebble.libpebblecommon.packets.blobdb.TimelineAttribute
import io.rebble.libpebblecommon.protocolhelpers.PebblePacket
import io.rebble.libpebblecommon.protocolhelpers.PebblePacket.Companion.deserialize
import io.rebble.libpebblecommon.util.DataBuffer
import io.rebble.libpebblecommon.util.Endian
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class NotificationAppsDbTest {

    @Test
    fun testttt() {
        val ppBytes = byteArrayOf(
            0,
            52,
            -78,
            -37,
            8,
            5,
            0,
            6,
            -19,
            -55,
            13,
            104,
            16,
            99,
            111,
            109,
            46,
            103,
            111,
            111,
            103,
            108,
            101,
            46,
            71,
            109,
            97,
            105,
            108,
            25,
            0,
            0,
            0,
            0,
            0,
            3,
            0,
            30,
            5,
            0,
            71,
            109,
            97,
            105,
            108,
            40,
            1,
            0,
            0,
            14,
            4,
            0,
            -19,
            -55,
            13,
            104
        )
        val expectedValue = byteArrayOf(
            0,
            0,
            0,
            0,
            3,
            0,
            30,
            5,
            0,
            71,
            109,
            97,
            105,
            108,
            40,
            1,
            0,
            0,
            14,
            4,
            0,
            -19,
            -55,
            13,
            104
        ).asUByteArray()
        val packet = deserialize(ppBytes.toUByteArray()) as BlobDB2Command.Write
        val value = packet.value.get()
        assertContentEquals(expectedValue, value)
        val item = NotificationAppItem().apply { fromBytes(DataBuffer(value)) }
        assertEquals(3, item.attrCount.get().toInt())
        assertEquals(0, item.actionCount.get().toInt())
        assertEquals(TimelineAttribute.AppName.id, item.attributes.list[0].attributeId.get())
        assertEquals("Gmail", item.attributes.list[0].content.get().toByteArray().decodeToString())
        assertEquals(TimelineAttribute.MuteDayOfWeek.id, item.attributes.list[1].attributeId.get())
        assertContentEquals(byteArrayOf(0), item.attributes.list[1].content.get().toByteArray())
        assertEquals(TimelineAttribute.LastUpdated.id, item.attributes.list[2].attributeId.get())
        val timestamp =
            DataBuffer(item.attributes.list[2].content.get()).apply { setEndian(Endian.Little) }
                .getUInt()
        assertEquals(1745734125, timestamp.toLong())
    }
}