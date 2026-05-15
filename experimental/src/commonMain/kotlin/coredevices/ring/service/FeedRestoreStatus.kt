package coredevices.ring.service

sealed class FeedRestoreStatus {
    data class UploadingPending(val remaining: Int) : FeedRestoreStatus()
    data object FetchingFromCloud : FeedRestoreStatus()
    data class RecordingApplied(val count: Int) : FeedRestoreStatus()
    data class KeyMismatch(val recordingId: String) : FeedRestoreStatus()
    data class IntegrityFailed(val recordingId: String) : FeedRestoreStatus()
    data class Complete(val applied: Int, val total: Int) : FeedRestoreStatus()
    data object NotSignedIn : FeedRestoreStatus()
}