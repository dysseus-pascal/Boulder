package io.rebble.libpebblecommon.services

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.PebbleProtocolHandler
import io.rebble.libpebblecommon.datalogging.Datalogging
import io.rebble.libpebblecommon.di.ConnectionCoroutineScope
import io.rebble.libpebblecommon.packets.DataLoggingIncomingPacket
import io.rebble.libpebblecommon.packets.DataLoggingOutgoingPacket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlin.uuid.Uuid

class DataLoggingService(
    private val protocolHandler: PebbleProtocolHandler,
    private val scope: ConnectionCoroutineScope,
    private val datalogging: Datalogging,
    // Boulder: austauschbar, damit ein Test die Wartezeit der Uhr nachstellen kann.
    private val zeit: TimeSource.WithComparableMarks = TimeSource.Monotonic,
) : ProtocolService {
    private var watchInfo: WatchInfo? = null
    private var acceptSessions = false

    companion object {
        private val logger = Logger.withTag("DataLoggingService")

        // Boulder: die Uhr wartet 30 s auf die Antwort (ACK_NACK_TIMEOUT_TICKS in
        // dls_endpoint.c) und schickt das Paket danach noch einmal. Ein ACK nach
        // ihrer Wartezeit gaelte schon dem neuen Versuch, der mehr Items tragen kann;
        // darum gibt es nach 20 s keins mehr, die Uhr wiederholt dann.
        private val SPAETESTES_ACK = 20.seconds
    }

    private val sessions = mutableMapOf<UByte, DataLoggingSession>()

    // Boulder: ein Paket, das noch gesichert werden muss, bevor die Uhr es freigeben darf.
    private class Eingang(
        val id: UByte,
        val session: DataLoggingSession,
        val daten: ByteArray,
        val info: WatchInfo,
        val ankunft: ComparableTimeMark,
    )

    // UNLIMITED: der Empfang darf nie warten - staut er sich ueber 5 s, trennt
    // PebbleProtocolRunner die Verbindung. Die Uhr schickt je Sitzung erst nach
    // der Antwort weiter, die Schlange bleibt also kurz.
    private val eingang = Channel<Eingang>(Channel.UNLIMITED)

    private suspend fun send(packet: DataLoggingOutgoingPacket) {
        protocolHandler.send(packet)
    }

    private suspend fun sendAckNack(sessionId: UByte) {
        if (acceptSessions) {
            send(DataLoggingOutgoingPacket.ACK(sessionId))
        } else {
            send(DataLoggingOutgoingPacket.NACK(sessionId))
        }
    }

    suspend fun realInit(info: WatchInfo) {
        // Boulder: die Uhr-Info zuerst - wer acceptSessions sieht, findet sie dann schon vor.
        watchInfo = info
        acceptSessions = true
        send(DataLoggingOutgoingPacket.ReportOpenSessions(emptyList()))
    }

    fun initialInit() {
        protocolHandler.inboundMessages.onEach {
            when (it) {
                is DataLoggingIncomingPacket.OpenSession -> {
                    val id = it.sessionId.get()
                    val tag = it.tag.get()
                    val applicationUuid = it.applicationUUID.get()
                    val itemSize = it.dataItemSize.get()
                    logger.d { "Session opened: $id tag: $tag (accepted: $acceptSessions)" }
                    sessions[id] = DataLoggingSession(id, tag, applicationUuid, itemSize)
                    datalogging.openSession(id, tag, applicationUuid, itemSize)
                    sendAckNack(id)
                }

                is DataLoggingIncomingPacket.SendDataItems -> {
                    val id = it.sessionId.get()
                    val session = sessions[id]
                    if (session == null) {
                        logger.e { "Session not found: $id" }
                        return@onEach
                    }
                    val info = watchInfo
                    if (!acceptSessions || info == null) {
                        logger.w { "Data for session $id before init; not accepted" }
                        sendAckNack(id)
                        return@onEach
                    }
                    // Boulder: erst sichern, dann bestaetigen - das ACK schickt der Arbeiter unten.
                    eingang.trySend(Eingang(id, session, it.payload.get().toByteArray(), info, zeit.markNow()))
                }

                is DataLoggingIncomingPacket.CloseSession -> {
                    val id = it.sessionId.get()
                    val session = sessions[id]
                    logger.d { "Session closed: $id" }
                    if (session != null) {
                        datalogging.closeSession(id, session.tag)
                    }
                    sessions.remove(id)
                    sendAckNack(id)
                }
            }
        }.launchIn(scope)
        scope.launch { sichereUndBestaetige() }
    }

    // Boulder: der Reihe nach, wie die Pakete kamen. Ohne Antwort schickt die Uhr
    // das Paket nach ihrer Wartezeit noch einmal; alle Schreibwege ersetzen statt zu doppeln.
    private suspend fun sichereUndBestaetige() {
        for (e in eingang) {
            val gesichert = try {
                datalogging.logData(
                    sessionId = e.id,
                    uuid = e.session.uuid,
                    tag = e.session.tag,
                    data = e.daten,
                    watchInfo = e.info,
                    itemSize = e.session.itemSize,
                )
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                logger.e(ex) { "Datalogging packet for session ${e.id} failed" }
                false
            }
            val alter = e.ankunft.elapsedNow()
            if (gesichert && alter < SPAETESTES_ACK) {
                send(DataLoggingOutgoingPacket.ACK(e.id))
            } else {
                logger.w { "Not acknowledging session ${e.id} (secured: $gesichert, after $alter); the watch sends it again" }
            }
        }
    }
}

data class DataLoggingSession(
    val id: UByte,
    val tag: UInt,
    val uuid: Uuid,
    val itemSize: UShort,
)
