package io.rebble.libpebblecommon.connection.bt.ble.ppog

import co.touchlab.kermit.Logger
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeByteArray
import io.rebble.libpebblecommon.connection.BleConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.min
import kotlin.time.Duration.Companion.seconds

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
    private val bleConfig: BleConfig,
) {
    private var mtu: Int = initialMtu

    fun run(scope: CoroutineScope) {
        scope.launch {
            val params = withTimeoutOrNull(12.seconds) {
                initWaitingForResetRequest()
            } ?: withTimeoutOrNull(5.seconds) {
                initWithResetRequest()
            } ?: throw IllegalStateException("Timed out initializing PPoG")
            runConnection(params)
        }
    }

    private suspend inline fun <reified T : PPoGPacket> waitForPacket(): T {
        // Wait for reset request from watch
        while (true) {
            val packet = inboundPacketData.receive().asPPoGPacket()
            if (packet !is T) {
                // Not expected, but can happen (i.e. don't crash out): if a watch reconnects
                // really quickly then we can see stale packets come through.
                Logger.w("unexpected packet $packet waiting for ${T::class}")
                continue
            }
            return packet
        }
    }

    private suspend fun initWithResetRequest(): PPoGConnectionParams? {
        if (!bleConfig.reversedPPoG) return null

        Logger.d("initWithResetRequest (iOS reversed PPoG fallback)")
        // Reversed PPoG doesn't have a meta characteristic, so we have to assume.
        val ppogVersion = PPoGVersion.ONE

        // Send reset request
        sendPacketImmediately(
            packet = PPoGPacket.ResetRequest(
                sequence = 0,
                ppogVersion = ppogVersion,
            ),
            version = ppogVersion,
        )

        val resetComplete = waitForPacket<PPoGPacket.ResetComplete>()
        Logger.d("got $resetComplete")

        sendPacketImmediately(resetComplete, ppogVersion)

        return PPoGConnectionParams(
            rxWindow = resetComplete.rxWindow,
            txWindow = resetComplete.txWindow,
            pPoGversion = ppogVersion,
        )
    }

    // Negotiate connection
    private suspend fun initWaitingForResetRequest(): PPoGConnectionParams? {
        Logger.d("initWaitingForResetRequest")

        val resetRequest = waitForPacket<PPoGPacket.ResetRequest>()
        Logger.d("got $resetRequest")

        // Send reset complete
        sendPacketImmediately(
            packet = PPoGPacket.ResetComplete(
                sequence = 0,
                rxWindow = min(desiredRxWindow, MAX_SUPPORTED_WINDOW_SIZE),
                txWindow = min(desiredTxWindow, MAX_SUPPORTED_WINDOW_SIZE),
            ),
            version = resetRequest.ppogVersion
        )

        // Wait for reset complete confirmation
        val resetComplete = inboundPacketData.receive().asPPoGPacket()
        if (resetComplete !is PPoGPacket.ResetComplete) throw IllegalStateException("expected ResetComplete got $resetComplete")
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
                                packet = PPoGPacket.Data(
                                    sequence = outboundSequence.getThenIncrement(),
                                    data = chunk.toByteArray()
                                ),
                                attemptCount = 0
                            )
                        }
                        // TODO retry/timeout
                        .forEach {
                            if (!outboundDataQueue.trySend(it).isSuccess) throw IllegalStateException(
                                "Failed to add message to queue"
                            )
                        }
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
            // TODO do we have rx/tx windows the correct way around here?
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

    fun updateMtu(mtu: Int) {
        // TODO error out if smaller than previous?
        this.mtu = mtu
    }
}

private const val DATA_HEADER_OVERHEAD_BYTES = 1 + 3
private const val MAX_SEQUENCE = 32
private val RESET_REQUEST_TIMEOUT = 10.seconds

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
