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
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.timeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.Instant
import kotlinx.datetime.format
import kotlinx.datetime.format.DateTimeComponents
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
        val tempLogFile = getTempFilePath(appContext, "logs-${transport.identifier.asString}")
        SystemFileSystem.sink(tempLogFile).use { sink ->
            sink.asByteWriteChannel().use {
                writeLine("# Device logs:")
                for (generation in 0..<NUM_GENERATIONS_LEGACY) {
                    requestLogGeneration(generation, this)
                }
            }
        }
        logger.d { "gatherLogs done" }
        return tempLogFile
    }

    private suspend fun requestLogGeneration(
        generation: Int,
        writeChannel: ByteWriteChannel,
    ) {
        logger.d { "requestLogGeneration: $generation" }
        writeChannel.writeLine("=== Generation: $generation ===")
        val cookie = randomCookie()
        try {
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
                }
                .timeout(LOG_RECEIVE_TIMEOUT)
                .collect {
                    if (it is LogDump.LogLine) {
                        val level = LogLevel.fromCode(it.level.get()).str
                        val instant = Instant.fromEpochSeconds(it.timestamp.get().toLong())
                        val timestamp =
                            instant.format(DateTimeComponents.Formats.ISO_DATE_TIME_OFFSET)
                        val filename = it.filename.get()
                        val lineNumber = it.line.get()
                        val message = it.messageText.get()
                        val logString = "$level $timestamp $filename:$lineNumber> $message"
                        writeChannel.writeLine(logString)
                    }
                }
        } catch (e: TimeoutCancellationException) {
            logger.w { "Timeout receiving logs for generation $generation" }
            writeChannel.writeLine("!!! Timeout receiving logs for this generation !!!")
        } finally {
            logger.d { "finished generation $generation" }
        }
    }

    companion object {
        private val LOG_RECEIVE_TIMEOUT = 5.seconds
        private val NUM_GENERATIONS_LEGACY = 4
    }
}

suspend fun ByteWriteChannel.writeLine(line: String) {
    writeString("$line\n")
}

enum class LogLevel(val code: UByte, val str: String) {
    Always(0u, "*"),
    Error(1u, "E"),
    Warning(50u, "W"),
    Info(100u, "I"),
    Debug(200u, "D"),
    Verbose(255u, "V"),
    Unknown(254u, "?"),
    ;

    companion object {
        fun fromCode(code: UByte): LogLevel = entries.firstOrNull { it.code == code } ?: Unknown
    }
}
