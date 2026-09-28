package io.rebble.libpebblecommon.imaging

/**
 * Lossless transport for the existing packed 4-bpp pixels. Each independent ten-row tile has a
 * little-endian u16 length followed by an LZ4 block. Bit 15 marks an uncompressed tile instead.
 * Incompressible tiles stay at full resolution; their only overhead is the two-byte length.
 * See https://github.com/lz4/lz4/blob/dev/doc/lz4_Block_format.md.
 */
internal object TiledImageEncoder {
    const val TILE_ROWS = 10
    const val MAX_PAYLOAD_BYTES = 40 * 1024

    fun encode(image: EncodedImage): UByteArray? {
        if (image.width !in 1..300 || image.height !in 1..300 || image.palette.size !in 1..16) {
            return null
        }
        val stride = (image.width + 1) / 2
        if (image.pixels.size != stride * image.height) return null
        val output = ArrayList<UByte>(image.pixels.size)
        for (row in 0 until image.height step TILE_ROWS) {
            val start = row * stride
            val length = minOf(TILE_ROWS, image.height - row) * stride
            val raw = image.pixels.copyOfRange(start, start + length)
            val compressed = compressBlock(raw)
            val useRaw = compressed.size >= raw.size
            val tile = if (useRaw) raw else compressed
            val header = tile.size or if (useRaw) 0x8000 else 0
            if (output.size + 2 + tile.size > MAX_PAYLOAD_BYTES) return null
            output.add(header.toUByte())
            output.add((header ushr 8).toUByte())
            output.addAll(tile.asList())
        }
        return output.toUByteArray()
    }

    // A small hash-table LZ4 encoder. Each tile resets its dictionary; matches never refer to
    // another tile. Keep the last five bytes literal and the final match at least 12 bytes back
    // so blocks also work with standard LZ4 decoders.
    private fun compressBlock(input: UByteArray): UByteArray {
        val output = ArrayList<UByte>(input.size)
        val last = IntArray(4096) { -1 }
        fun word(at: Int): Int = input[at].toInt() or (input[at + 1].toInt() shl 8) or
            (input[at + 2].toInt() shl 16) or (input[at + 3].toInt() shl 24)
        fun hash(at: Int): Int = (word(at) * -1640531535) ushr 20
        fun extension(length: Int) {
            var remaining = length
            while (remaining >= 255) {
                output.add(255u)
                remaining -= 255
            }
            output.add(remaining.toUByte())
        }
        fun literals(start: Int, end: Int) {
            for (at in start until end) output.add(input[at])
        }
        var anchor = 0
        var at = 0
        while (at <= input.size - 12) {
            val h = hash(at)
            val reference = last[h]
            last[h] = at
            if (reference < 0 || word(reference) != word(at)) {
                at++
                continue
            }
            var matchLength = 4
            while (at + matchLength < input.size - 5 &&
                input[reference + matchLength] == input[at + matchLength]) {
                matchLength++
            }
            val literalLength = at - anchor
            output.add(((minOf(literalLength, 15) shl 4) or
                minOf(matchLength - 4, 15)).toUByte())
            if (literalLength >= 15) extension(literalLength - 15)
            literals(anchor, at)
            val offset = at - reference
            output.add(offset.toUByte())
            output.add((offset ushr 8).toUByte())
            if (matchLength >= 19) extension(matchLength - 19)
            at += matchLength
            anchor = at
            // Seed the end of a match so the next sequence can reuse its trailing bytes.
            if (at >= 2 && at + 2 < input.size) last[hash(at - 2)] = at - 2
        }
        val remaining = input.size - anchor
        output.add((minOf(remaining, 15) shl 4).toUByte())
        if (remaining >= 15) extension(remaining - 15)
        literals(anchor, input.size)
        return output.toUByteArray()
    }
}
