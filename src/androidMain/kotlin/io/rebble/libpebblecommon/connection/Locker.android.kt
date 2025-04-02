package io.rebble.libpebblecommon.connection

import kotlinx.io.files.Path

actual fun getLockerPBWCacheDirectory(context: AppContext): Path {
    return Path(context.context.cacheDir.resolve("pbw").absolutePath)
}