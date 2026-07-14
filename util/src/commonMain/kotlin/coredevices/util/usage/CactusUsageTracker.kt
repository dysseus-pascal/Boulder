package coredevices.util.usage

import coredevices.database.CactusUsageEventEntity
import coredevices.util.Platform
import coredevices.util.isAndroid
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

enum class DeviceType(val wireValue: String) {
    Watch("watch"),
    Ring("ring"),
}

enum class CactusEventType(val wireValue: String) {
    Transcribe("transcribe"),
    Complete("complete"),
}

interface CactusUsageTracker {
    fun recordTranscribe(
        deviceType: DeviceType,
        deviceId: String?,
        modelName: String?,
        success: Boolean,
        durationMs: Long,
        failureReason: String? = null,
    )

    fun recordComplete(
        deviceType: DeviceType,
        deviceId: String?,
        modelName: String?,
        success: Boolean,
        durationMs: Long,
        failureReason: String? = null,
    )

    fun recordTranscribeWarmup(
        modelName: String?,
        success: Boolean,
        durationMs: Long,
        failureReason: String? = null,
    )
}

/** No-op tracker for tests and contexts where usage reporting isn't wired up. */
object NoOpCactusUsageTracker : CactusUsageTracker {
    override fun recordTranscribe(
        deviceType: DeviceType,
        deviceId: String?,
        modelName: String?,
        success: Boolean,
        durationMs: Long,
        failureReason: String?,
    ) {}

    override fun recordComplete(
        deviceType: DeviceType,
        deviceId: String?,
        modelName: String?,
        success: Boolean,
        durationMs: Long,
        failureReason: String?,
    ) {}

    override fun recordTranscribeWarmup(
        modelName: String?,
        success: Boolean,
        durationMs: Long,
        failureReason: String?,
    ) {}
}

@OptIn(ExperimentalUuidApi::class)
class RealCactusUsageTracker(
    private val queue: CactusUsageEventQueue,
    private val installIdProvider: InstallIdProvider,
    private val platform: Platform,
    private val appVersion: String,
) : CactusUsageTracker {
    private val platformWire: String = if (platform.isAndroid) "android" else "ios"

    override fun recordTranscribe(
        deviceType: DeviceType,
        deviceId: String?,
        modelName: String?,
        success: Boolean,
        durationMs: Long,
        failureReason: String?,
    ) = emit(
        CactusEventType.Transcribe,
        warmup = false,
        deviceType.wireValue,
        deviceId?.ifBlank { null } ?: UNKNOWN,
        modelName, success, durationMs, failureReason,
    )

    override fun recordComplete(
        deviceType: DeviceType,
        deviceId: String?,
        modelName: String?,
        success: Boolean,
        durationMs: Long,
        failureReason: String?,
    ) = emit(
        CactusEventType.Complete,
        warmup = false,
        deviceType.wireValue,
        deviceId?.ifBlank { null } ?: UNKNOWN,
        modelName, success, durationMs, failureReason,
    )

    override fun recordTranscribeWarmup(
        modelName: String?,
        success: Boolean,
        durationMs: Long,
        failureReason: String?,
    ) = emit(
        CactusEventType.Transcribe,
        warmup = true,
        deviceType = null,
        deviceId = null,
        modelName, success, durationMs, failureReason,
    )

    private fun emit(
        eventType: CactusEventType,
        warmup: Boolean,
        deviceType: String?,
        deviceId: String?,
        modelName: String?,
        success: Boolean,
        durationMs: Long,
        failureReason: String?,
    ) {
        queue.enqueue(
            CactusUsageEventEntity(
                clientEventId = Uuid.random().toString(),
                installId = installIdProvider.installId,
                deviceType = deviceType,
                deviceId = deviceId,
                eventType = eventType.wireValue,
                warmup = warmup,
                modelName = modelName?.ifBlank { null } ?: UNKNOWN,
                success = success,
                failureReason = failureReason,
                durationMs = durationMs,
                appPlatform = platformWire,
                appVersion = appVersion,
                clientEventAt = Clock.System.now().toString(),
            )
        )
    }

    companion object {
        private const val UNKNOWN = "unknown"
    }
}
