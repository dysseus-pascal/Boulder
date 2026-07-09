package coredevices.ring.service.recordings

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.round

/**
 * First-order IIR high-pass filter
 */
class HighPassFilter(sampleRate: Int, cutoffHz: Double) {

    // R = exp(-2*pi*fc/fs), stored in Q15.
    private val rQ15: Int = run {
        require(sampleRate > 0) { "sampleRate must be > 0" }
        require(cutoffHz > 0.0 && cutoffHz < sampleRate / 2.0) {
            "cutoffHz must be in (0, sampleRate/2)"
        }
        val r = exp(-2.0 * PI * cutoffHz / sampleRate)
        round(r * 32768.0).toInt().coerceIn(0, 32767)
    }

    private var xm1 = 0      // previous input
    private var ym1 = 0      // previous output, Q15, 32-bit

    /** Filter a single sample. */
    fun process(x: Short): Short {
        val y = (x.toInt() - xm1) + ((rQ15 * ym1) shr 15)
        xm1 = x.toInt()
        ym1 = y
        return y.coerceIn(-32768, 32767).toShort()
    }

    /** Filter a buffer in place. */
    fun process(buffer: ShortArray, offset: Int = 0, count: Int = buffer.size - offset) {
        var xm = xm1
        var ym = ym1
        val end = offset + count
        var i = offset
        while (i < end) {
            val xi = buffer[i].toInt()
            val y = (xi - xm) + ((rQ15 * ym) shr 15)
            xm = xi
            ym = y
            buffer[i] = y.coerceIn(-32768, 32767).toShort()
            i++
        }
        xm1 = xm
        ym1 = ym
    }

    /** Reset filter state. */
    fun reset() {
        xm1 = 0
        ym1 = 0
    }
}