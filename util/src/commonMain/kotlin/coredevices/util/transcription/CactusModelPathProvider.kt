package coredevices.util.transcription

interface CactusModelPathProvider {
    suspend fun getSTTModelPath(): String
    suspend fun getLMModelPath(): String
    fun initTelemetry()
}
