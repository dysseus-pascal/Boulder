package io.rebble.libpebblecommon.connection

import kotlinx.io.files.Path
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask

actual fun getTempAppPath(appContext: AppContext): Path {
    val fm = NSFileManager.defaultManager
    val nsUrl = fm.URLsForDirectory(NSCachesDirectory, NSUserDomainMask).first()!! as NSURL
    val path = Path(nsUrl.path!!, "temp.pbw")
    return path
}