package io.rebble.libpebblecommon.connection

import kotlinx.io.files.Path

actual fun getTempAppPath(appContext: AppContext): Path {
    val cache = appContext.context.cacheDir
    val file = cache.resolve("temp.pbw")
    file.deleteOnExit()
    return Path(file.absolutePath)
}