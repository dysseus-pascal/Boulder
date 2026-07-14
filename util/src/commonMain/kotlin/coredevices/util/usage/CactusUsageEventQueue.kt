package coredevices.util.usage

import co.touchlab.kermit.Logger
import coredevices.database.CactusUsageEventDao
import coredevices.database.CactusUsageEventEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

class CactusUsageEventQueue(
    private val dao: CactusUsageEventDao,
    private val uploader: CactusUsageUploader,
) {
    private val logger = Logger.withTag("CactusUsageEventQueue")
    private val channel = Channel<CactusUsageEventEntity>(Channel.UNLIMITED)
    private val uploadMutex = Mutex()

    fun enqueue(event: CactusUsageEventEntity) {
        channel.trySend(event)
    }

    fun startProcessing(scope: CoroutineScope) {
        scope.launch {
            uploadPendingFromDb()

            while (true) {
                dao.insert(channel.receive())
                var collected = 1

                while (collected < MAX_COLLECT_BEFORE_FLUSH) {
                    val next = withTimeoutOrNull(IDLE_TIMEOUT) { channel.receive() }
                    next?.let { dao.insert(it); collected++ } ?: break
                }

                uploadPendingFromDb()
            }
        }
    }

    suspend fun uploadPendingFromDb() = uploadMutex.withLock {
        evictOldestIfOverLimit()
        while (true) {
            val batch = dao.getBatch(UPLOAD_BATCH_SIZE)
            if (batch.isEmpty()) return@withLock
            val ok = uploader.upload(batch)
            if (ok) {
                dao.deleteByIds(batch.map { it.clientEventId })
                logger.d { "Uploaded ${batch.size} usage events" }
            } else {
                logger.w { "Upload failed; will retry later" }
                return@withLock
            }
        }
    }

    private suspend fun evictOldestIfOverLimit() {
        val count = dao.count()
        if (count > MAX_STORED_EVENTS) {
            val excess = count - MAX_STORED_EVENTS
            logger.w { "Usage event DB has $count entries (limit $MAX_STORED_EVENTS), evicting $excess oldest" }
            dao.deleteOldest(excess)
        }
    }

    companion object {
        private val IDLE_TIMEOUT = 10.seconds
        private const val MAX_COLLECT_BEFORE_FLUSH = 50
        private const val UPLOAD_BATCH_SIZE = 100
        private const val MAX_STORED_EVENTS = 1000L
    }
}
