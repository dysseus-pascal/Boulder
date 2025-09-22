package io.rebble.libpebblecommon.util

import kotlinx.serialization.Serializable

/**
 * Represents an ARGB8888 color, which is converted to an ARGB2222 color for the Pebble
 */
@Serializable
data class PebbleColor(
    val alpha: UByte,
    val red: UByte,
    val green: UByte,
    val blue: UByte
)

fun PebbleColor.toProtocolNumber() =
    (((alpha / 85u) shl 6) or
    ((red / 85u) shl 4) or
    ((green / 85u) shl 2) or
    (blue / 85u)).toUByte()

/**
 * Converts a 32-bit ARGB8888 integer color to a PebbleColor
 */
fun Int.toPebbleColor(): PebbleColor {
    val a = (this shr 24) and 0xFF
    val r = (this shr 16) and 0xFF
    val g = (this shr 8) and 0xFF
    val b = this and 0xFF

    return PebbleColor(
        a.toUByte(),
        r.toUByte(),
        g.toUByte(),
        b.toUByte()
    )
}