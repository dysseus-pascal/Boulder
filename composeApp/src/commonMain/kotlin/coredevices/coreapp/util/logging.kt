package coredevices.coreapp.util

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import coredevices.ExperimentalDevices
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.crashlytics.crashlytics
import io.ktor.utils.io.core.append
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock
import kotlinx.io.Sink
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.okio.asKotlinxIoRawSink
import kotlinx.io.okio.asKotlinxIoRawSource
import kotlinx.io.okio.asOkioSink
import kotlinx.io.okio.asOkioSource
import kotlinx.io.writeString
import okio.deflate
import okio.inflate
import okio.use
import okio.utf8Size
import org.koin.mp.KoinPlatform
import kotlin.time.Duration.Companion.seconds

fun initLogging() {
    Logger.addLogWriter(object : LogWriter() {
        override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
            if (severity != Severity.Verbose) {
                val log = buildString {
                    append("[$tag] $message")
                }
                Firebase.crashlytics.log(log)
            }
            if (severity != Severity.Debug && severity != Severity.Verbose) {
                throwable?.let { Firebase.crashlytics.recordException(it) }
            }
        }
    })
    Logger.addLogWriter(KoinPlatform.getKoin().get<FileLogWriter>())
    try {
        // Cactus logging is handled natively in the vendored SDK
    } catch (e: Exception) {
        Logger.e(e) { "Failed to initialize Cactus logging" }
    }
}

/**
 * Boulder: die Mindeststufe fuer Logcat und Logdatei. Im Release erst ab Info (Nutzerentscheid
 * 03.10.2026): geschaetzt 5'000-20'000 Zeilen am Tag, 70-90 % davon v/d, und Kermit baut eine
 * Zeile unter der Mindeststufe gar nicht erst. Der Debug-Build bleibt bei Verbose, ebenso wer
 * einen Diagnoseschalter der Verbindung einschaltet ([ausfuehrlich]) - die loggen ueber v/d
 * und waeren sonst wirkungslos.
 */
fun logMindeststufe(debugBuild: Boolean, ausfuehrlich: Boolean): Severity =
    if (debugBuild || ausfuehrlich) Severity.Verbose else Severity.Info

expect fun generateDeviceSummaryPlatformDetails(): String

suspend fun generateDeviceSummary(experimentalDevices: ExperimentalDevices): String {
    val deviceSummary = generateDeviceSummaryPlatformDetails()
    val experimentalSummary = withTimeoutOrNull(5.seconds) {
        try {
            experimentalDevices.debugSummary()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            "<RING SUMMARY EXCEPTION: ${e.message}>"
        }
    } ?: "<RING SUMMARY TIMEOUT>"
    return deviceSummary + "\n" + experimentalSummary
}

class FileLogWriter : LogWriter(), AutoCloseable {
    private val logScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val logChannel = Channel<LogEntry>(capacity = 1000)

    private data class LogEntry(
        val severity: Severity,
        val message: String,
        val tag: String,
        val throwable: Throwable?
    )

    private class LogContext(
        val path: Path,
        val backupPath: Path,
        var sink: Sink,
    )

    companion object {
        private const val MAX_LOG_FILE_SIZE = 1024 * 1024 * 1.5 // 1.5MB
    }

    private lateinit var logContext: LogContext
    private val logMutex = Mutex()

    // Boulder: die Groesse von latest.log, mitgezaehlt statt vor jeder Zeile per stat() erfragt.
    // Hier kommt nur an, was Kermit ueber der Mindeststufe durchlaesst: im Release ab Info etwa
    // 500-6'000 Zeilen am Tag (10-30 % von 5'000-20'000), im Debug-Build alle. stat() bleibt fuer
    // den Start und nach jedem Rollover. Nur unter logMutex gelesen und geschrieben.
    private var geschriebeneBytes = 0L

    init {
        startLogProcessing()
    }

    private fun rollover(path: Path) {
        val backupTime = Clock.System.now()
        // Rename the existing log file to a backup
        val backupPath = Path(getLogsCacheDir() + "/previous.log.gz")
        try {
            SystemFileSystem.sink(backupPath).buffered().asOkioSink().deflate().use { gzipSink ->
                SystemFileSystem.source(path).buffered().use { source ->
                    source.transferTo(gzipSink.asKotlinxIoRawSink())
                }
            }
        } catch (_: Exception) {
            // Best-effort backup; don't crash the log writer if compression fails
        }
        // Create a new log file
        SystemFileSystem.sink(path, append = false).buffered().use { sink ->
            sink.writeString("Log file rolled over at $backupTime\n")
            sink.flush()
        }
    }

