package coredevices.ring.ui.viewmodel

import PlatformUiContext
import kotlinx.io.files.Path
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask
import toNSURL

actual suspend fun writeToDownloads(uiContext: PlatformUiContext, path: Path) {
    val downloadsDir = NSFileManager.defaultManager.URLForDirectory(
        directory = NSDocumentDirectory,
        inDomain = NSUserDomainMask,
        appropriateForURL = null,
        create = false,
        error = null
    ) ?: return
    val destinationURL = downloadsDir.URLByAppendingPathComponent(path.name, isDirectory = false)!!
    NSFileManager.defaultManager.copyItemAtURL(path.toNSURL(), destinationURL, error = null)
}