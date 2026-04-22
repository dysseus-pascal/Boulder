package coredevices.util.transcription

actual suspend fun withHighPriorityThread(block: suspend () -> Unit) {
    block()
}