    private fun startLogProcessing() {
        logScope.launch {
            val path = Path(getLogsCacheDir() + "/latest.log")
            logMutex.withLock {
                val backupPath = Path(getLogsCacheDir() + "/previous.log.gz")
                SystemFileSystem.createDirectories(Path(getLogsCacheDir()))
                // Roll over log file if it exists and exceeds the size limit
                SystemFileSystem.metadataOrNull(path)?.let {
                    if (it.size > MAX_LOG_FILE_SIZE) {
                        rollover(path)
                    }
                }
                val sink = SystemFileSystem.sink(path, true).buffered()
                logContext = LogContext(path, backupPath, sink)
                geschriebeneBytes = SystemFileSystem.metadataOrNull(path)?.size ?: 0L
            }
            try {
                for (logEntry in logChannel) { // Consumes from the channel
                    if (!logScope.isActive) break // Exit if scope is cancelled

                    logMutex.withLock { // Serialize access to the sink
                        if (geschriebeneBytes > MAX_LOG_FILE_SIZE) {
                            logContext.sink.flush()
                            logContext.sink.closeQuietly()
                            try {
                                rollover(path)
                            } finally {
                                logContext.sink = SystemFileSystem.sink(path, true).buffered()
                                geschriebeneBytes = SystemFileSystem.metadataOrNull(path)?.size ?: 0L
                            }
                        }
                        writeLogEntryToFile(logEntry)
                    }
                }
            } finally {
                // This block executes when the channel is closed or the scope is cancelled
                logMutex.withLock {
                    logContext.sink.closeQuietly()
                }
            }
        }
    }

    override fun close() {
        logScope.cancel()
        logChannel.close()
        logContext.sink.closeQuietly()
    }

    override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
        val offerResult = logChannel.trySend(LogEntry(severity, message, tag, throwable))
        if (!offerResult.isSuccess) {
            // Handle failure to enqueue (e.g., if channel is closed or full with a bounded channel)
            println("Failed to enqueue log message (channel closed or full): $message")
        }
    }

    private fun writeLogEntryToFile(logEntry: LogEntry) {
        val severityChar = when (logEntry.severity) {
            Severity.Verbose -> 'V'
            Severity.Debug -> 'D'
            Severity.Info -> 'I'
            Severity.Warn -> 'W'
            Severity.Error -> 'E'
            Severity.Assert -> 'A'
        }
        val timestamp = Clock.System.now()
        val tagText = logEntry.tag.ifBlank { "Global" }
        geschriebeneBytes += logContext.sink.appendGezaehlt("$timestamp [$severityChar] ${tagText}: ${logEntry.message}\n")
        logEntry.throwable?.let {
            geschriebeneBytes += logContext.sink.appendGezaehlt(it.stackTraceToString() + "\n")
        }
    }

    /**
     * Only for calling when the app is dying, and we really need to log the exception.
     */
    fun logBlockingAndFlush(
        severity: Severity,
        message: String,
        tag: String,
        throwable: Throwable?
    ) {
        runBlocking(logScope.coroutineContext) {
            logMutex.withLock {
                writeLogEntryToFile(LogEntry(severity, message, tag, throwable))
                logContext.sink.flush()
            }
        }
    }

    suspend fun dumpLogs(): Path {
        return withContext(logScope.coroutineContext) {
            logMutex.withLock {
                logContext.sink.flush()
                logContext.sink.closeQuietly()
                val dumpFile = Path(getLogsCacheDir() + "/upload.log")
                try {
                    SystemFileSystem.delete(dumpFile, mustExist = false)
                    if (SystemFileSystem.exists(logContext.backupPath)) {
                        SystemFileSystem.source(logContext.backupPath).buffered().asOkioSource().inflate().use { source ->
                            SystemFileSystem.sink(dumpFile, append = false).buffered().use { sink ->
                                sink.transferFrom(source.asKotlinxIoRawSource())
                                sink.writeString("\n")
                            }
                        }
                    }
                    SystemFileSystem.source(logContext.path).buffered().use { source ->
                        SystemFileSystem.sink(dumpFile, append = true).buffered().use { sink ->
                            sink.transferFrom(source)
                        }
                    }
                } catch (e: Exception) {
                    Logger.e(e) { "Error reading log file for bug report" }
                    try {
                        SystemFileSystem.sink(dumpFile, append = false).buffered().use { sink ->
                            sink.append("Error reading log file for bug report: $e / ${e.stackTraceToString()}")
                        }
                    } catch (_: Exception) {}
                } finally {
                    logContext.sink = SystemFileSystem.sink(logContext.path, true).buffered()
                }
                return@withContext dumpFile
            }
        }
    }
}

/**
 * Boulder: haengt [text] an und gibt die geschriebenen Bytes zurueck - die UTF-8-Laenge, die
 * append() schreibt (ein einzelnes Surrogat als ein "?"), so dass der Zaehler der Datei folgt.
 */
internal fun Sink.appendGezaehlt(text: String): Long {
    append(text)
    return text.utf8Size()
}

fun AutoCloseable.closeQuietly() = try {
    close()
} catch (e: Exception) {
}

expect fun getLogsCacheDir(): String