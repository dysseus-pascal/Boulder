package io.rebble.libpebblecommon.voice

import io.rebble.libpebblecommon.packets.VoiceAttribute
import io.rebble.libpebblecommon.packets.VoiceAttributeType
import io.rebble.libpebblecommon.util.DataBuffer

sealed class VoiceEncoderInfo {
    abstract val sampleRate: Long

    data class Speex(
        override val sampleRate: Long,
        val version: String,
        val bitRate: Int,
        val bitstreamVersion: Int,
        val frameSize: Int,
    ) : VoiceEncoderInfo()


    companion object Companion {
        fun fromProtocol(attributes: List<VoiceAttribute>): VoiceEncoderInfo? {
            val speexInfoData = attributes.firstOrNull {
                it.id.get() == VoiceAttributeType.SpeexEncoderInfo.value
            }?.content?.get() ?: return null
            val speexInfo = VoiceAttribute.SpeexEncoderInfo()
            speexInfo.fromBytes(DataBuffer(speexInfoData))
            return Speex(
                sampleRate = speexInfo.sampleRate.get().toLong(),
                version = speexInfo.version.get(),
                bitRate = speexInfo.bitRate.get().toInt(),
                bitstreamVersion = speexInfo.bitstreamVersion.get().toInt(),
                frameSize = speexInfo.frameSize.get().toInt()
            )
        }
    }
}