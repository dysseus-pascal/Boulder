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

    /**
     * Compresses a ShortArray of audio samples in-place to level out loudness,
     * making quieter speech approximate normal volume.
     *
     * @param samples The input/output audio samples as a ShortArray. This array will be modified.
     * @param sampleRate The sample rate of the audio (e.g., 44100).
     * @param rmsWindowMs The time window in milliseconds to calculate RMS loudness (e.g., 50-100ms for speech).
     * @param targetRmsPercentage The target RMS loudness as a percentage of Short.MAX_VALUE (0.0 to 1.0).
     *                              Quieter parts will be boosted towards this level.
     * @param maxGainDb The maximum gain in decibels that can be applied to boost quiet parts.
     *                   Limits noise amplification. (e.g., 18.0 to 24.0 dB good for speech).
     * @param attackMs How quickly the gain reacts to an increase in desired gain (ms).
     * @param releaseMs How quickly the gain reacts to a decrease in desired gain (ms).
     */
    fun levelSpeechLoudness(
        samples: ShortArray, // Renamed to 'samples' to reflect its dual role
        sampleRate: Int,
        rmsWindowMs: Int = 80, // Typical for speech
        targetRmsPercentage: Double = 0.3, // Aim for 30% of max loudness
        maxGainDb: Double = 12.0, // Max 20dB boost
        attackMs: Int = 50,
        releaseMs: Int = 150
    ) { // No return type, as it modifies in-place

        if (samples.isEmpty()) {
            return
        }

        // Convert parameters to samples
        val rmsWindowSamples = (sampleRate * rmsWindowMs / 1000.0).toInt().coerceAtLeast(1)
        val attackSamples = (sampleRate * attackMs / 1000.0).toInt().coerceAtLeast(1)
        val releaseSamples = (sampleRate * releaseMs / 1000.0).toInt().coerceAtLeast(1)

        // Convert Db to linear gain factor
        val maxGainLinear = 10.0.pow(maxGainDb / 20.0)

        // Target RMS value in short amplitude units
        val targetRms = Short.MAX_VALUE.toDouble() * targetRmsPercentage

        // Alpha values for gain smoothing (exponential moving average)
        val attackAlpha = exp(-1.0 / attackSamples)
        val releaseAlpha = exp(-1.0 / releaseSamples)

        // Working buffer for floating-point calculations.
        // We copy the input samples to a DoubleArray first to perform calculations
        // with higher precision, and then write the results back to the original ShortArray.
        val doubleSamples = DoubleArray(samples.size) { samples[it].toDouble() }

        var currentGain = 1.0 // Initialize gain to 1.0 (no change)

        // Iterate through the audio in windows, calculating RMS and applying gain
        for (i in doubleSamples.indices) {
            // Determine the start and end of the current RMS window
            val windowStart = (i - rmsWindowSamples / 2).coerceAtLeast(0)
            val windowEnd = (i + rmsWindowSamples / 2).coerceAtMost(doubleSamples.lastIndex)

            // Calculate RMS for the current window using the doubleSamples buffer
            var sumOfSquares = 0.0
            var count = 0
            for (j in windowStart..windowEnd) {
                sumOfSquares += doubleSamples[j] * doubleSamples[j]
                count++
            }
            val currentRmsWindow = if (count > 0) sqrt(sumOfSquares / count) else 0.0

            // Determine the desired gain for THIS window
            var targetProposedGain = 1.0
            if (currentRmsWindow > 0 && currentRmsWindow < targetRms) {
                targetProposedGain = targetRms / currentRmsWindow
                // Limit the maximum gain to prevent excessive noise amplification
                targetProposedGain = targetProposedGain.coerceAtMost(maxGainLinear)
            } else if (currentRmsWindow >= targetRms) {
                // If loud enough, we want to gently bring the gain back to 1.0 (no boost)
                targetProposedGain = 1.0
            }


            // Smooth the current gain towards the desired target gain (attack/release)
            if (targetProposedGain > currentGain) {
                // Attack phase: gaining volume
                currentGain = (currentGain * (1.0 - attackAlpha)) + (targetProposedGain * attackAlpha)
            } else {
                // Release phase: lowering volume
                currentGain = (currentGain * (1.0 - releaseAlpha)) + (targetProposedGain * releaseAlpha)
            }

            // Apply the smoothed gain to the current sample from the doubleSamples buffer
            var processedSample = doubleSamples[i] * currentGain

            // Clamp the sample to valid Short range to prevent clipping
            processedSample = processedSample.coerceIn(Short.MIN_VALUE.toDouble(), Short.MAX_VALUE.toDouble())

            // Write the result back to the ORIGINAL samples array
            samples[i] = processedSample.toInt().toShort()
        }
    }

    fun process(input: ShortArray): ShortArray {
        val inputCopy = input.copyOf()
        levelSpeechLoudness(inputCopy, sampleRateIn)
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