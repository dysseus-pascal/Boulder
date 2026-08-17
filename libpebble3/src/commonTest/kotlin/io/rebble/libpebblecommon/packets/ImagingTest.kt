package io.rebble.libpebblecommon.packets

import assertIs
import io.rebble.libpebblecommon.protocolhelpers.PebblePacket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

internal class ImagingTest {
    @Test
    fun albumArtRequestRoundTrips() {
        val original = Imaging.AlbumArtRequest(
            token = 0x2Au,
            format = Imaging.Format.Palette4Bit.value,
            width = 166u,
            height = 166u,
            title = "Mrs. Robinson",
            artist = "Simon & Garfunkel",
        )

        val decoded = PebblePacket.deserialize(original.serialize())

        // The image-type byte selects the concrete request subclass.
        assertIs<Imaging.AlbumArtRequest>(decoded)
        assertEquals(0x2Au.toUByte(), decoded.token.get())
        assertEquals(Imaging.ImageType.AlbumArt.value, decoded.imageType.get())
        assertEquals(Imaging.Format.Palette4Bit.value, decoded.format.get())
        assertEquals(166, decoded.width.get().toInt())
        assertEquals(166, decoded.height.get().toInt())
        assertEquals("Mrs. Robinson", decoded.title.get())
        assertEquals("Simon & Garfunkel", decoded.artist.get())
    }

    @Test
    fun unknownImageTypeDecodesToBaseRequest() {
        val original = Imaging.Request().apply {
            token.set(1u)
            imageType.set(0x7Fu) // not a known ImageType
            width.set(64u)
            height.set(64u)
        }

        val decoded = PebblePacket.deserialize(original.serialize())

        assertIs<Imaging.Request>(decoded)
        assertFalse(decoded is Imaging.AlbumArtRequest)
        assertEquals(0x7Fu.toUByte(), decoded.imageType.get())
    }
}
