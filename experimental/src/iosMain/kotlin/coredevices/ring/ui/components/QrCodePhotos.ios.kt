package coredevices.ring.ui.components

import PlatformUiContext
import co.touchlab.kermit.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.CoreGraphics.CGAffineTransformMakeScale
import platform.CoreImage.CIContext
import platform.CoreImage.CIDetector
import platform.CoreImage.CIDetectorAccuracy
import platform.CoreImage.CIDetectorAccuracyHigh
import platform.CoreImage.CIDetectorTypeQRCode
import platform.CoreImage.CIFilter
import platform.CoreImage.CIImage
import platform.CoreImage.CIQRCodeFeature
import platform.CoreImage.createCGImage
import platform.CoreImage.filterWithName
import platform.Foundation.NSData
import platform.Foundation.NSItemProvider
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.dataUsingEncoding
import platform.Foundation.setValue
import platform.Photos.PHAssetChangeRequest
import platform.Photos.PHPhotoLibrary
import platform.PhotosUI.PHPickerConfiguration
import platform.PhotosUI.PHPickerFilter
import platform.PhotosUI.PHPickerResult
import platform.PhotosUI.PHPickerViewController
import platform.PhotosUI.PHPickerViewControllerDelegateProtocol
import platform.UIKit.UIImage
import platform.UIKit.UIScreen
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowLevelAlert
import platform.darwin.NSObject
import kotlin.coroutines.resume

private val logger = Logger.withTag("QrCodePhotos")

/** UIKit delegate properties are weak; hold the picker delegate until it fires. */
private var pickerDelegateRef: NSObject? = null

/** Window hosting the picker, kept until it's dismissed. */
private var pickerWindowRef: UIWindow? = null

actual suspend fun saveQrCodeToPhotos(
    uiContext: PlatformUiContext,
    data: String,
    fileName: String,
): Boolean {
    val image = withContext(Dispatchers.Default) { generateQrImage(data) }
    if (image == null) {
        logger.w { "Failed to render QR code image" }
        return false
    }
    return suspendCancellableCoroutine { cont ->
        PHPhotoLibrary.sharedPhotoLibrary().performChanges(
            changeBlock = {
                PHAssetChangeRequest.creationRequestForAssetFromImage(image)
            },
            completionHandler = { success, error ->
                if (!success) {
                    logger.w { "Failed to save QR code to photos: ${error?.localizedDescription}" }
                }
                cont.resume(success)
            }
        )
    }
}

actual suspend fun pickQrCodeFromPhotos(uiContext: PlatformUiContext): QrPhotoPickResult {
    val imageData: NSData = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val configuration = PHPickerConfiguration().apply {
                filter = PHPickerFilter.imagesFilter
                selectionLimit = 1
            }
            val picker = PHPickerViewController(configuration = configuration)
            val mainWindow = uiContext.viewController.view.window
            val delegate = object : NSObject(), PHPickerViewControllerDelegateProtocol {
                override fun picker(
                    picker: PHPickerViewController,
                    didFinishPicking: List<*>
                ) {
                    pickerDelegateRef = null
                    picker.dismissViewControllerAnimated(true) {
                        pickerWindowRef?.hidden = true
                        pickerWindowRef = null
                        mainWindow?.makeKeyWindow()
                    }
                    val provider: NSItemProvider? =
                        (didFinishPicking.firstOrNull() as? PHPickerResult)?.itemProvider
                    if (provider == null) {
                        cont.resume(null)
                        return
                    }
                    provider.loadDataRepresentationForTypeIdentifier("public.image") { data, error ->
                        if (data == null) {
                            logger.w { "Failed to load picked image: ${error?.localizedDescription}" }
                        }
                        cont.resume(data)
                    }
                }
            }
            pickerDelegateRef = delegate
            picker.delegate = delegate
            // Compose draws its dialogs above the main window's content, so a
            // picker presented from the app's view controller ends up underneath
            // them. Host it in a dedicated window above the dialog layer instead.
            val host = UIViewController()
            val window = UIWindow(frame = UIScreen.mainScreen.bounds)
            mainWindow?.windowScene?.let { window.windowScene = it }
            window.windowLevel = UIWindowLevelAlert + 1
            window.rootViewController = host
            pickerWindowRef = window
            window.makeKeyAndVisible()
            host.presentViewController(picker, animated = true, completion = null)
        }
    } ?: return QrPhotoPickResult.Cancelled

    return withContext(Dispatchers.Default) {
        decodeQrCode(imageData)
            ?.let { QrPhotoPickResult.Found(it) }
            ?: QrPhotoPickResult.NoQrFound
    }
}

internal fun generateQrImage(data: String): UIImage? {
    @Suppress("CAST_NEVER_SUCCEEDS")
    val message = (data as NSString).dataUsingEncoding(NSUTF8StringEncoding) ?: return null
    val filter = CIFilter.filterWithName("CIQRCodeGenerator") ?: return null
    filter.setValue(message, forKey = "inputMessage")
    filter.setValue("M", forKey = "inputCorrectionLevel")
    val output = filter.outputImage ?: return null
    // The generator renders 1pt per module; scale up so the saved image is crisp.
    val scaled = output.imageByApplyingTransform(CGAffineTransformMakeScale(12.0, 12.0))
    val cgImage = CIContext().createCGImage(scaled, fromRect = scaled.extent) ?: return null
    return UIImage.imageWithCGImage(cgImage)
}

private fun decodeQrCode(imageData: NSData): String? {
    val ciImage = CIImage.imageWithData(imageData) ?: return null
    val detector = CIDetector.detectorOfType(
        CIDetectorTypeQRCode,
        context = null,
        options = mapOf<Any?, Any>(CIDetectorAccuracy to CIDetectorAccuracyHigh)
    ) ?: return null
    return detector.featuresInImage(ciImage)
        .filterIsInstance<CIQRCodeFeature>()
        .firstOrNull()
        ?.messageString
}
