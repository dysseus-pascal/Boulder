package coredevices.ring.ui.components

import PlatformUiContext

/** Outcome of picking a photo and decoding a QR code from it. */
sealed interface QrPhotoPickResult {
    data class Found(val data: String) : QrPhotoPickResult
    data object Cancelled : QrPhotoPickResult
    data object NoQrFound : QrPhotoPickResult
}

/**
 * Render [data] as a QR code and save it as an image to the user's photo
 * library (camera roll). @return true if the image was saved.
 */
expect suspend fun saveQrCodeToPhotos(
    uiContext: PlatformUiContext,
    data: String,
    fileName: String,
): Boolean

/** Let the user pick an image from their photo library and decode a QR code from it. */
expect suspend fun pickQrCodeFromPhotos(uiContext: PlatformUiContext): QrPhotoPickResult
