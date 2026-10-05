package coredevices.pebble

import android.provider.OpenableColumns
import co.touchlab.kermit.Logger
import com.eygraber.uri.Uri
import com.eygraber.uri.toAndroidUri
import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.util.getTempFilePath
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import java.io.File
import java.io.IOException

private val logger = Logger.withTag("PebbleDeepLinkHandler")

actual fun readNameFromContentUri(appContext: AppContext, uri: Uri): String? {
    try {
        val cursor = appContext.context.contentResolver.query(
            uri.toAndroidUri(),
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )

        cursor?.use {
            if (it.moveToFirst()) {
                val displayNameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (displayNameIndex != -1) {
                    return it.getString(displayNameIndex)
                }
            }
        }
    } catch (e: Exception) {
        logger.e(e) { "readNameFromContentUri" }
    }
    return null
}

actual fun writeFile(
    appContext: AppContext,
    uri: Uri
): Path? {
    val stream = try {
        appContext.context.contentResolver.openInputStream(uri.toAndroidUri())
    } catch (e: Exception) {
        logger.e(e) { "writeFile" }
        return null
    }
    if (stream == null) {
        logger.e { "writeFile: stream is null" }
        return null
    }
    val fileToWrite = getTempFilePath(appContext, "sideloaded_file")
    // Boulder: mit Obergrenze und gefangenem Fehler - die Datei kommt von einer beliebigen App,
    // ein endloser oder abbrechender Strom fuellte sonst den Speicher oder beendete die App.
    return try {
        stream.use { quelle ->
            File(fileToWrite.toString()).outputStream().use { ziel ->
                val puffer = ByteArray(64 * 1024)
                var gesamt = 0L
                while (true) {
                    val n = quelle.read(puffer)
                    if (n < 0) break
                    gesamt += n
                    if (gesamt > SIDELOAD_HOECHSTENS_BYTES) throw IOException("larger than $SIDELOAD_HOECHSTENS_BYTES bytes")
                    ziel.write(puffer, 0, n)
                }
            }
        }
        fileToWrite
    } catch (e: Exception) {
        logger.e(e) { "writeFile: not copied" }
        SystemFileSystem.delete(fileToWrite, mustExist = false)
        null
    }
}

/** Boulder: groesser ist keine Firmware, App oder Sprachdatei einer Pebble (Firmware um 1 MB). */
internal const val SIDELOAD_HOECHSTENS_BYTES = 32L * 1024 * 1024