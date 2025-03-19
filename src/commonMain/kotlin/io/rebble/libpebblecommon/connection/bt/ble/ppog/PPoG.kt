package io.rebble.libpebblecommon.connection.bt.ble.ppog

import co.touchlab.kermit.Logger
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeByteArray
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.selects.select
import kotlin.math.min

interface PPoGPacketSender {
    suspend fun sendPacket(packet: ByteArray): Boolean
}

class PPoG(
    private val inboundPPBytes: ByteWriteChannel,
    private val outboundPPBytes: ReceiveChannel<ByteArray>,
    private val inboundPacketData: ReceiveChannel<ByteArray>,
    private val pPoGPacketSender: PPoGPacketSender,
    initialMtu: Int,
    private val desiredTxWindow: Int,
    private val desiredRxWindow: Int,
) {
    private var mtu: Int = initialMtu

    suspend fun run(scope: CoroutineScope) {
        scope.async {
            val params = initPPoG()
            try {
                runConnection(params)
            } catch (e: Exception) {
                Logger.e("error running PPoG", e)
            }
        }
        // TODO error handling - make parent throw if async throws?
    }

    // Negotiate connection
    private suspend fun initPPoG(): PPoGConnectionParams {
        Logger.d("initPPoG")

        // Wait for reset request from watch
        val resetRequest = inboundPacketData.receive().asPPoGPacket()
        if (resetRequest !is PPoGPacket.ResetRequest) throw IllegalStateException("expected ResetRequest got $resetRequest")
        Logger.d("got $resetRequest")

        // Send reset complete
        sendPacketImmediately(
            packet = PPoGPacket.ResetComplete(
                sequence = 0,
                rxWindow = min(desiredRxWindow, MAX_SUPPORTED_WINDOW_SIZE),
                txWindow = min(desiredTxWindow, MAX_SUPPORTED_WINDOW_SIZE)
            ),
            version = resetRequest.ppogVersion
        )

        // Wait for reset complete confirmation
        val resetComplete = inboundPacketData.receive().asPPoGPacket()
        if (resetComplete !is PPoGPacket.ResetComplete) throw IllegalStateException("expected ResetComplete got $resetRequest")
        Logger.d("got $resetComplete")

        return PPoGConnectionParams(
            rxWindow = resetComplete.rxWindow,
            txWindow = resetComplete.txWindow,
            pPoGversion = resetRequest.ppogVersion,
        )
    }

    // No need for any locking - state is only accessed/mutated within this method (except for mtu
    // which can only increase).
    private suspend fun runConnection(params: PPoGConnectionParams) {
        Logger.d("runConnection")

        val outboundSequence = Sequence()
        val inboundSequence = Sequence()
        val outboundDataQueue = Channel<PacketToSend>(Channel.UNLIMITED)
        val inflightPackets = ArrayDeque<PacketToSend>()
        var lastSentAck: PPoGPacket.Ack? = null

        while (true) {
//            Logger.v("select")
            select {
                outboundPPBytes.onReceive { bytes ->
                    bytes.asList().chunked(maxDataBytes())
                        .map { chunk ->
                            PacketToSend(
                                packet = PPoGPacket.Data(sequence = outboundSequence.getThenIncrement(), data = chunk.toByteArray()),
                                attemptCount = 0
                            )
                        }
                        // TODO retry/timeout
                        .forEach { if (!outboundDataQueue.trySend(it).isSuccess) throw IllegalStateException("Failed to add message to queue") }
                }
                inboundPacketData.onReceive { bytes ->
                    val packet = bytes.asPPoGPacket()
                    Logger.v("received packet: $packet")
                    when (packet) {
                        is PPoGPacket.Ack -> {
                            // TODO remove resends of this packet from send queue (+ also remove up-to-them, which OG code didn't do?)

                            // Remove from in-flight packets, up until (including) this packet
                            // TODO warn if we don't have that packet inflight?
                            while (true) {
                                val inflightPacket = inflightPackets.removeFirstOrNull() ?: break
                                if (inflightPacket.packet.sequence == packet.sequence) break
                            }
                        }
                        is PPoGPacket.Data -> {
                            if (packet.sequence != inboundSequence.get()) {
                                Logger.w("data out of sequence; resending last ack")
                                lastSentAck?.let { sendPacketImmediately(it, params.pPoGversion) }
                            } else {
                                inboundPPBytes.writeByteArray(packet.data)
                                inboundPPBytes.flush()
                                inboundSequence.increment()
                                // TODO coalesced ACKing
                                lastSentAck = PPoGPacket.Ack(sequence = packet.sequence)
                                    .also { sendPacketImmediately(it, params.pPoGversion) }
                            }
                        }
                        is PPoGPacket.ResetComplete -> throw IllegalStateException("We don't handle resrtting PPoG - disconnect and reconnect")
                        is PPoGPacket.ResetRequest -> throw IllegalStateException("We don't handle resrtting PPoG - disconnect and reconnect")
                    }
                }
            }

            // Drain send queue
            while (inflightPackets.size < params.txWindow && !outboundDataQueue.isEmpty) {
                // TODO resends (and increment packet's sent counter)
                val packet = outboundDataQueue.receive()
                sendPacketImmediately(packet.packet, params.pPoGversion)
                inflightPackets.add(packet)
            }
        }
    }

    private fun maxDataBytes() = mtu - DATA_HEADER_OVERHEAD_BYTES

    private suspend fun sendPacketImmediately(packet: PPoGPacket, version: PPoGVersion) {
        Logger.v("sendPacketImmediately: $packet")
        if (!pPoGPacketSender.sendPacket(packet.serialize(version))) {
            Logger.e("Couldn't send packet!")
            throw IllegalStateException("Couldn't send packet!")
        }
    }

    suspend fun updateMtu(mtu: Int) {
        // TODO error out if smaller than previous?
        this.mtu = mtu
    }
}

private const val DATA_HEADER_OVERHEAD_BYTES = 1 + 3
private const val MAX_SEQUENCE = 32

private data class PPoGConnectionParams(
    val rxWindow: Int,
    val txWindow: Int,
    val pPoGversion: PPoGVersion,
)

private data class PacketToSend(
    val packet: PPoGPacket.Data,
    val attemptCount: Int
)

private class Sequence {
    private var sequence = 0

    fun getThenIncrement(): Int {
        val currentSequence = sequence
        increment()
        return currentSequence
    }

    fun get(): Int = sequence

    fun increment() {
        sequence = (sequence + 1) % MAX_SEQUENCE
    }
}
