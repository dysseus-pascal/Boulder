package io.rebble.libpebblecommon.connection

import kotlinx.io.files.Path

actual fun getTestAppPath(appContext: AppContext): Path {
    val cache = appContext.context.cacheDir
    return Path(cache.absolutePath + "/test.pbw")
}