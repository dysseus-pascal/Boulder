package coredevices.ring.encryption

import PlatformUiContext
import co.touchlab.kermit.Logger
import coredevices.firestore.EncryptionInfo
import coredevices.firestore.UsersDao
import coredevices.ring.database.Preferences
import coredevices.ring.service.RecordingBackgroundScope
import coredevices.util.Platform
import coredevices.util.isAndroid
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.auth.auth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import kotlin.time.Clock

/** Outcome of [EncryptionManager.enableEncryption]. */
sealed interface EnableEncryptionResult {
    data object Enabled : EnableEncryptionResult
    /** No key in the key manager for the current account. */
    data object NoLocalKey : EnableEncryptionResult
    /** Local key doesn't match the fingerprint recorded for this account. */
    data class KeyFingerprintMismatch(
        val localFingerprint: String,
        val expectedFingerprint: String,
    ) : EnableEncryptionResult
    /** Local key is present but failed an encrypt/decrypt self-test. */
    data class KeyUnusable(val reason: String) : EnableEncryptionResult
}

/** State of the encryption key for the current account. */
enum class KeyStorageStatus {
    /** No key on this device, none recorded for the account. */
    NoKeyStored,
    /** Not on this device, but a fingerprint is recorded — a key exists
     *  elsewhere and should be restored, not regenerated. */
    KeyGeneratedBefore,
    /** A key for the current account is on this device. */
    KeyLocallyAvailable,
}

/**
 * Owns encryption state and operations: key generation, cloud-keychain
 * backup/restore, and the on/off switch for encrypting future uploads.
 * Enabling is forward-only — existing cloud data is left as-is.
 */
class EncryptionManager(
    private val encryptionKeyManager: EncryptionKeyManager,
    private val usersDao: UsersDao,
    private val preferences: Preferences,
    private val platform: Platform,
    private val scope: RecordingBackgroundScope,
) {
    companion object {
        private val logger = Logger.withTag("EncryptionManager")
    }
    // --- Key management state ---

    /** Bumped after the local key store changes (generate/restore). The
     *  local key is a suspend one-shot, not a flow, so this re-drives
     *  [keyStorageStatus] to re-read it. */
    private val keyStoreRevision = MutableStateFlow(0)

    /** Derived from account email, recorded fingerprints (prefs +
     *  Firestore) and local key presence, so it can't go stale. */
    val keyStorageStatus: StateFlow<KeyStorageStatus> =
        combine(
            Firebase.auth.authStateChanged
                .map { it?.email }
                .onStart { emit(Firebase.auth.currentUser?.email) }
                .distinctUntilChanged(),
            preferences.encryptionKeyFingerprint,
            usersDao.user
                .map { it?.user?.encryption?.keyFingerprint }
                .onStart { emit(null) }
                .catch { e ->
                    logger.w(e) { "Could not read encryption info from Firestore" }
                    emit(null)
                },
            keyStoreRevision,
        ) { email, prefFingerprint, firestoreFingerprint, _ ->
            val hasLocalKey = withContext(Dispatchers.IO) {
                encryptionKeyManager.getLocalKey(email) != null
            }
            when {
                hasLocalKey -> KeyStorageStatus.KeyLocallyAvailable
                prefFingerprint != null || firestoreFingerprint != null ->
                    KeyStorageStatus.KeyGeneratedBefore
                else -> KeyStorageStatus.NoKeyStored
            }
        }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), KeyStorageStatus.NoKeyStored)

    private val _generatedKey = MutableStateFlow<String?>(null)
    val generatedKey = _generatedKey.asStateFlow()

    val useEncryption = preferences.useEncryption

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

        keyStoreRevision.value++
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
            keyStoreRevision.value++
            logger.i { "Key restored from cloud keychain" }
        }
    }

    fun clearGeneratedKey() { _generatedKey.value = null }

    /** True only if the cloud keychain holds a key matching the local
     *  key's fingerprint. Any failure returns false (not an error). */
    suspend fun isLocalKeyBackedUpToCloud(uiContext: PlatformUiContext): Boolean {
        val localKey = withContext(Dispatchers.IO) {
            encryptionKeyManager.getLocalKey(Firebase.auth.currentUser?.email)
        }
        if (localKey == null) {
            logger.w { "Cloud backup check: no local key" }
            return false
        }
        val cloudKey = try {
            encryptionKeyManager.readFromCloudKeychain(uiContext)
        } catch (e: Exception) {
            logger.w(e) { "Cloud backup check: could not read cloud keychain" }
            null
        }
        if (cloudKey == null) return false
        val matches = AesCbcHmacCrypto.keyFingerprint(cloudKey) ==
            AesCbcHmacCrypto.keyFingerprint(localKey)
        if (!matches) {
            logger.w { "Cloud backup check: cloud key fingerprint differs from local key" }
        }
        return matches
    }

    /**
     * Turn on encryption for future uploads (existing cloud data is left
     * unencrypted). Refuses unless a usable key is present, so we never
     * upload recordings nothing can decrypt: the account key must exist,
     * match any recorded fingerprint, and pass a round-trip self-test.
     */
    suspend fun enableEncryption(): EnableEncryptionResult {
        val localKey = withContext(Dispatchers.IO) {
            encryptionKeyManager.getLocalKey(Firebase.auth.currentUser?.email)
        }
        if (localKey == null) {
            keyStoreRevision.value++
            logger.w { "Refusing to enable encryption: no local key in key manager" }
            return EnableEncryptionResult.NoLocalKey
        }

        val localFingerprint = AesCbcHmacCrypto.keyFingerprint(localKey)
        val expectedFingerprint = preferences.encryptionKeyFingerprint.value
        if (expectedFingerprint != null && expectedFingerprint != localFingerprint) {
            logger.w {
                "Refusing to enable encryption: local key fingerprint " +
                    "$localFingerprint != expected $expectedFingerprint"
            }
            return EnableEncryptionResult.KeyFingerprintMismatch(
                localFingerprint = localFingerprint,
                expectedFingerprint = expectedFingerprint,
            )
        }

        try {
            val probe = "enc-probe".encodeToByteArray()
            val roundTripped = AesCbcHmacCrypto.decrypt(
                AesCbcHmacCrypto.encrypt(probe, localKey), localKey
            )
            require(roundTripped.contentEquals(probe)) { "round-trip mismatch" }
        } catch (e: Exception) {
            logger.w(e) { "Refusing to enable encryption: key failed self-test" }
            return EnableEncryptionResult.KeyUnusable(e.message ?: "key self-test failed")
        }

        preferences.setUseEncryption(true)
        logger.i { "Encryption enabled — future uploads will be encrypted" }
        return EnableEncryptionResult.Enabled
    }

    fun disableEncryption() {
        preferences.setUseEncryption(false)
    }
}
