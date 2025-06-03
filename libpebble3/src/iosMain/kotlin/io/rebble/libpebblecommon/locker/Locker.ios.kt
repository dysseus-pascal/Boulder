package io.rebble.libpebblecommon.locker

import io.rebble.libpebblecommon.connection.AppContext
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask

actual fun getLockerPBWCacheDirectory(context: AppContext): Path {
    val fileManager = NSFileManager.defaultManager
    val cachesDirectory = fileManager.URLsForDirectory(NSCachesDirectory, inDomains = NSUserDomainMask).firstOrNull()
        as? NSURL
        ?: throw IllegalStateException("Unable to get caches directory")
    val path = Path(cachesDirectory.path!!, "pbw")
    SystemFileSystem.createDirectories(path, false)
    return path
}