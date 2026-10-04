package io.rebble.libpebblecommon.datalogging

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.SystemAppIDs.SYSTEM_APP_UUID
import io.rebble.libpebblecommon.connection.WebServices
import io.rebble.libpebblecommon.services.WatchInfo
import io.rebble.libpebblecommon.structmapper.SBytes
import io.rebble.libpebblecommon.structmapper.SUInt
import io.rebble.libpebblecommon.structmapper.StructMappable
import io.rebble.libpebblecommon.util.DataBuffer
import io.rebble.libpebblecommon.util.Endian
import kotlinx.coroutines.CancellationException
import kotlin.uuid.Uuid

class Datalogging(
    private val webServices: WebServices,
    private val healthDataProcessor: HealthDataProcessor,
    // Boulder: was sich nicht lesen laesst, liegt dort roh, bevor die Uhr es freigibt.
    private val quarantaene: DlsQuarantaene,
) {
    private val logger = Logger.withTag("Datalogging")

    /**
     * Boulder: true, wenn das Paket gesichert ist und die Uhr es freigeben darf -
     * in der Datenbank oder, wo es sich nicht lesen liess, in der Quarantaene.
     * false: nicht bestaetigen, die Uhr schickt es nach ihrer Wartezeit noch einmal.
     */
    suspend fun logData(
        sessionId: UByte,
        uuid: Uuid,
        tag: UInt,
        data: ByteArray,
        watchInfo: WatchInfo,
        itemSize: UShort,
    ): Boolean {
        // Handle health tags
        if (tag in HealthDataProcessor.HEALTH_TAGS) {
            return when (val ablage = healthDataProcessor.speichere(sessionId, tag, uuid, itemSize, data)) {
                Ablage.Gesichert -> true
                is Ablage.Quarantaene -> quarantaene.lege(tag, sessionId, itemSize, data, ablage.grund)
                Ablage.NichtGespeichert -> false
            }
        }

        // Handle system-app datalogging tags
        if (uuid == SYSTEM_APP_UUID) {
            try {
                systemDaten(tag, data, watchInfo, itemSize)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Boulder: vorher beendete das den Empfang aller Datalogging-Pakete.
                logger.e(e) { "System datalogging tag $tag unreadable (${data.size} bytes)" }
                return quarantaene.lege(tag, sessionId, itemSize, data, "unreadable: ${e.message}")
            }
        }
        return true
    }

    private fun systemDaten(tag: UInt, data: ByteArray, watchInfo: WatchInfo, itemSize: UShort) {
        when (tag) {
            MEMFAULT_CHUNKS_TAG -> {
                // A single SendDataItems payload can contain multiple items,
                // each itemSize bytes. Parse each one as a MemfaultChunk.
                val size = itemSize.toInt()
                var offset = 0
                while (offset + size <= data.size) {
                    val itemData = data.copyOfRange(offset, offset + size)
                    val chunk = MemfaultChunk()
                    chunk.fromBytes(DataBuffer(itemData.toUByteArray()))
                    webServices.uploadMemfaultChunk(chunk.bytes.get().toByteArray(), watchInfo)
                    offset += size
                }
            }
            ANALYTICS_HEARTBEAT_TAG -> {
                // Fixed-size native_heartbeat_record items (no inner length prefix).
                val size = itemSize.toInt()
                if (size <= 0) {
                    logger.w { "Analytics heartbeat with itemSize=$size; ignoring" }
                    return
                }
                var offset = 0
                while (offset + size <= data.size) {
                    val itemData = data.copyOfRange(offset, offset + size)
                    webServices.uploadAnalyticsHeartbeat(itemData, watchInfo)
                    offset += size
                }
            }
        }
    }

    fun openSession(sessionId: UByte, tag: UInt, applicationUuid: Uuid, itemSize: UShort) {
        if (tag in HealthDataProcessor.HEALTH_TAGS) {
            healthDataProcessor.handleSessionOpen(sessionId, tag, applicationUuid, itemSize)
        }
    }

    fun closeSession(sessionId: UByte, tag: UInt) {
        if (tag in HealthDataProcessor.HEALTH_TAGS) {
            healthDataProcessor.handleSessionClose(sessionId)
        }
    }

    companion object {
        private val MEMFAULT_CHUNKS_TAG: UInt = 86u
        private val ANALYTICS_HEARTBEAT_TAG: UInt = 87u
    }
}

class MemfaultChunk : StructMappable() {
    val chunkSize: SUInt = SUInt(m, 0u, Endian.Little)
    val bytes: SBytes = SBytes(m).apply { linkWithSize(chunkSize) }
}
