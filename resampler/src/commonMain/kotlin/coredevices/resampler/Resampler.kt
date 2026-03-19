package coredevices.resampler

import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sqrt

class Resampler(private val sampleRateIn: Int, private val sampleRateOut: Int) {
    private inline fun linearInterp(y0: Short, y1: Short, mu: Double): Short {
        return (y0 + mu * (y1 - y0)).toInt().toShort()
    }

    fun process(input: ShortArray): ShortArray {
        val inputCopy = input.copyOf()
        val ratio = sampleRateOut.toDouble() / sampleRateIn
        val outputLength = (ceil(inputCopy.size*ratio) + 1).toInt()
        val output = ShortArray(outputLength)
        output[0] = inputCopy[0]
        for (i in 1 until outputLength) {
            val inputPos = i.toDouble()/ratio
            val x0 = floor(inputPos).toInt().coerceAtMost(inputCopy.size-1)
            var x1 = x0 + 1
            if (x1 >= inputCopy.size) {
                x1 = inputCopy.size - 1
            }
            val mu = inputPos - x0
            val y0 = inputCopy[x0]
            val y1 = inputCopy[x1]
            output[i] = linearInterp(y0, y1, mu)
        }
        return output
    }
}