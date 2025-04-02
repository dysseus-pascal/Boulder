package io.rebble.libpebblecommon.connection

import kotlinx.io.files.Path
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask

actual fun getLockerPBWCacheDirectory(context: AppContext): Path {
    val fileManager = NSFileManager.defaultManager
    val cachesDirectory = fileManager.URLsForDirectory(NSCachesDirectory, inDomains = NSUserDomainMask).firstOrNull()
        as? NSURL
        ?: throw IllegalStateException("Unable to get caches directory")
    return Path(cachesDirectory.path!!, "pbw")
}