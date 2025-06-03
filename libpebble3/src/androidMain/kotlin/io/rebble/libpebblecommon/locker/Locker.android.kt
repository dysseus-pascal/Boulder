package io.rebble.libpebblecommon.locker

import io.rebble.libpebblecommon.connection.AppContext
import kotlinx.io.files.Path

actual fun getLockerPBWCacheDirectory(context: AppContext): Path {
    val dir = context.context.cacheDir.resolve("pbw")
    dir.mkdirs()
    return Path(dir.absolutePath)
}