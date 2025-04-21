package io.rebble.libpebblecommon.services.notification

import TestPebbleProtocolHandler
import assertIs
import io.rebble.libpebblecommon.packets.blobdb.BlobCommand
import io.rebble.libpebblecommon.packets.blobdb.BlobResponse
import io.rebble.libpebblecommon.packets.blobdb.NotificationSource
import io.rebble.libpebblecommon.packets.blobdb.PushNotification
import io.rebble.libpebblecommon.services.blobdb.BlobDBService
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class NotificationServiceTest {
    @Test
    fun `Forward notification success`() = runTest(timeout = 5.seconds) {
        val protocolHandler = TestPebbleProtocolHandler { receivedPacket ->
            if (receivedPacket is BlobCommand) {
                this.receivePacket(BlobResponse.Success().also {
                    it.token.set(receivedPacket.token.get())
                })
            }
        }
        val notificationService = NotificationService(BlobDBService(protocolHandler, backgroundScope).apply {
            init()
        })
        // Hack: init() starts collecting asynchronously - we need to make that immediate, really
        yield()
        val result = notificationService.send(TEST_NOTIFICATION)
        assertIs<BlobResponse.Success>(
            result,
            "Reply wasn't success from BlobDB when sending notif"
        )
    }

    @Test
    fun `Forward notification fail`() = runTest(timeout = 5.seconds) {
        val protocolHandler = TestPebbleProtocolHandler { receivedPacket ->
            if (receivedPacket is BlobCommand) {
                this.receivePacket(BlobResponse.GeneralFailure().also {
                    it.token.set(receivedPacket.token.get())
                })
            }
        }

        val notificationService = NotificationService(BlobDBService(protocolHandler, backgroundScope).apply {
            init()
        })
        // Hack: init() starts collecting asynchronously - we need to make that immediate, really
        yield()
        val result = notificationService.send(TEST_NOTIFICATION)

        assertIs<BlobResponse.GeneralFailure>(
            result,
            "Reply wasn't fail from BlobDB when sending notif"
        )
    }

    @Test
    fun `Resend notification`() = runTest(timeout = 5.seconds) {
        val receivedTokens = ArrayList<UShort>()
        val protocolHandler = TestPebbleProtocolHandler { receivedPacket ->
            if (receivedPacket is BlobCommand) {
                val nextPacket = if (receivedTokens.size == 0) {
                    BlobResponse.TryLater()
                } else {
                    BlobResponse.Success()
                }

                this.receivePacket(nextPacket.also {
                    it.token.set(receivedPacket.token.get())
                })

                receivedTokens.add(receivedPacket.token.get())

            }
        }

        val notificationService = NotificationService(BlobDBService(protocolHandler, backgroundScope).apply {
            init()
        })
        // Hack: init() starts collecting asynchronously - we need to make that immediate, really
        yield()
        val result = notificationService.send(TEST_NOTIFICATION)

        assertIs<BlobResponse.Success>(
            result,
            "Reply wasn't success from BlobDB when sending notif"
        )

        assertEquals(2, receivedTokens.size)

        val uniqueTokens = receivedTokens.distinct()
        assertEquals(
            2,
            uniqueTokens.size,
            "NotificationService should re-generate token every time."
        )
    }
}

private val TEST_NOTIFICATION = PushNotification(
    sender = "Test Notif",
    subject = "This is a test notification!",
    message = "This is the notification body",
    backgroundColor = 0b11110011u,
    source = NotificationSource.Email
)
