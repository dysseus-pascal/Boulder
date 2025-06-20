package io.rebble.libpebblecommon.web

import io.rebble.libpebblecommon.connection.FirmwareUpdateCheckResult
import io.rebble.libpebblecommon.connection.WebServices
import io.rebble.libpebblecommon.services.WatchInfo

class FirmwareUpdateManager(
    private val webServices: WebServices,
) {
    suspend fun checkForUpdates(watchInfo: WatchInfo): FirmwareUpdateCheckResult? {
        return webServices.checkForFirmwareUpdate(watchInfo)
    }
}