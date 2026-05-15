package coredevices.ring.service

import co.touchlab.kermit.Logger
import coredevices.indexai.data.entity.RecordingDocument
import coredevices.ring.database.Preferences
import coredevices.ring.database.firestore.dao.FirestoreRecordingsDao
import coredevices.ring.database.room.repository.RecordingRepository
import coredevices.ring.encryption.KeyFingerprintMismatchException
import coredevices.ring.encryption.TamperedException
import coredevices.ring.service.recordings.RecordingProcessingQueue
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.auth.auth
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

/**
 * Restores feed history from Firestore: uploads any locally-queued recordings
 * that haven't reached the cloud yet, then pages through the remote collection
 * and ingests every document into the local database.
 *
 * Singleton so it can be injected anywhere that needs a "sync now" operation
 * (e.g. Settings UI and the encryption migration).
 */
class FeedHistoryRestore(
    private val recordingRepository: RecordingRepository,
    private val firestoreRecordingsDao: FirestoreRecordingsDao,
    private val recordingProcessingQueue: RecordingProcessingQueue,
    private val preferences: Preferences,
) {
    companion object {
        private val logger = Logger.withTag("FeedHistoryRestore")
    }

    /**
     * Emits [FeedRestoreStatus] progress updates and always completes normally.
     * Callers that only need completion (e.g. encryption migration) can
     * `collect { }` and discard the emissions.
     */
    fun restore(): Flow<FeedRestoreStatus> = flow {
        if (Firebase.auth.currentUser == null) {
            logger.w { "Not signed in — cannot restore feed history" }
            emit(FeedRestoreStatus.NotSignedIn)
            return@flow
        }
        uploadPendingRecordings { emit(it) }
        downloadFromCloud { emit(it) }
    }

    /** Uploads locally-queued recordings to Firestore and waits for them to land.
     *  Use this when you need the upload step in isolation (e.g. before a backup). */
    fun uploadPending(): Flow<FeedRestoreStatus> = flow {
        uploadPendingRecordings { emit(it) }
    }

    /** Kicks any locally-queued recordings toward Firestore and waits for them to land. */
    private suspend fun uploadPendingRecordings(onStatus: suspend (FeedRestoreStatus) -> Unit) {
        val recordings = withContext(Dispatchers.IO) { recordingRepository.getAllRecordings().first() }
        val pending = recordings.filter { it.firestoreId == null }
        if (pending.isEmpty()) {
            logger.i { "No pending uploads" }
            return
        }
        logger.i { "Kicking ${pending.size} pending recordings for upload" }
        onStatus(FeedRestoreStatus.UploadingPending(pending.size))

        val pendingIds = pending.map { it.id }.toSet()
        val now = kotlin.time.Clock.System.now()
        withContext(Dispatchers.IO) {
            for (recording in pending) {
                recordingRepository.setRecordingUpdated(recording.id, now)
            }
        }

        // Wait for the push observer to assign firestoreIds — cap at 60s in case
        // it's offline or wedged.
        try {
            withTimeout(60_000) {
                recordingRepository.getAllRecordings()
                    .first { recs ->
                        val stillPending = recs.count { it.id in pendingIds && it.firestoreId == null }
                        if (stillPending > 0) onStatus(FeedRestoreStatus.UploadingPending(stillPending))
                        stillPending == 0
                    }
            }
            logger.i { "All ${pending.size} pending recordings uploaded" }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            logger.w { "Timed out waiting for ${pending.size} pending uploads — moving on" }
        }
    }

    /**
     * Pages through Firestore and ingests every remote recording.
     * Processes each page concurrently; errors (key mismatch, tampered docs) are
     * emitted after each page's batch completes so progress updates remain sequential.
     */
    private suspend fun downloadFromCloud(onStatus: suspend (FeedRestoreStatus) -> Unit) {
        val user = Firebase.auth.currentUser ?: return
        logger.i { "Feed history restore started for ${user.uid} (${user.email})" }
        onStatus(FeedRestoreStatus.FetchingFromCloud)

        var cursor: dev.gitlive.firebase.firestore.DocumentSnapshot? = null
        var totalApplied = 0
        var totalRemote = 0

        while (true) {
            val snapshot = withContext(Dispatchers.IO) { firestoreRecordingsDao.getPaginated(50, cursor) }
            val docs = snapshot.documents
            if (docs.isEmpty()) break
            totalRemote += docs.size

            val mutex = Mutex()
            var pageApplied = 0
            val pageErrors = mutableListOf<FeedRestoreStatus>()

            coroutineScope {
                docs.map { doc ->
                    async {
                        try {
                            val data = doc.data<RecordingDocument>()
                            val before = withContext(Dispatchers.IO) {
                                recordingRepository.getByFirestoreId(doc.id)
                            }
                            try {
                                recordingProcessingQueue.ingestRemoteRecording(doc.id, data)
                            } catch (e: KeyFingerprintMismatchException) {
                                logger.e { "Recording ${doc.id} encrypted with key ${e.expected} but local key is ${e.actual} — restore the original key" }
                                mutex.withLock { pageErrors.add(FeedRestoreStatus.KeyMismatch(doc.id)) }
                                return@async
                            } catch (e: TamperedException) {
                                logger.e(e) { "Recording ${doc.id} failed integrity check" }
                                mutex.withLock { pageErrors.add(FeedRestoreStatus.IntegrityFailed(doc.id)) }
                                return@async
                            }
                            val after = withContext(Dispatchers.IO) {
                                recordingRepository.getByFirestoreId(doc.id)
                            }
                            if (before == null || (after != null && after.updated != before.updated)) {
                                mutex.withLock { pageApplied++ }
                            }
                        } catch (e: Exception) {
                            logger.w(e) { "Skipping recording ${doc.id}: ${e.message}" }
                        }
                    }
                }.awaitAll()
            }

            pageErrors.forEach { onStatus(it) }
            totalApplied += pageApplied
            if (pageApplied > 0) onStatus(FeedRestoreStatus.RecordingApplied(totalApplied))

            logger.i { "Progress: fetched $totalRemote remote, applied $totalApplied" }
            cursor = docs.lastOrNull()
        }

        logger.i { "Feed history restore complete: applied $totalApplied of $totalRemote" }
        preferences.setLastBackupCount(totalRemote)
        onStatus(FeedRestoreStatus.Complete(totalApplied, totalRemote))
    }
}
