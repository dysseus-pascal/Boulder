package io.rebble.libpebblecommon.connection.devconnection

import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.connection.PebbleSocketIdentifier
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.lang.reflect.Proxy
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.URLClassLoader
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.util.concurrent.CompletionStage
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Boulder: der Release-Build schliesst kotlin-reflect aus (androidApp/build.gradle.kts,
 * ~0,45 MB). Es kam nur ueber ktor-server-core, und ktor braucht es dort nur, um Module
 * nach Namen aus einer Konfiguration zu laden. Der Test laesst den echten [DevConnectionServer]
 * (LAN-Entwicklerverbindung) in einem Klassenlader OHNE kotlin-reflect laufen: ein
 * Websocket-Gast bekommt Status und Log und reicht eine Nachricht an die Uhr durch.
 */
class DevConnectionOhneReflectTest {

    @Test
    fun lan_server_laeuft_ohne_kotlin_reflect() {
        val eintraege = System.getProperty("java.class.path").split(File.pathSeparator).filter { it.isNotBlank() }
        val (weg, bleibt) = eintraege.partition { File(it).name.startsWith("kotlin-reflect") }
        // Gegenprobe: ohne kotlin-reflect im normalen Testpfad belegte dieser Test nichts.
        assertTrue(weg.isNotEmpty(), "kotlin-reflect steht nicht im Testklassenpfad")
        // Eltern sind die Plattformklassen des JDK (java.net.http), nicht der Testklassenpfad.
        val plattform = ClassLoader.getSystemClassLoader().parent
        URLClassLoader(bleibt.map { File(it).toURI().toURL() }.toTypedArray(), plattform).use { lader ->
            assertFailsWith<ClassNotFoundException> { Class.forName("kotlin.reflect.full.KClasses", false, lader) }
            val probe = Class.forName(OhneReflectProbe::class.java.name, true, lader)
            assertEquals("status, log, relay 1-2-3", probe.getMethod("pruefe").invoke(null))
        }
    }
}

/** Laeuft im Klassenlader ohne kotlin-reflect; alles hier wird dort neu geladen. */
object OhneReflectProbe {
    @JvmStatic
    fun pruefe(): String = runBlocking(Dispatchers.IO) {
        // Der Server fasst LibPebble nur beim Installieren eines PBW an.
        val libPebble = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(LibPebble::class.java)) { selbst, m, a ->
            when (m.name) {
                "hashCode" -> System.identityHashCode(selbst)
                "equals" -> selbst === a?.firstOrNull()
                "toString" -> "LibPebble-Attrappe"
                else -> error("unerwartet: ${m.name}")
            }
        } as LibPebble
        val server = DevConnectionServer(libPebble)
        val anDieUhr = CompletableDeferred<ByteArray>()
        val lauf = launch {
            server.start(
                PebbleSocketIdentifier("probe"),
                inboundPKJSLogs = flowOf("hallo"),
                inboundDeviceMessages = flow { awaitCancellation() },
                outboundDeviceMessages = { anDieUhr.complete(it) },
            )
        }
        try {
            withTimeout(30.seconds) {
                while (runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", DevConnectionServer.PORT), 200) } }.isFailure) {
                    kotlinx.coroutines.delay(50)
                }
            }
            val eingang = LinkedBlockingQueue<ByteArray>()
            val gast = HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(URI("ws://127.0.0.1:${DevConnectionServer.PORT}/"), object : WebSocket.Listener {
                    private val teil = java.io.ByteArrayOutputStream()
                    override fun onBinary(ws: WebSocket, data: ByteBuffer, last: Boolean): CompletionStage<*>? {
                        val b = ByteArray(data.remaining()).also { data.get(it) }
                        teil.write(b)
                        if (last) eingang.put(teil.toByteArray()).also { teil.reset() }
                        ws.request(1)
                        return null
                    }
                }).get(10, TimeUnit.SECONDS)
            val erhalten = List(2) { eingang.poll(10, TimeUnit.SECONDS) ?: error("keine Nachricht vom Server") }
            val typen = erhalten.map { it[0] }.toSet()
            check(ServerMessageType.ConnectionStatusUpdate.value in typen) { "kein Status: $typen" }
            check(ServerMessageType.PhoneAppLog.value in typen) { "kein Log: $typen" }
            gast.sendBinary(ByteBuffer.wrap(byteArrayOf(ClientMessageType.RelayToWatch.value, 1, 2, 3)), true).get(10, TimeUnit.SECONDS)
            val relay = withTimeout(10.seconds) { anDieUhr.await() }
            gast.abort()
            "status, log, relay " + relay.joinToString("-")
        } finally {
            server.stop()
            lauf.cancelAndJoin()
        }
    }
}
