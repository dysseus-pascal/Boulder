package coredevices.ring.service.recordings

import coredevices.util.KrispAudioProcessor
import coredevices.ring.storage.RecordingStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.io.Buffer
import kotlinx.io.EOFException
import kotlinx.io.readShortLe
import kotlinx.io.writeShortLe

class RecordingPreprocessor(
    private val recordingStorage: RecordingStorage
) {
    private val gain = 7.5f
    suspend fun preprocess(fileId: String) {
        withContext(Dispatchers.IO) {
            val audioProcessor = KrispAudioProcessor()
            audioProcessor.init()
            audioProcessor.use {
                val (fileSource, info) = recordingStorage.openRecordingSource(fileId)
                val source = Buffer()
                fileSource.transferTo(source)
                recordingStorage.openRecordingSink(fileId, info.cachedMetadata.sampleRate, info.cachedMetadata.mimeType).use { sink ->
                    val samples = audioProcessor.samplesPerFrame
                    val buffer = ShortArray(samples)
                    val out = ShortArray(samples)
                    while (true) {
                        var end = buffer.size
                        for (i in buffer.indices) {
                            buffer[i] = try {
                                source.readShortLe()
                            } catch (e: EOFException) {
                                end = i
                                break
                            }
                        }
                        if (end == 0) break
                        // Apply gain to the input samples before processing, ring is quiet
                        for (i in buffer.indices) {
                            val amplifiedSample = (buffer[i] * gain)
                                .toInt()
                                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                            buffer[i] = amplifiedSample.toShort()
                        }
                        audioProcessor.process(buffer, out)
                        for (i in 0 until end) {
                            sink.writeShortLe(out[i])
                        }
                    }
                }
            }

        }
    }
}