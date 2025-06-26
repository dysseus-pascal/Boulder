package io.rebble.libpebblecommon.util

import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.Instant
import kotlin.time.Duration.Companion.seconds

sealed class GeolocationPositionResult {
    data class Success(val timestamp: Instant, val latitude: Double, val longitude: Double) : GeolocationPositionResult()
    data class Error(val message: String) : GeolocationPositionResult()
}

interface SystemGeolocation {
    companion object {
        /**
         * Maximum time to cache geolocation data before falling back to a new request.
         */
        val MAX_CACHED_TIME = 30.seconds

        /**
         * Oldest allowed cached fallback time to return in the case that a fix cannot be obtained.
         */
        val MAX_FALLBACK_TIME = 60.seconds
    }
    suspend fun getCurrentPosition(): GeolocationPositionResult
    suspend fun watchPosition(): Flow<GeolocationPositionResult>
}