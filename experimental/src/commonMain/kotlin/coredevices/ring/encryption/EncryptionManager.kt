package coredevices.ring.encryption

import PlatformUiContext
import co.touchlab.kermit.Logger
import coredevices.firestore.EncryptionInfo
import coredevices.firestore.UsersDao
import coredevices.ring.database.Preferences
import coredevices.util.Platform
import coredevices.util.isAndroid
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.auth.auth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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

/**
 * Owns all encryption-related state and operations: key generation,
 * cloud-keychain backup/restore, and the on/off switch that controls
 * whether future uploads are encrypted.
 *
 * Enabling encryption is forward-only: it just flips the preference so
 * subsequent uploads are encrypted. Existing cloud data is left as-is.
 *
 * Singleton so the state flows survive Settings ViewModel recreation.
 */
class EncryptionManager(
    private val encryptionKeyManager: EncryptionKeyManager,
    private val usersDao: UsersDao,
    private val preferences: Preferences,
    private val platform: Platform,
) {
    companion object {
        private val logger = Logger.withTag("EncryptionManager")
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

    /** True only if the cloud keychain holds a key matching the local key's
     *  fingerprint. Any failure returns false ("not verified", not an error). */
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
     * Turn on encryption for all future uploads. Existing cloud data is left
     * unencrypted; only newly-uploaded recordings get encrypted.
     *
     * Refuses to flip the switch unless a usable key is actually present, so
     * we never end up uploading recordings nothing can decrypt:
     *  - a key for the current account must exist in the key manager;
     *  - if a fingerprint was recorded for this account, the local key must
     *    match it (otherwise other devices couldn't decrypt these uploads);
     *  - the key must pass a round-trip encrypt/decrypt self-test.
     */
    suspend fun enableEncryption(): EnableEncryptionResult {
        val localKey = withContext(Dispatchers.IO) {
            encryptionKeyManager.getLocalKey(Firebase.auth.currentUser?.email)
        }
        _hasLocalKey.value = localKey != null
        if (localKey == null) {
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
