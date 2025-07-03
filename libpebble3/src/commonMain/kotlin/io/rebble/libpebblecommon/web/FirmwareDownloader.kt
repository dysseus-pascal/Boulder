package io.rebble.libpebblecommon.web

import co.touchlab.kermit.Logger
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.readRemaining
import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.locker.getLockerPBWCacheDirectory
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.time.Duration.Companion.seconds

class FirmwareDownloader(
    private val httpClient: HttpClient,
    private val appContext: AppContext,
) {
    private val logger = Logger.withTag("FirmwareDownloader")
    private val fwDir = getLockerPBWCacheDirectory(appContext)

    suspend fun downloadFirmware(url: String): Path? {
        return withTimeoutOrNull(20.seconds) {
            val path = Path(fwDir, "fw.pbz")
            SystemFileSystem.delete(path, mustExist = false)
            val response = try {
                httpClient.get(url)
            } catch (e: IOException) {
                logger.w(e) { "Error downloading fw: ${e.message}" }
                return@withTimeoutOrNull null
            }
            if (!response.status.isSuccess()) {
                logger.w("http call failed: $response")
                return@withTimeoutOrNull null
            }
            SystemFileSystem.sink(path).use { sink ->
                response.bodyAsChannel().readRemaining().transferTo(sink)
            }
            path
        }
    }
}

expect fun getFirmwareDownloadDirectory(context: AppContext): Path
