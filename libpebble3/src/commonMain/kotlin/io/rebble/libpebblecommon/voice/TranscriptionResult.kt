package io.rebble.libpebblecommon.voice

import io.rebble.libpebblecommon.packets.Result

sealed class TranscriptionResult {
    data class Success(
        val words: List<TranscriptionWord>,
    ) : TranscriptionResult()

    data object Failed : TranscriptionResult()

    data class ConnectionError(val message: String) : TranscriptionResult()
    data class Error(val message: String) : TranscriptionResult()
    data object Disabled : TranscriptionResult()
}

internal fun TranscriptionResult.toProtocol(): Result {
    return when (this) {
        is TranscriptionResult.Success -> Result.Success
        TranscriptionResult.Failed, is TranscriptionResult.Error -> Result.FailRecognizerError
        is TranscriptionResult.ConnectionError -> Result.FailServiceUnavailable
        TranscriptionResult.Disabled -> Result.FailDisabled
    }
}