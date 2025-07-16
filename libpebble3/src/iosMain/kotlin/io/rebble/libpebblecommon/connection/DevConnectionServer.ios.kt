package io.rebble.libpebblecommon.connection

import kotlinx.io.files.Path
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Foundation.NSUserDomainMask

internal actual fun getTempPbwPath(): Path {
    val cacheURL = NSFileManager.defaultManager
        .URLsForDirectory(NSCachesDirectory, NSUserDomainMask)
        .firstOrNull() as NSURL
    val cacheDir = cacheURL.path!!
    val uuid = NSUUID.UUID().UUIDString
    return Path(cacheDir, "devconn-$uuid.pbw")
}