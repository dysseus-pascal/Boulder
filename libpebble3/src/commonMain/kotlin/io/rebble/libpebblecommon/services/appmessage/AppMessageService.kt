package io.rebble.libpebblecommon.services.appmessage

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.ConnectedPebble
import io.rebble.libpebblecommon.connection.PebbleProtocolHandler
import io.rebble.libpebblecommon.di.ConnectionCoroutineScope
import io.rebble.libpebblecommon.packets.AppCustomizationSetStockAppIconMessage
import io.rebble.libpebblecommon.packets.AppCustomizationSetStockAppTitleMessage
import io.rebble.libpebblecommon.packets.AppMessage
import io.rebble.libpebblecommon.packets.AppMessageTuple
import io.rebble.libpebblecommon.services.ProtocolService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

private const val APPMESSAGE_BUFFER_SIZE = 16
private val APPMESSAGE_TIMEOUT = 10.seconds

class AppMessageService(
    private val protocolHandler: PebbleProtocolHandler,
    private val scope: ConnectionCoroutineScope
) : ProtocolService, ConnectedPebble.AppMessages {
    private val logger = Logger.withTag("AppMessageService")
    private val receivedMessages = HashMap<Uuid, Channel<AppMessageData>>()
    override val transactionSequence: Iterator<UByte> = AppMessageTransactionSequence().iterator()
    private val mapAccessMutex = Mutex()

    fun init() {
        protocolHandler.inboundMessages.onEach {
            when (it) {
                is AppMessage.AppMessagePush -> {
                    // Boulder: ein Tupel, das sich nicht lesen laesst (etwa eine Zahl mit 8 Byte,
                    // die die Firmware nicht prueft), warf hier und beendete den Empfang aller
                    // AppMessages dieser Verbindung. Jetzt faellt nur diese Nachricht weg.
                    val daten = try {
                        it.appMessageData()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.e(e) { "Unreadable AppMessage ${it.transactionId.get()} for ${it.uuid.get()} dropped, NACK" }
                        lehneAb(it.transactionId.get())
                        return@onEach
                    }
                    getReceivedMessagesChannel(daten.uuid).trySend(daten).onFailure { e ->
                        logger.w(e) { "AppMessage ${daten.transactionId} for ${daten.uuid} dropped: buffer full" }
                    }
                }
            }
        }.launchIn(scope)
    }

    // Boulder: kein Begleiter sieht die unlesbare Nachricht, also antwortet keiner. Ohne
    // NACK wartete die Uhr-App bis zu ihrem Timeout (10 s, app_message_internal.h) mit
    // blockierter Outbox. Eigene Coroutine: der Empfang wartet nicht auf den Versand.
    private fun lehneAb(transactionId: UByte) {
        scope.launch {
            try {
                protocolHandler.send(AppMessage.AppMessageNACK(transactionId))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.w(e) { "NACK for unreadable AppMessage $transactionId not sent" }
            }
        }
    }

    /**
     * Send an AppMessage
     */
    override suspend fun sendAppMessage(appMessageData: AppMessageData): AppMessageResult {
        val appMessage = AppMessage.AppMessagePush(
            transactionId = appMessageData.transactionId,
            uuid = appMessageData.uuid,
            tuples = appMessageData.data.toAppMessageTuples()
        )
        val result = scope.async {
            withTimeoutOrNull(APPMESSAGE_TIMEOUT) {
                protocolHandler.inboundMessages.first {
                    it is AppMessage && (it is AppMessage.AppMessageACK || it is AppMessage.AppMessageNACK)
                            && it.transactionId.get() == appMessageData.transactionId
                }
            } ?: run {
                logger.w { "Timed out sending AppMessage ${appMessageData.transactionId}" }
                AppMessage.AppMessageNACK(appMessageData.transactionId)
            }
        }
        protocolHandler.send(appMessage)
        return when (val msg = result.await()) {
            is AppMessage.AppMessageACK -> msg.appMessageResult()
            is AppMessage.AppMessageNACK -> msg.appMessageResult()
            else -> throw IllegalStateException("Unexpected result: $result")
        }
    }

    override suspend fun sendAppMessageResult(appMessageResult: AppMessageResult) {
        val appMessage = when (appMessageResult) {
            is AppMessageResult.ACK -> AppMessage.AppMessageACK(appMessageResult.transactionId)
            is AppMessageResult.NACK -> AppMessage.AppMessageNACK(appMessageResult.transactionId)
        }
        protocolHandler.send(appMessage)
    }

    suspend fun send(packet: AppCustomizationSetStockAppIconMessage) {
        protocolHandler.send(packet)
    }

    suspend fun send(packet: AppCustomizationSetStockAppTitleMessage) {
        protocolHandler.send(packet)
    }

    override fun inboundAppMessages(appUuid: Uuid): Flow<AppMessageData> {
        return suspend { getReceivedMessagesChannel(appUuid) }.asFlow().flatMapConcat { it.receiveAsFlow() }
    }

    private suspend fun getReceivedMessagesChannel(appUuid: Uuid): Channel<AppMessageData> {
        receivedMessages[appUuid]?.let { return it }

        return mapAccessMutex.withLock {
            receivedMessages.getOrPut(appUuid) { Channel(APPMESSAGE_BUFFER_SIZE) }
        }
    }
}

private fun Map<Int, Any>.toAppMessageTuples(): List<AppMessageTuple> {
    return map { (key, value) ->
        val k = key.toUInt()
        when (value) {
            is String -> AppMessageTuple.createString(k, value)
            is UByteArray -> AppMessageTuple.createUByteArray(k, value)
            is ByteArray -> AppMessageTuple.createUByteArray(k, value.asUByteArray())
            is Int -> AppMessageTuple.createInt(k, value)
            is UInt -> AppMessageTuple.createUInt(k, value)
            is Short -> AppMessageTuple.createShort(k, value)
            is UShort -> AppMessageTuple.createUShort(k, value)
            is Byte -> AppMessageTuple.createByte(k, value)
            is UByte -> AppMessageTuple.createUByte(k, value)
            is Boolean -> AppMessageTuple.createShort(k, if (value) 1 else 0)
            else -> throw IllegalArgumentException("Unsupported type: ${value::class.simpleName}")
        }
    }
}
typealias AppMessageDictionary = Map<Int, Any>

data class AppMessageData(
    val transactionId: UByte,
    val uuid: Uuid,
    val data: AppMessageDictionary
)

sealed class AppMessageResult(val transactionId: UByte) {
    class ACK(transactionId: UByte) : AppMessageResult(transactionId)
    class NACK(transactionId: UByte) : AppMessageResult(transactionId)
}

private fun AppMessage.AppMessagePush.appMessageData(): AppMessageData {
    return AppMessageData(
        transactionId = transactionId.get(),
        uuid = uuid.get(),
        data = dictionary.list.associate { it.key.get().toInt() to it.getTypedData() }
    )
}

private fun AppMessage.AppMessageACK.appMessageResult() = AppMessageResult.ACK(transactionId.get())
private fun AppMessage.AppMessageNACK.appMessageResult() = AppMessageResult.NACK(transactionId.get())
