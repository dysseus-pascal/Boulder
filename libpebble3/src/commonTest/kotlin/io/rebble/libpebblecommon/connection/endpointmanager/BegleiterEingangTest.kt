package io.rebble.libpebblecommon.connection.endpointmanager

import io.rebble.libpebblecommon.services.appmessage.AppMessageData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

/**
 * Neben einem Begleiter, der jede Nachricht sofort bestaetigt, schickt die Uhr
 * weiter, auch wenn PebbleKit JS noch auf sein JS wartet (bis 6 s je Nachricht).
 * Was sich dabei anstaut, darf nicht still wegfallen.
 */
class BegleiterEingangTest {
    @Test
    fun einLangsamerBegleiterVerliertKeineNachricht() {
        val uuid = Uuid.parse("00000000-0000-0000-0000-0000000000cc")
        val eingang = begleiterEingang()
        repeat(100) { eingang.reicheWeiter(AppMessageData(it.toUByte(), uuid, mapOf(1 to it)), uuid) }
        val angekommen = generateSequence { eingang.tryReceive().getOrNull() }.map { it.transactionId.toInt() }.toList()
        assertEquals((0 until 100).toList(), angekommen)
    }
}
