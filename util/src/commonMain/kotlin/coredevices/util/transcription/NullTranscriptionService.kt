package coredevices.util.transcription

import co.touchlab.kermit.Logger
import coredevices.util.AudioEncoding
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class NullTranscriptionService: TranscriptionService {
    companion object {
        private val logger = Logger.Companion.withTag(NullTranscriptionService::class.simpleName!!)
    }
    override suspend fun isAvailable(): Boolean = true

    //TODO: Throw exception instead of placeholder implementation
    override suspend fun transcribe(
        audioStreamFrames: Flow<ByteArray>?,
        sampleRate: Int,
        encoding: AudioEncoding
    ): Flow<TranscriptionSessionStatus> = flow {
        emit(TranscriptionSessionStatus.Open)
        logger.v { "Transcription flow opened" }
        audioStreamFrames?.collect {
            // Do nothing
        }
        /*emit(TranscriptionSessionStatus.Partial("This"))
        delay(300)
        emit(TranscriptionSessionStatus.Partial("This is"))
        delay(300)
        emit(TranscriptionSessionStatus.Partial("This is a"))
        delay(600)
        emit(TranscriptionSessionStatus.Partial("This is a transcription"))
        delay(1000)
        emit(TranscriptionSessionStatus.Transcription("This is a transcription"))*/
        delay(100)
        emit(TranscriptionSessionStatus.Transcription("Create a note: I need to buy some milk"))
    }
}