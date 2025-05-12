package io.rebble.libpebblecommon.util

import io.rebble.libpebblecommon.connection.AppContext
import kotlinx.io.files.Path

actual fun getTempFilePath(
    appContext: AppContext,
    name: String,
): Path {
    val cache = appContext.context.cacheDir
    val file = cache.resolve(name)
    file.deleteOnExit()
    return Path(file.absolutePath)
}