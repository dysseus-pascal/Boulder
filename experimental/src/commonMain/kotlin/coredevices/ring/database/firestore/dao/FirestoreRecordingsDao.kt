package coredevices.ring.database.firestore.dao

import coredevices.firestore.CollectionDao
import coredevices.indexai.data.entity.RecordingDocument
import dev.gitlive.firebase.firestore.Direction
import dev.gitlive.firebase.firestore.DocumentReference
import dev.gitlive.firebase.firestore.DocumentSnapshot
import dev.gitlive.firebase.firestore.FirebaseFirestore
import dev.gitlive.firebase.firestore.QuerySnapshot
import dev.gitlive.firebase.firestore.Source
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlin.time.Instant

class FirestoreRecordingsDao(dbProvider: () -> FirebaseFirestore): CollectionDao("recordings", dbProvider) {
    private val collection get() = authenticatedId?.let { db.collection("$it/recordings") }
        ?: throw IllegalStateException("Not authenticated — cannot access recordings")

    suspend fun addRecording(
        recording: RecordingDocument
    ): DocumentReference {
        return collection.add(recording)
    }

    suspend fun setRecording(id: String, recording: RecordingDocument) {
        collection.document(id).set(recording)
    }

    fun changesFlow(): Flow<QuerySnapshot> {
        return collection.snapshots
    }

    suspend fun recordingsSince(since: Instant): QuerySnapshot {
        return collection
            .where { "updated" greaterThan since.toEpochMilliseconds() }
            .get()
    }

    suspend fun getPaginated(limit: Int, startAfter: DocumentSnapshot? = null, source: Source = Source.DEFAULT): QuerySnapshot {
        return collection
            .orderBy("timestamp", Direction.DESCENDING)
            .limit(limit)
            .let { if (startAfter != null) it.startAfter(startAfter) else it }
            .get(source)
    }

    fun getRecording(id: String): DocumentReference {
        return collection.document(id)
    }

    suspend fun getCount(): Int {
        var count = 0
        var cursor: DocumentSnapshot? = null
        while (true) {
            val snapshot = getPaginated(500, cursor)
            val docs = snapshot.documents
            if (docs.isEmpty()) break
            count += docs.size
            cursor = docs.lastOrNull()
        }
        return count
    }

    suspend fun hasAnyRecordings(): Boolean {
        val snapshot = getPaginated(1)
        return snapshot.documents.isNotEmpty()
    }

    suspend fun deleteRecordingsByIds(ids: List<String>) {
        for (chunk in ids.chunked(500)) {
            val batch = db.batch()
            for (id in chunk) {
                batch.delete(collection.document(id))
            }
            batch.commit()
        }
    }

    suspend fun deleteAllRecordings() {
        while (true) {
            val snapshot = getPaginated(500)
            val docs = snapshot.documents
            if (docs.isEmpty()) break
            val batch = db.batch()
            for (doc in docs) {
                batch.delete(collection.document(doc.id))
            }
            batch.commit()
        }
    }
}