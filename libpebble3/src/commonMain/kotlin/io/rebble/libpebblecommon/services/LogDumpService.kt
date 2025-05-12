package io.rebble.libpebblecommon.services

import co.touchlab.kermit.Logger
import io.ktor.util.cio.use
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.asByteWriteChannel
import io.ktor.utils.io.writeString
import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.connection.ConnectedPebble
import io.rebble.libpebblecommon.connection.PebbleProtocolHandler
import io.rebble.libpebblecommon.connection.Transport
import io.rebble.libpebblecommon.packets.LogDump
import io.rebble.libpebblecommon.packets.LogDump.ReceivedLogDumpMessage
import io.rebble.libpebblecommon.util.getTempFilePath
import io.rebble.libpebblecommon.util.randomCookie
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.time.Duration.Companion.seconds

class LogDumpService(
    private val protocolHandler: PebbleProtocolHandler,
    private val appContext: AppContext,
    private val transport: Transport,
) : ProtocolService, ConnectedPebble.Logs {
    private val logger = Logger.withTag("LogDumpService")

    override suspend fun gatherLogs(): Path? {
        logger.d { "gatherLogs" }
        return withTimeoutOrNull(LOG_DUMP_TIMEOUT) {
            val tempLogFile = getTempFilePath(appContext, "logs-${transport.identifier.asString}")
            var writtenLogs = false
            SystemFileSystem.sink(tempLogFile).use { sink ->
                sink.asByteWriteChannel().use {
                    for (generation in 0..<NUM_GENERATIONS_LEGACY) {
                        writtenLogs = writtenLogs || requestLogGeneration(generation, this)
                    }
                }
            }

            logger.d { "gatherLogs done: writtenLogs=$writtenLogs" }
            if (writtenLogs) {
                tempLogFile
            } else {
                null
            }
        }
    }

    private suspend fun requestLogGeneration(
        generation: Int,
        writeChannel: ByteWriteChannel,
    ): Boolean {
        logger.d { "requestLogGeneration: $generation" }
        val cookie = randomCookie()
        var written = false
        protocolHandler.inboundMessages
            .onSubscription {
                protocolHandler.send(
                    LogDump.RequestLogDump(
                        logGeneration = generation.toUByte(),
                        cookie = cookie,
                    )
                )
            }
            .filterIsInstance(ReceivedLogDumpMessage::class)
            .takeWhile {
                when (it) {
                    is LogDump.LogLine -> true
                    is LogDump.Done -> false
                    is LogDump.NoLogs -> false
                }
            }.collect {
                if (it is LogDump.LogLine) {
                    written = true
                    writeChannel.writeString(it.messageText.get())
//                    logger.d { "LogLine: ${it.messageText}" }
                }
            }
        logger.d { "finished generation $generation written = $written" }
        return written
    }

    companion object {
        private val LOG_DUMP_TIMEOUT = 30.seconds
        private val NUM_GENERATIONS_LEGACY = 4
    }
}