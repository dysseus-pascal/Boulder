package io.rebble.libpebblecommon.services.blobdb

import io.rebble.libpebblecommon.PacketPriority
import io.rebble.libpebblecommon.ProtocolHandler
import io.rebble.libpebblecommon.connection.PebbleProtocolHandler
import io.rebble.libpebblecommon.packets.blobdb.BlobCommand
import io.rebble.libpebblecommon.packets.blobdb.BlobResponse
import io.rebble.libpebblecommon.protocolhelpers.PebblePacket
import io.rebble.libpebblecommon.protocolhelpers.ProtocolEndpoint
import io.rebble.libpebblecommon.services.ProtocolService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.StateFlow

/**
 * Singleton to handle sending BlobDB commands cleanly, by allowing registered callbacks to be triggered when the sending packet receives a BlobResponse
 * @see BlobResponse
 */
class BlobDBService(private val protocolHandler: PebbleProtocolHandler) : ProtocolService {
    private val pending: MutableMap<UShort, CompletableDeferred<BlobResponse>> = mutableMapOf()

    fun init(scope: CoroutineScope) {
        scope.async {
            protocolHandler.inboundMessages.collect { packet ->
                when (packet) {
                    is BlobResponse -> {
                        pending.remove(packet.token.get())?.complete(packet)
                    }
                }
            }
        }
    }

    /**
     * Send a BlobCommand, with an optional callback to be triggered when a matching BlobResponse is received
     * @see BlobCommand
     * @see BlobResponse
     * @param packet the packet to send
     *
     * @return [BlobResponse] from the watch or *null* if the sending failed
     */
    suspend fun send(
        packet: BlobCommand,
        priority: PacketPriority = PacketPriority.NORMAL
    ): BlobResponse {
        val result = CompletableDeferred<BlobResponse>()
        pending[packet.token.get()] = result

        protocolHandler.send(packet, priority)

        return result.await()
    }
}