import io.ktor.client.*
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.ws
import io.ktor.http.*
import io.ktor.websocket.Frame
import io.rebble.libpebblecommon.exceptions.PacketDecodeException
import io.rebble.libpebblecommon.packets.PingPong
import io.rebble.libpebblecommon.protocolhelpers.PebblePacket
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertTrue

@Ignore("This tests requires manual run.")
class DeviceTests {
    private fun bytesToHex(bytes: UByteArray): String {
        val hexArray = "0123456789ABCDEF".toCharArray()
        val hexChars = CharArray(bytes.size * 2)
        for (j in bytes.indices) {
            val v = (bytes[j] and 0xFFu).toInt()

            hexChars[j * 2] = hexArray[v ushr 4]
            hexChars[j * 2 + 1] = hexArray[v and 0x0F]
        }
        return String(hexChars)
    }
    val phoneHost = "change-me"
    val phonePort = 9000

    val client = HttpClient {
        install(WebSockets)
    }

    private suspend fun sendWS(packet: PebblePacket, blockResponse: Boolean): PebblePacket? {
        var ret: PebblePacket? = null
        withTimeout(5_000) {
            client.ws(
                method = HttpMethod.Get,
                host = phoneHost,
                port = phonePort, path = "/"
            ) {
                send(Frame.Binary(true, byteArrayOf(0x01) + packet.serialize().toByteArray()))
                flush()
                while (blockResponse) {
                    val frame = incoming.receive()
                    if (frame is Frame.Binary) {
                        try {
                            ret = PebblePacket.deserialize(
                                frame.data.slice(1 until frame.data.size).toByteArray()
                                    .toUByteArray()
                            )
                            break
                        } catch (e: PacketDecodeException) {
                            println(e.toString())
                        }
                    }
                }
            }
        }
        return ret
    }

    @Test
    fun sendPing() = runBlocking {
        val res = sendWS(PingPong.Ping(1337u), true)
        val gotPong =
            res?.endpoint == PingPong.endpoint && (res as? PingPong)?.cookie?.get() == 1337u
        assertTrue(gotPong, "Pong not received within sane amount of time")
    }
}