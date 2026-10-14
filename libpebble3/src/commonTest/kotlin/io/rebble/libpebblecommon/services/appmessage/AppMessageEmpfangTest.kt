package io.rebble.libpebblecommon.services.appmessage

import TestPebbleProtocolHandler
import io.rebble.libpebblecommon.di.ConnectionCoroutineScope
import io.rebble.libpebblecommon.packets.AppMessage
import io.rebble.libpebblecommon.packets.AppMessageTuple
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * Die Firmware prueft die Breite einer Zahl in einer AppMessage nicht
 * (dict.c); TupletInteger nimmt sizeof, ein int64 kommt mit 8 Byte. Eine
 * solche Nachricht darf den Empfang der Verbindung nicht beenden.
 */
class AppMessageEmpfangTest {
    private val uuid = Uuid.parse("00000000-0000-0000-0000-0000000000dd")

    @Test
    fun eineUnlesbareNachrichtBekommtSofortEinNack() = runTest {
        // Sonst wartete die Uhr-App mit blockierter Outbox bis zu ihrem Timeout (10 s).
        val gesendet = mutableListOf<Any>()
        val uhr = TestPebbleProtocolHandler { gesendet += it }
        val dienst = AppMessageService(uhr, ConnectionCoroutineScope(backgroundScope.coroutineContext))
        dienst.init()
        runCurrent()
        val achtByte = AppMessageTuple().apply {
            key.set(1u)
            type.set(AppMessageTuple.Type.Int.value)
            dataLength.set(8u)
            data.set(UByteArray(8))
        }
        uhr.receivePacket(AppMessage.AppMessagePush(7u, uuid, listOf(achtByte)))
        runCurrent()
        val antwort = assertIs<AppMessage.AppMessageNACK>(gesendet.single())
        assertEquals(7u.toUByte(), antwort.transactionId.get())
    }

    @Test
    fun eineLesbareNachrichtBeantwortetDerDienstNicht() = runTest {
        // Bestaetigen ist Sache des Begleiters (er sichert erst) - der Dienst schweigt.
        val gesendet = mutableListOf<Any>()
        val uhr = TestPebbleProtocolHandler { gesendet += it }
        val dienst = AppMessageService(uhr, ConnectionCoroutineScope(backgroundScope.coroutineContext))
        dienst.init()
        runCurrent()
        uhr.receivePacket(AppMessage.AppMessagePush(8u, uuid, listOf(AppMessageTuple.createInt(1u, 42))))
        runCurrent()
        assertEquals(emptyList(), gesendet)
    }

    @Test
    fun eineUnlesbareNachrichtBeendetDenEmpfangNicht() = runTest {
        val uhr = TestPebbleProtocolHandler { }
        val dienst = AppMessageService(uhr, ConnectionCoroutineScope(backgroundScope.coroutineContext))
        dienst.init()
        runCurrent()
        val achtByte = AppMessageTuple().apply {
            key.set(1u)
            type.set(AppMessageTuple.Type.Int.value)
            dataLength.set(8u)
            data.set(UByteArray(8))
        }
        uhr.receivePacket(AppMessage.AppMessagePush(3u, uuid, listOf(achtByte)))
        uhr.receivePacket(AppMessage.AppMessagePush(4u, uuid, listOf(AppMessageTuple.createInt(1u, 42))))
        runCurrent()
        val angekommen = withTimeoutOrNull(1.seconds) { dienst.inboundAppMessages(uuid).first() }
        assertEquals(4u.toUByte(), angekommen?.transactionId)
        assertEquals(42L, angekommen?.data?.get(1))
    }
}
