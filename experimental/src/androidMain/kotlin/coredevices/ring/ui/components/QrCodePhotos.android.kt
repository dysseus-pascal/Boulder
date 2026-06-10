package coredevices.ring.ui.components

import PlatformUiContext
import android.content.ContentResolver
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import co.touchlab.kermit.Logger
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

private val logger = Logger.withTag("QrCodePhotos")

private const val QR_IMAGE_SIZE = 1024

/** Largest dimension we decode picked photos at — QR modules survive this fine. */
private const val MAX_DECODE_SIZE = 2048

actual suspend fun saveQrCodeToPhotos(
    uiContext: PlatformUiContext,
    data: String,
    fileName: String,
): Boolean = withContext(Dispatchers.IO) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        // Pre-Q shared storage writes need WRITE_EXTERNAL_STORAGE, which the app doesn't hold.
        logger.w { "Saving QR to photos unsupported below Android 10" }
        return@withContext false
    }
    try {
        val matrix = QRCodeWriter().encode(data, BarcodeFormat.QR_CODE, QR_IMAGE_SIZE, QR_IMAGE_SIZE)
        val pixels = IntArray(matrix.width * matrix.height) { i ->
            if (matrix.get(i % matrix.width, i / matrix.width)) Color.BLACK else Color.WHITE
        }
        val bitmap = Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)

        val resolver = uiContext.activity.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            values
        ) ?: return@withContext false
        resolver.openOutputStream(uri)?.use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        } ?: return@withContext false
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        true
    } catch (e: Exception) {
        logger.w(e) { "Failed to save QR code to photos" }
        false
    }
}

actual suspend fun pickQrCodeFromPhotos(uiContext: PlatformUiContext): QrPhotoPickResult {
    val registry = uiContext.activity as? ActivityResultRegistryOwner
        ?: error("Activity is not an ActivityResultRegistryOwner")

    val uri: Uri = suspendCancellableCoroutine { cont ->
        val launcher = registry.activityResultRegistry.register(
            "pickQrCodePhoto",
            ActivityResultContracts.PickVisualMedia(),
        ) { result ->
            cont.resume(result)
        }
        launcher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    } ?: return QrPhotoPickResult.Cancelled

    return withContext(Dispatchers.Default) {
        try {
            val bitmap = loadScaledBitmap(uiContext.activity.contentResolver, uri)
                ?: return@withContext QrPhotoPickResult.NoQrFound
            decodeQrCode(bitmap)
                ?.let { QrPhotoPickResult.Found(it) }
                ?: QrPhotoPickResult.NoQrFound
        } catch (e: Exception) {
            logger.w(e) { "Failed to decode QR code from picked photo" }
            QrPhotoPickResult.NoQrFound
        }
    }
}

private fun loadScaledBitmap(resolver: ContentResolver, uri: Uri): Bitmap? {
    // decodeStream always returns null with inJustDecodeBounds — only the
    // stream open can be null-checked; the probe's success is in outWidth/outHeight.
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    val probeStream = resolver.openInputStream(uri) ?: return null
    probeStream.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sampleSize = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sampleSize * 2) >= MAX_DECODE_SIZE) {
        sampleSize *= 2
    }
    val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    return resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
}

private fun decodeQrCode(bitmap: Bitmap): String? {
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
    val hints = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true,
    )
    return try {
        MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source)), hints).text
    } catch (_: Exception) {
        null
    }
}
