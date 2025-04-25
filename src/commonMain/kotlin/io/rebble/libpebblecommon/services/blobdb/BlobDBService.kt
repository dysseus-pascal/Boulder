package io.rebble.libpebblecommon.services.blobdb

import io.rebble.libpebblecommon.PacketPriority
import io.rebble.libpebblecommon.connection.PebbleProtocolHandler
import io.rebble.libpebblecommon.di.ConnectionCoroutineScope
import io.rebble.libpebblecommon.packets.blobdb.BlobCommand
import io.rebble.libpebblecommon.packets.blobdb.BlobResponse
import io.rebble.libpebblecommon.services.ProtocolService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch

/**
 * Singleton to handle sending BlobDB commands cleanly, by allowing registered callbacks to be triggered when the sending packet receives a BlobResponse
 * @see BlobResponse
 */
class BlobDBService(
    private val protocolHandler: PebbleProtocolHandler,
    private val scope: ConnectionCoroutineScope,
) : ProtocolService {
    private val pending: MutableMap<UShort, CompletableDeferred<BlobResponse>> = mutableMapOf()

    fun init() {
        scope.launch {
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