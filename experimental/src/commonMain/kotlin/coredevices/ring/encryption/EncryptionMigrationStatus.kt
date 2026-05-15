package coredevices.ring.encryption

sealed class EncryptionMigrationStatus {
    data object NoKey : EncryptionMigrationStatus()
    data object SyncingFromCloud : EncryptionMigrationStatus()
    data object CachingAudio : EncryptionMigrationStatus()
    data class EncryptingAudio(val done: Int, val total: Int) : EncryptionMigrationStatus()
    data class EncryptingDocuments(val done: Int, val total: Int) : EncryptionMigrationStatus()
    data class Complete(val docs: Int, val audioFiles: Int) : EncryptionMigrationStatus()
    data class Failed(val cause: Exception) : EncryptionMigrationStatus()
}