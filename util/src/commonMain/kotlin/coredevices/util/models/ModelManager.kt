package coredevices.util.models

import coredevices.util.CommonBuildKonfig
import coredevices.util.Platform

class ModelManager(
    private val platform: Platform,
    private val modelDownloadManager: ModelDownloadManager,
) {
    val modelDownloadStatus = modelDownloadManager.downloadStatus

    fun downloadSTTModel(modelInfo: ModelInfo, allowMetered: Boolean): Boolean {
        return modelDownloadManager.downloadSTTModel(modelInfo, allowMetered)
    }

    fun downloadLanguageModel(modelInfo: ModelInfo, allowMetered: Boolean): Boolean {
        return modelDownloadManager.downloadLanguageModel(modelInfo, allowMetered)
    }

    fun cancelDownload() {
        modelDownloadManager.cancelDownload()
    }

    fun getDownloadedModelSlugs(): List<String> {
        return listOf(CommonBuildKonfig.CACTUS_STT_MODEL, CommonBuildKonfig.CACTUS_LM_MODEL_NAME)
    }

    fun deleteModel(modelName: String) {
        // No-op: vendored models are managed by CactusModelProvider
    }

    suspend fun getAvailableSTTModels(): List<ModelInfo> {
        return listOf(ModelInfo(slug = CommonBuildKonfig.CACTUS_STT_MODEL))
    }

    suspend fun getAvailableLanguageModels(): List<ModelInfo> {
        return listOf(ModelInfo(slug = CommonBuildKonfig.CACTUS_LM_MODEL_NAME))
    }

    fun getRecommendedSTTMode(): CactusSTTMode {
        return when {
            platform.supportsNPU() || platform.supportsHeavyCPU() -> CactusSTTMode.RemoteFirst
            else -> CactusSTTMode.RemoteOnly
        }
    }

    fun getRecommendedSTTModel(): RecommendedModel {
        return RecommendedModel.Standard(CommonBuildKonfig.CACTUS_STT_MODEL)
    }

    fun getRecommendedLanguageModel(): String {
        return CommonBuildKonfig.CACTUS_LM_MODEL_NAME
    }
}

sealed class RecommendedModel {
    abstract val modelSlug: String
    data class Lite(override val modelSlug: String) : RecommendedModel()
    data class Standard(override val modelSlug: String) : RecommendedModel()
}

data class ModelInfo(
    val createdAt: kotlin.time.Instant = kotlin.time.Clock.System.now(),
    val slug: String,
    val sizeInMB: Int = 0,
    val url: String = ""
)

expect fun Platform.supportsNPU(): Boolean
expect fun Platform.supportsHeavyCPU(): Boolean