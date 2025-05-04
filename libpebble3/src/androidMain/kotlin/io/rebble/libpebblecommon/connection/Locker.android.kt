package io.rebble.libpebblecommon.connection

import kotlinx.io.files.Path

actual fun getLockerPBWCacheDirectory(context: AppContext): Path {
    val dir = context.context.cacheDir.resolve("pbw")
    dir.mkdirs()
    return Path(dir.absolutePath)
}