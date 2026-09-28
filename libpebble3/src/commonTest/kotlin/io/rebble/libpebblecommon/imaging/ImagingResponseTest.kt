package io.rebble.libpebblecommon.imaging

import io.rebble.libpebblecommon.packets.Imaging
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class ImagingResponseTest {
    private val image = EncodedImage(260, 260, ubyteArrayOf(0xc0u, 0xffu),
        Random(1).nextBytes(33_800).asUByteArray())

    private fun le32(bytes: UByteArray, at: Int): Int = (0..3).fold(0) { value, i ->
        value or (bytes[at + i].toInt() shl (8 * i))
    }

    @Test
    fun compressedAlbumResponseDeclaresFullDimensionsLengthAndSequentialOffsets() {
        val chunks = buildResponse(7u, Imaging.ImageType.AlbumArt, image, 0x03u)
        val expected = TiledImageEncoder.encode(image)!!
        val assembled = ArrayList<UByte>()
        chunks.forEachIndexed { index, response ->
            val body = response.body.get()
            assertEquals(7u.toUByte(), body[0])
            assertEquals(assembled.size, le32(body, 2))
            val length = body[6].toInt() or (body[7].toInt() shl 8)
            var start = 8
            if (index == 0) {
                assertEquals(1, body[1].toInt() and 1)
                assertEquals(260, body[8].toInt() or (body[9].toInt() shl 8))
                assertEquals(260, body[10].toInt() or (body[11].toInt() shl 8))
                assertEquals(3u.toUByte(), body[12])
                assertEquals(expected.size, le32(body, 14 + image.palette.size))
                start += 6 + image.palette.size + 4
            }
            assertEquals(if (index == chunks.lastIndex) 2 else 0, body[1].toInt() and 2)
            assertEquals(length, body.size - start)
            assembled.addAll(body.copyOfRange(start, body.size).asList())
        }
        assertContentEquals(expected, assembled.toUByteArray())
    }

    @Test
    fun legacyRequestsAndNotificationsKeepRawFormat() {
        for ((type, format) in listOf(
            0x00u to 0x02u,
            0x01u to 0x03u,
        )) {
            val chunks = buildResponse(9u, Imaging.ImageType.from(type.toUByte())!!, image, format.toUByte())
            val first = chunks.first().body.get()
            assertEquals(2u.toUByte(), first[12])
            assertEquals(type.toInt(), first[1].toInt() ushr 4)
            assertContentEquals(image.pixels.copyOfRange(0, 1000),
                first.copyOfRange(14 + image.palette.size, first.size))
        }
    }

    @Test
    fun missingImageKeepsNoImageResponse() {
        val body = buildResponse(1u, Imaging.ImageType.AlbumArt, null, 0x03u).single().body.get()
        assertEquals(8, body.size)
        assertTrue(body[1].toInt() and 4 != 0)
    }
}
