package coredevices.util.transcription

import coredevices.util.AudioEncoding
import kotlinx.coroutines.flow.Flow

interface TranscriptionService {
    /**
     * Check if transcription service is available.
     * @return True if transcription service is available, false otherwise.
     */
    suspend fun isAvailable(): Boolean

    /**
     * Begin initialization of transcription service in the background.
     */
    fun earlyInit() {}

    /**
     * Transcribe audio stream frames to text.
     * @param audioStreamFrames Audio stream frames to transcribe (in PCM format). If null, transcription will use default mic (and requires permission).
     * @return Flow of transcription session status.
     */
    suspend fun transcribe(audioStreamFrames: Flow<ByteArray>?, sampleRate: Int = 16000, encoding: AudioEncoding = AudioEncoding.PCM_16BIT): Flow<TranscriptionSessionStatus>
}