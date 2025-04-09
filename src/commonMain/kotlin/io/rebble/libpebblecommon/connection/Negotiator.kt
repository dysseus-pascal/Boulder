package io.rebble.libpebblecommon.connection

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.services.SystemService
import io.rebble.libpebblecommon.services.WatchInfo
import io.rebble.libpebblecommon.services.app.AppRunStateService
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds

class Negotiator() {
    suspend fun negotiate(
        systemService: SystemService,
        appRunStateService: AppRunStateService,
    ): WatchInfo? = try {
        Logger.d("negotiate()")
        withTimeout(20.seconds) {
            val appVersionRequest = systemService.appVersionRequest.await()
            Logger.d("RealNegotiatingPebbleDevice appVersionRequest = $appVersionRequest")
            systemService.sendPhoneVersionResponse()
            Logger.d("RealNegotiatingPebbleDevice sent watch version request")
            val watchInfo = systemService.requestWatchVersion()
            Logger.d("RealNegotiatingPebbleDevice watchVersionResponse = $watchInfo")
            val runningApp = appRunStateService.runningApp.first()
            Logger.d("RealNegotiatingPebbleDevice runningApp = $runningApp")
            watchInfo
        }
    } catch (e: TimeoutCancellationException) {
        Logger.w("negotiation timed out")
        null
    }
}