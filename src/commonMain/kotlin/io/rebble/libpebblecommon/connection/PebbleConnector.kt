package io.rebble.libpebblecommon.connection

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel

sealed class PebbleConnectionResult {
    class Success(
        val inboundPPBytes: Channel<ByteArray>, // TODO <Byte> or [Source]
        // Using ByteArray here because I'm not 100% sure how the watch handles multiple PP messages
        // within a single PPoG packet (we could make this [Byte] (or use source/sink) if that
        // works OK (for all knowns LE watches).
        val outboundPPBytes: Channel<ByteArray>,
    ) : PebbleConnectionResult()

    class Failed(reason: String) : PebbleConnectionResult()
}

interface PebbleConnector {
    suspend fun connect(pebbleDevice: PebbleDevice, scope: CoroutineScope): PebbleConnectionResult
}
