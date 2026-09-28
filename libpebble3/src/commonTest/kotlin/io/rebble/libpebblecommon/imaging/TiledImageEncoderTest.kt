package io.rebble.libpebblecommon.imaging

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class TiledImageEncoderTest {
    private fun image(width: Int, height: Int, pixels: UByteArray) =
        EncodedImage(width, height, UByteArray(16) { (0xc0 + it).toUByte() }, pixels)

    private fun decode(payload: UByteArray, width: Int, height: Int): UByteArray {
        val output = UByteArray(((width + 1) / 2) * height)
        var input = 0
        var written = 0
        while (input < payload.size) {
            val header = payload[input++].toInt() or (payload[input++].toInt() shl 8)
            val end = input + (header and 0x7fff)
            val tileStart = written
            if (header and 0x8000 != 0) {
                while (input < end) output[written++] = payload[input++]
            } else {
                fun length(base: Int): Int {
                    var result = base
                    if (base == 15) do {
                        val extra = payload[input++].toInt()
                        result += extra
                    } while (extra == 255)
                    return result
                }
                while (input < end) {
                    val token = payload[input++].toInt()
                    val literals = length(token ushr 4)
                    repeat(literals) { output[written++] = payload[input++] }
                    if (input == end) break
                    val distance = payload[input++].toInt() or (payload[input++].toInt() shl 8)
                    assertTrue(distance > 0 && distance <= written - tileStart)
                    val match = length(token and 15) + 4
                    repeat(match) {
                        output[written] = output[written - distance]
                        written++
                    }
                }
            }
            assertEquals(end, input)
        }
        assertEquals(output.size, written)
        return output
    }

    @Test
    fun fullResolutionFlatCoverCompressesLosslessly() {
        val pixels = UByteArray(33_800) { 0x12u }
        val encoded = TiledImageEncoder.encode(image(260, 260, pixels))!!
        assertTrue(encoded.size < 1024)
        assertContentEquals(pixels, decode(encoded, 260, 260))
    }

    @Test
    fun incompressibleCoverKeepsFullResolutionWithBoundedOverhead() {
        val pixels = Random(4448).nextBytes(33_800).asUByteArray()
        val encoded = TiledImageEncoder.encode(image(260, 260, pixels))!!
        assertTrue(encoded.size <= 33_852)
        assertContentEquals(pixels, decode(encoded, 260, 260))
    }

    @Test
    fun oddWidthsPartialTilesAndSmallImagesRoundTrip() {
        for ((width, height) in listOf(1 to 1, 3 to 11, 259 to 253, 166 to 166)) {
            val pixels = UByteArray(((width + 1) / 2) * height) { ((it / 7) % 256).toUByte() }
            val encoded = TiledImageEncoder.encode(image(width, height, pixels))!!
            assertContentEquals(pixels, decode(encoded, width, height))
        }
    }

    @Test
    fun rejectsInvalidDimensionsBuffersAndOversizePayloads() {
        assertNull(TiledImageEncoder.encode(image(0, 1, ubyteArrayOf())))
        assertNull(TiledImageEncoder.encode(image(301, 1, UByteArray(151))))
        assertNull(TiledImageEncoder.encode(image(260, 260, UByteArray(100))))
        assertNull(TiledImageEncoder.encode(image(300, 300, Random(7).nextBytes(45_000).asUByteArray())))
    }

    @Test
    fun mixedLiteralAndMatchLengthsRoundTrip() {
        val random = Random(4448)
        repeat(100) { iteration ->
            val width = random.nextInt(1, 301)
            val height = random.nextInt(1, 81)
            val period = random.nextInt(1, 65)
            val pixels = UByteArray(((width + 1) / 2) * height) { i ->
                if (iteration % 3 == 0 || i % 71 < 8) random.nextInt(256).toUByte()
                else (i % period).toUByte()
            }
            val encoded = TiledImageEncoder.encode(image(width, height, pixels))!!
            assertContentEquals(pixels, decode(encoded, width, height))
        }
    }
}
