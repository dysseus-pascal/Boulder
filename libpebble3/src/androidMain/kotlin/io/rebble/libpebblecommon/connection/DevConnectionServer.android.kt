package io.rebble.libpebblecommon.connection

import android.content.Context
import kotlinx.io.files.Path
import org.koin.mp.KoinPlatform
import java.util.UUID

internal actual fun getTempPbwPath(): Path {
    val context: Context = KoinPlatform.getKoin().get()
    val cacheDir = context.cacheDir
    val uuid = UUID.randomUUID().toString()
    return Path(cacheDir.absolutePath, "devconn-$uuid.pbw")
}