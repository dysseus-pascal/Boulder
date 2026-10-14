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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlin.uuid.Uuid

class Datalogging(
    private val webServices: WebServices,
    private val healthDataProcessor: HealthDataProcessor,
    // Boulder: was sich nicht lesen laesst, liegt dort roh, bevor die Uhr es freigibt.
    private val quarantaene: DlsQuarantaene,
) {
    private val logger = Logger.withTag("Datalogging")

    private val _thirdPartyEvents = MutableSharedFlow<ThirdPartyDatalogEvent>(extraBufferCapacity = 256)

    /** Data logging events of third-party watchapps, in protocol order. */
    val thirdPartyEvents: SharedFlow<ThirdPartyDatalogEvent> = _thirdPartyEvents.asSharedFlow()

    /**
     * Boulder: true, wenn das Paket gesichert ist und die Uhr es freigeben darf -
     * in der Datenbank oder, wo es sich nicht lesen liess, in der Quarantaene.
     * false: nicht bestaetigen, die Uhr schickt es nach ihrer Wartezeit noch einmal.
     */
    suspend fun logData(
        sessionId: UByte,
        uuid: Uuid,
        tag: UInt,
        timestamp: UInt,
        data: ByteArray,
        watchInfo: WatchInfo,
        itemSize: UShort,
        itemsLeft: UInt,
    ): Boolean {
        // Boulder: mit Itemgroesse 0 laesst sich kein Item abteilen. Schritte, Sitzungen
        // und Heartbeat bestaetigten das Paket bisher ungelesen, die Memfault-Schleife
        // rueckte nie weiter und endete nur ueber eine Ausnahme beim Lesen. Wie alles
        // Unlesbare kommt es roh in die Quarantaene; erst danach gibt die Uhr es frei.
        if (itemSize.toInt() == 0 && wirdGelesen(tag, uuid, itemSize)) {
            if (data.isEmpty()) {
                logger.w { "Datalogging tag $tag with item size 0 and no data; nothing to keep" }
                return true
            }
            logger.e { "Datalogging tag $tag with item size 0 (session $sessionId, ${data.size} bytes); unreadable" }
            return quarantaene.lege(tag, sessionId, itemSize, data, "item size 0")
        }

        if (uuid == SYSTEM_APP_UUID) {
            // Handle health tags
            // Boulder: auch die Tags der Boulder-Firmware (BoulderDls), Unbekanntes aus ihrem Bereich inklusive.
            if (tag in HealthDataProcessor.HEALTH_TAGS || BoulderDls.art(tag, uuid, itemSize) != null) {
                return when (val ablage = healthDataProcessor.speichere(sessionId, tag, uuid, itemSize, data)) {
                    Ablage.Gesichert -> true
                    is Ablage.Quarantaene -> quarantaene.lege(tag, sessionId, itemSize, data, ablage.grund)
                    Ablage.NichtGespeichert -> false
                }
            }

            // Handle system-app datalogging tags
            try {
                systemDaten(tag, data, watchInfo, itemSize)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Boulder: vorher beendete das den Empfang aller Datalogging-Pakete.
                logger.e(e) { "System datalogging tag $tag unreadable (${data.size} bytes)" }
                return quarantaene.lege(tag, sessionId, itemSize, data, "unreadable: ${e.message}")
            }
            return true
        }

        // A third-party session: emit it for delivery to companion apps
        if (itemSize.toInt() <= 0 || data.size % itemSize.toInt() != 0) {
            logger.w { "Dropped a malformed datalogging batch (uuid=$uuid tag=$tag size=${data.size} itemSize=$itemSize)" }
            return true
        }
        val batch = ThirdPartyDatalogEvent.Batch(
            uuid = uuid,
            tag = tag,
            timestamp = timestamp,
            itemSize = itemSize,
            watchSerial = watchInfo.serial,
            itemsLeft = itemsLeft,
            data = data,
        )
        if (!_thirdPartyEvents.tryEmit(batch)) {
            // Boulder: nicht bestaetigen - die Uhr behaelt den Schub und schickt ihn spaeter noch einmal.
            logger.w { "Third-party datalogging buffer is full; not acknowledging the batch (uuid=$uuid tag=$tag)" }
            return false
        }
        return true
    }

    // Boulder: die Tags, deren Items Boulder selbst liest. Alles andere wird wie
    // bisher nur bestaetigt (Gesundheit fremder Apps, Datalogging von Watchapps).
    private fun wirdGelesen(tag: UInt, uuid: Uuid, itemSize: UShort): Boolean =
        BoulderDls.art(tag, uuid, itemSize) != null || (uuid == SYSTEM_APP_UUID &&
            (tag in HealthDataProcessor.HEALTH_TAGS || tag == MEMFAULT_CHUNKS_TAG || tag == ANALYTICS_HEARTBEAT_TAG))

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
        // Boulder: BoulderDls
        if ((applicationUuid == SYSTEM_APP_UUID && tag in HealthDataProcessor.HEALTH_TAGS) ||
            BoulderDls.art(tag, applicationUuid, itemSize) != null
        ) {
            healthDataProcessor.handleSessionOpen(sessionId, tag, applicationUuid, itemSize)
        }
    }

    fun closeSession(
        sessionId: UByte,
        tag: UInt,
        uuid: Uuid,
        timestamp: UInt,
        itemSize: UShort,
        watchSerial: String?,
    ) {
        // Boulder: auch fuer die Boulder-Tags; vergessen wird nur, was beim Oeffnen gemerkt wurde.
        healthDataProcessor.handleSessionClose(sessionId)
        if (uuid == SYSTEM_APP_UUID) {
            return
        }
        if (watchSerial == null) {
            return
        }
        val finished = ThirdPartyDatalogEvent.Finished(uuid, tag, timestamp, itemSize, watchSerial)
        if (!_thirdPartyEvents.tryEmit(finished)) {
            logger.w { "Third-party datalogging buffer is full; dropped a session finish (uuid=$uuid tag=$tag)" }
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

/**
 * A data logging event of a third-party watchapp. [uuid], [tag] and [timestamp] identify the
 * session; all items of a session are [itemSize] bytes.
 */
sealed interface ThirdPartyDatalogEvent {
    val uuid: Uuid
    val tag: UInt
    val timestamp: UInt
    val itemSize: UShort
    val watchSerial: String

    /** One batch of whole items. [itemsLeft] is the number of items that stay on the watch. */
    data class Batch(
        override val uuid: Uuid,
        override val tag: UInt,
        override val timestamp: UInt,
        override val itemSize: UShort,
        override val watchSerial: String,
        val itemsLeft: UInt,
        val data: ByteArray,
    ) : ThirdPartyDatalogEvent

    /** The session is complete and the watch sent all its data. */
    data class Finished(
        override val uuid: Uuid,
        override val tag: UInt,
        override val timestamp: UInt,
        override val itemSize: UShort,
        override val watchSerial: String,
    ) : ThirdPartyDatalogEvent
}
