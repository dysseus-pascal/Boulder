package coredevices.ring.encryption

import PlatformUiContext
import co.touchlab.kermit.Logger
import coredevices.firestore.EncryptionInfo
import coredevices.firestore.UsersDao
import coredevices.indexai.data.entity.LocalRecording
import coredevices.indexai.data.entity.RecordingDocument
import coredevices.indexai.data.entity.RecordingEntry
import coredevices.indexai.database.dao.ConversationMessageDao
import coredevices.indexai.database.dao.RecordingEntryDao
import coredevices.ring.database.Preferences
import coredevices.ring.database.firestore.dao.FirestoreRecordingsDao
import coredevices.ring.database.room.repository.RecordingRepository
import coredevices.ring.service.FeedHistoryRestore
import coredevices.ring.storage.RecordingStorage
import coredevices.util.Platform
import coredevices.util.isAndroid
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.auth.auth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlin.time.Clock

/**
 * Owns all encryption-related state and operations: key generation,
 * cloud-keychain backup/restore, and the in-place migration that encrypts
 * every local audio file + Firestore document when the user enables
 * encryption.
 *
 * Singleton so the state flows survive Settings ViewModel recreation
 * (e.g. across configuration changes during a long migration).
 */
class EncryptionManager(
    private val encryptionKeyManager: EncryptionKeyManager,
    private val documentEncryptor: DocumentEncryptor,
    private val recordingRepository: RecordingRepository,
    private val recordingEntryDao: RecordingEntryDao,
    private val conversationMessageDao: ConversationMessageDao,
    private val firestoreRecordingsDao: FirestoreRecordingsDao,
    private val recordingStorage: RecordingStorage,
    private val usersDao: UsersDao,
    private val preferences: Preferences,
    private val platform: Platform,
    private val feedHistoryRestore: FeedHistoryRestore,
) {
    companion object {
        private val logger = Logger.withTag("EncryptionManager")
        private val migrationLog = Logger.withTag("EncryptionMigration")
    }
    // --- Key management state ---

    private val _hasLocalKey = MutableStateFlow(false)
    val hasLocalKey = _hasLocalKey.asStateFlow()
    private val _generatedKey = MutableStateFlow<String?>(null)
    val generatedKey = _generatedKey.asStateFlow()

    val useEncryption = preferences.useEncryption

    suspend fun checkLocalKey() {
        val key = withContext(Dispatchers.IO) { encryptionKeyManager.getLocalKey(Firebase.auth.currentUser?.email) }
        _hasLocalKey.value = key != null
    }

    suspend fun generateAndStoreKey(uiContext: PlatformUiContext) {
        val keyResult = encryptionKeyManager.generateKey()

        val email = Firebase.auth.currentUser?.email ?: "unknown"
        withContext(Dispatchers.IO) {
            encryptionKeyManager.saveKeyLocally(keyResult.keyBase64, email)
        }

        var backupLocation = "local_only"
        try {
            encryptionKeyManager.saveToCloudKeychain(uiContext, keyResult.keyBase64)
            backupLocation = if (platform.isAndroid) "google_password_manager" else "icloud_keychain"
        } catch (e: Exception) {
            logger.w(e) { "Cloud keychain save failed (key still saved locally)" }
        }

        val deviceName = platform.deviceModelName

        val encryptionInfo = EncryptionInfo(
            keyFingerprint = keyResult.fingerprint,
            createdAt = Clock.System.now().toString(),
            keyBackupLocation = backupLocation,
            keyCreationDevice = deviceName
        )

        withContext(Dispatchers.IO) {
            usersDao.updateEncryptionInfo(encryptionInfo)
            preferences.setEncryptionKeyFingerprint(keyResult.fingerprint)
        }

        _hasLocalKey.value = true
        _generatedKey.value = keyResult.keyBase64
        logger.i { "Key generated, fingerprint=${keyResult.fingerprint}, backup=$backupLocation" }
    }

    suspend fun readKeyFromCloudKeychain(uiContext: PlatformUiContext) {
        val key = encryptionKeyManager.readFromCloudKeychain(uiContext)
        if (key != null) {
            val email = Firebase.auth.currentUser?.email ?: "unknown"
            withContext(Dispatchers.IO) {
                encryptionKeyManager.saveKeyLocally(key, email)
            }
            _hasLocalKey.value = true
            logger.i { "Key restored from cloud keychain" }
        }
    }

    fun clearGeneratedKey() { _generatedKey.value = null }

    /**
     * Enable encryption: syncs all remote recordings locally via [FeedHistoryRestore],
     * then encrypts all local audio + Firestore docs in place.
     *
     * Emits [EncryptionMigrationStatus] progress updates; always completes
     * normally (terminal failures are emitted as [EncryptionMigrationStatus.Failed]).
     */
    fun enableEncryption(): Flow<EncryptionMigrationStatus> = flow {
        try {
            val key = withContext(Dispatchers.IO) { encryptionKeyManager.getLocalKey() }
            if (key == null) {
                migrationLog.w { "No local encryption key found" }
                emit(EncryptionMigrationStatus.NoKey)
                return@flow
            }

            migrationLog.i { "Syncing recordings from cloud" }
            emit(EncryptionMigrationStatus.SyncingFromCloud)
            feedHistoryRestore.restore().collect { }

            migrationLog.i { "Caching audio files locally" }
            emit(EncryptionMigrationStatus.CachingAudio)
            val allRecordings = withContext(Dispatchers.IO) {
                recordingRepository.getAllRecordings().first()
            }
            val audioIds = collectCacheableAudioIds(allRecordings)
            migrationLog.i { "Cached ${audioIds.size} audio files locally" }

            preferences.setUseEncryption(true)

            migrationLog.i { "Encrypting ${audioIds.size} audio files" }
            val audioEncrypted = encryptAudioFiles(audioIds, key) { emit(it) }
            migrationLog.i { "Encrypted $audioEncrypted/${audioIds.size} audio files" }

            migrationLog.i { "Encrypting ${allRecordings.size} documents" }
            val docsEncrypted = encryptDocuments(allRecordings, key) { emit(it) }

            migrationLog.i { "Encryption migration complete: $docsEncrypted docs, $audioEncrypted audio files" }
            emit(EncryptionMigrationStatus.Complete(docsEncrypted, audioEncrypted))
        } catch (e: Exception) {
            migrationLog.e(e) { "Encryption migration failed" }
            emit(EncryptionMigrationStatus.Failed(e))
        }
    }

    private suspend fun collectCacheableAudioIds(recordings: List<LocalRecording>): List<String> {
        val audioIds = mutableListOf<String>()
        for (recording in recordings) {
            val entries = withContext(Dispatchers.IO) {
                recordingEntryDao.getEntriesForRecording(recording.id).first()
            }
            for (entry in entries) {
                val fileName = entry.fileName ?: continue
                for (variant in listOf(fileName, "$fileName-clean")) {
                    try {
                        val (source, _) = withContext(Dispatchers.IO) {
                            recordingStorage.openRecordingSource(variant)
                        }
                        source.close()
                        audioIds.add(variant)
                    } catch (e: Exception) {
                        migrationLog.w { "Could not cache audio $variant: ${e.message}" }
                    }
                }
            }
        }
        return audioIds
    }

    private suspend fun encryptAudioFiles(
        audioIds: List<String>,
        key: String,
        onProgress: suspend (EncryptionMigrationStatus.EncryptingAudio) -> Unit,
    ): Int {
        var encrypted = 0
        for (audioId in audioIds) {
            try {
                val success = withContext(Dispatchers.IO) {
                    recordingStorage.encryptAndReuploadAudio(audioId, key)
                }
                if (success) encrypted++
                onProgress(EncryptionMigrationStatus.EncryptingAudio(encrypted, audioIds.size))
            } catch (e: Exception) {
                migrationLog.w(e) { "Failed to encrypt audio $audioId" }
            }
        }
        return encrypted
    }

    // We overwrite via set() rather than addRecording() so the firestoreId stays stable —
    // reassigning firestoreIds would orphan items referencing this recording via sourceRecordingId.
    private suspend fun encryptDocuments(
        recordings: List<LocalRecording>,
        key: String,
        onProgress: suspend (EncryptionMigrationStatus.EncryptingDocuments) -> Unit,
    ): Int {
        var uploaded = 0
        for (recording in recordings) {
            val firestoreId = recording.firestoreId
            if (firestoreId.isNullOrBlank()) {
                // Legacy row never uploaded — the upload observer will pick it up pre-encrypted.
                migrationLog.w { "Skipping recording ${recording.id}: no firestoreId" }
                continue
            }
            try {
                val entries = withContext(Dispatchers.IO) {
                    recordingEntryDao.getEntriesForRecording(recording.id).first()
                }
                val messages = withContext(Dispatchers.IO) {
                    conversationMessageDao.getMessagesForRecording(recording.id).first()
                }
                val preservedMetadata = try {
                    withContext(Dispatchers.IO) {
                        firestoreRecordingsDao.getRecording(firestoreId).get()
                            .data<RecordingDocument>().metadata
                    }
                } catch (_: Exception) { null }
                var doc = recording.toDocument(
                    entries = entries.map { entry ->
                        RecordingEntry(
                            timestamp = entry.timestamp,
                            fileName = entry.fileName,
                            status = entry.status,
                            transcription = entry.transcription,
                            transcribedUsingModel = entry.transcribedUsingModel,
                            error = entry.error,
                            ringTransferInfo = entry.ringTransferInfo,
                            userMessageId = entry.userMessageId
                        )
                    },
                    messages = messages.map { it.document },
                    metadata = preservedMetadata,
                )
                doc = documentEncryptor.encryptDocument(doc, key)
                withContext(Dispatchers.IO) {
                    firestoreRecordingsDao.setRecording(firestoreId, doc)
                }
                uploaded++
                onProgress(EncryptionMigrationStatus.EncryptingDocuments(uploaded, recordings.size))
            } catch (e: Exception) {
                migrationLog.w(e) { "Failed to re-upload recording ${recording.id}" }
            }
        }
        return uploaded
    }

    fun disableEncryption() {
        preferences.setUseEncryption(false)
    }
}
