package coredevices.ring.transcription

import androidx.compose.ui.text.intl.Locale
import coredevices.util.AudioEncoding
import coredevices.util.transcription.TranscriptionService
import coredevices.util.transcription.TranscriptionSessionStatus
import coredevices.ring.BuildKonfig
import coredevices.util.GCloudTranscription
import coredevices.util.transcription.STTConversationContext
import coredevices.util.transcription.STTLanguage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.io.Buffer
import kotlinx.io.readByteString

class GCloudTranscriptionService: TranscriptionService {
    private val gcTranscription = GCloudTranscription(BuildKonfig.GCLOUD_DICTATION_URL)

    override suspend fun isAvailable(): Boolean {
        return true
    }

    override suspend fun transcribe(
        audioStreamFrames: Flow<ByteArray>?,
        sampleRate: Int,
        language: STTLanguage,
        conversationContext: STTConversationContext?,
        dictionaryContext: List<String>?,
        contentContext: String?,
        encoding: AudioEncoding
    ): Flow<TranscriptionSessionStatus> = flow {
        if (audioStreamFrames == null) {
            TODO("Transcription from default mic is not implemented yet. Please provide audio stream frames.")
        }
        val buffer = Buffer()
        emit(TranscriptionSessionStatus.Open)
        audioStreamFrames.collect {
            buffer.write(it)
        }
        val byteString = buffer.readByteString()
        buffer.close()
        val transcription = gcTranscription.recognize(
            sampleRateHertz = sampleRate,
            audioContent = byteString,
            languageCode = when (language) {
                is STTLanguage.Automatic -> Locale.current.language
                is STTLanguage.Specific -> language.languageCodes.firstOrNull() ?: Locale.current.language
            },
            encoding = when (encoding) {
                AudioEncoding.PCM_16BIT -> GCloudTranscription.Encoding.LINEAR16
                AudioEncoding.PCM_FLOAT_32BIT -> GCloudTranscription.Encoding.LINEAR16
            }
        )
        checkNotNull(transcription) { "Transcription result is null" }
        emit(TranscriptionSessionStatus.Transcription(transcription, "gcloud"))
    }
}