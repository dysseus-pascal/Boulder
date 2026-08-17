package io.rebble.libpebblecommon.imaging

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.PebbleProtocolHandler
import io.rebble.libpebblecommon.di.ConnectionCoroutineScope
import io.rebble.libpebblecommon.packets.Imaging
import io.rebble.libpebblecommon.services.ProtocolService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Generic image-fetch endpoint (0x35). The watch pulls an image; this service answers every request
 * on the endpoint, delegating the ones a consumer has registered for and telling the watch the rest
 * are unsupported. Consumers never deal with the endpoint, chunking or the no-image fallback.
 */
class ImagingService(
    private val protocolHandler: PebbleProtocolHandler,
    private val watchScope: ConnectionCoroutineScope,
) : ProtocolService {
    private val logger = Logger.withTag("ImagingService")

    /**
     * Produces the image for a request. Type-specific parameters are on the [Imaging.Request]
     * subclass for the type this handler is registered against. Null means "no image right now".
     */
    fun interface Handler {
        suspend fun image(request: Imaging.Request): EncodedImage?
    }

    private val handlers = mutableMapOf<Imaging.ImageType, Handler>()

    // A response is several chunks; another one sent in between would abort the transfer on the
    // watch, so only one response is on the wire at a time.
    private val sendLock = Mutex()

    /**
     * Register the source of images of [type]. Types with no handler are answered UNSUPPORTED, so
     * the watch stops asking for them for the rest of the connection.
     */
    fun registerHandler(type: Imaging.ImageType, handler: Handler) {
        handlers[type] = handler
    }

    fun init() {
        protocolHandler.inboundMessages
            .filterIsInstance<Imaging.Request>()
            .onEach { handleRequest(it) }
            .launchIn(watchScope)
    }

    private suspend fun handleRequest(pkt: Imaging.Request) {
        val token = pkt.token.get()
        val handler = Imaging.ImageType.from(pkt.imageType.get())?.let { handlers[it] }
        if (handler == null) {
            logger.w { "no handler for image type ${pkt.imageType.get()} token=$token" }
            return sendFlags(token, IMAGE_FLAG_UNSUPPORTED)
        }
        val width = pkt.width.get().toInt()
        val height = pkt.height.get().toInt()
        if (width !in 1..MAX_DIM || height !in 1..MAX_DIM) {
            logger.w { "image request token=$token out-of-range ${width}x$height; NO_IMAGE" }
            return sendFlags(token, IMAGE_FLAG_NO_IMAGE)
        }
        val image = try {
            handler.image(pkt)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A handler failure must not kill this collector for the rest of the connection.
            logger.w(e) { "image handler failed for token=$token (${width}x$height)" }
            null
        }
        serveImage(token, image)
    }

    /**
     * Send [image] to the watch for [token]. Also how a handler delivers an image it couldn't
     * produce when first asked — the watch accepts a later response for the same token. A null
     * [image] sends NO_IMAGE, so the watch falls back rather than waiting on the token forever.
     */
    suspend fun serveImage(token: UByte, image: EncodedImage?) = sendLock.withLock {
        val chunks = buildResponse(token, image)
        logger.d { "responding token=$token: ${chunks.size} chunk(s), hasImage=${image != null}" }
        chunks.forEach { protocolHandler.send(it) }
    }

    private suspend fun sendFlags(token: UByte, flags: Int) = sendLock.withLock {
        protocolHandler.send(Imaging.Response(flagsOnlyBody(token, flags)))
    }

    companion object {
        /** Largest dimension we'll encode, mirroring the firmware's IMAGING_MAX_DIM. */
        const val MAX_DIM = 300
    }
}

// Image-response chunk flags (must match the firmware's ImagingResponseFlags).
private const val IMAGE_FLAG_FIRST = 0x01
private const val IMAGE_FLAG_LAST = 0x02
private const val IMAGE_FLAG_NO_IMAGE = 0x04
private const val IMAGE_FLAG_UNSUPPORTED = 0x08

// Format byte in the image header (must match the firmware's ImagingFormat).
private const val IMAGE_FORMAT_4BIT_PALETTE = 0x02

// Pixel bytes per chunk; keeps each Pebble Protocol frame around 1 KB.
private const val IMAGE_CHUNK_PIXELS = 1000

private fun le16(value: Int, out: UByteArray, at: Int) {
    out[at] = (value and 0xFF).toUByte()
    out[at + 1] = ((value shr 8) and 0xFF).toUByte()
}

private fun le32(value: Int, out: UByteArray, at: Int) {
    out[at] = (value and 0xFF).toUByte()
    out[at + 1] = ((value shr 8) and 0xFF).toUByte()
    out[at + 2] = ((value shr 16) and 0xFF).toUByte()
    out[at + 3] = ((value shr 24) and 0xFF).toUByte()
}

private fun flagsOnlyBody(token: UByte, flags: Int): UByteArray {
    val body = UByteArray(8)
    body[0] = token
    body[1] = flags.toUByte()
    return body
}

/**
 * Split [image] into Imaging.Response chunks matching the firmware's wire format (after the command
 * byte the packet prepends):
 *   [token u8][flags u8][offset u32 LE][len u16 LE]([w u16][h u16][format u8][paletteCount u8][palette]) [pixels]
 * The image header (dimensions, format + palette) rides on the first chunk only. A null image (or
 * one with no pixels) yields a single NO_IMAGE chunk so the watch falls back to its text screen.
 */
private fun buildResponse(token: UByte, image: EncodedImage?): List<Imaging.Response> {
    if (image == null || image.pixels.isEmpty()) {
        return listOf(Imaging.Response(flagsOnlyBody(token, IMAGE_FLAG_NO_IMAGE)))
    }
    val header = UByteArray(6 + image.palette.size)
    le16(image.width, header, 0)
    le16(image.height, header, 2)
    header[4] = IMAGE_FORMAT_4BIT_PALETTE.toUByte()
    header[5] = image.palette.size.toUByte()
    image.palette.copyInto(header, 6)

    val pixels = image.pixels
    val total = pixels.size
    val chunks = mutableListOf<Imaging.Response>()
    var offset = 0
    var first = true
    while (offset < total) {
        val n = minOf(IMAGE_CHUNK_PIXELS, total - offset)
        var flags = 0
        if (first) flags = flags or IMAGE_FLAG_FIRST
        if (offset + n >= total) flags = flags or IMAGE_FLAG_LAST
        val headerBytes = if (first) header.size else 0
        val body = UByteArray(8 + headerBytes + n)
        body[0] = token
        body[1] = flags.toUByte()
        le32(offset, body, 2)
        le16(n, body, 6)
        var pos = 8
        if (first) {
            header.copyInto(body, pos)
            pos += header.size
            first = false
        }
        pixels.copyInto(body, pos, offset, offset + n)
        chunks.add(Imaging.Response(body))
        offset += n
    }
    return chunks
}
