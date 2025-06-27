package io.rebble.libpebblecommon.io.rebble.libpebblecommon.util

import android.annotation.SuppressLint
import android.location.LocationListener
import android.os.Build
import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.util.GeolocationPositionResult
import io.rebble.libpebblecommon.util.SystemGeolocation
import io.rebble.libpebblecommon.util.SystemGeolocation.Companion.MAX_CACHED_TIME
import io.rebble.libpebblecommon.util.SystemGeolocation.Companion.MAX_FALLBACK_TIME
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.coroutines.resume

class AndroidSystemGeolocation(appContext: AppContext): SystemGeolocation {
    companion object {
        private val logger = Logger.withTag("AndroidSystemGeolocation")
    }
    private val context = appContext.context
    private val locationManager by lazy {
        context.getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager
    }
    @SuppressLint("MissingPermission")
    private val location = callbackFlow {
        if (!checkPermission()) {
            trySend(GeolocationPositionResult.Error("Location permission not granted"))
            close()
            awaitClose()
        } else {
            val locationListener = LocationListener { location ->
                trySend(
                    GeolocationPositionResult.Success(
                        timestamp = Instant.fromEpochMilliseconds(location.time),
                        latitude = location.latitude,
                        longitude = location.longitude
                    )
                )
            }
            locationManager.requestLocationUpdates(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    android.location.LocationManager.FUSED_PROVIDER
                } else {
                    android.location.LocationManager.GPS_PROVIDER
                },
                250L,
                0f,
                locationListener
            )
            awaitClose {
                locationManager.removeUpdates(locationListener)
            }
        }
    }.shareIn(GlobalScope, SharingStarted.WhileSubscribed(1000))

    private fun checkPermission(): Boolean {
        return context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    override suspend fun getCurrentPosition(): GeolocationPositionResult {
        logger.d { "getCurrentPosition called" }
        return if (checkPermission()) {
            val location = suspendCancellableCoroutine { cont ->
                val lastKnownLocation = locationManager.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                if (Clock.System.now() - Instant.fromEpochMilliseconds(lastKnownLocation?.time ?: 0) < MAX_CACHED_TIME) {
                    logger.d { "Returning last known location" }
                    cont.resume(lastKnownLocation)
                    return@suspendCancellableCoroutine
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    logger.d { "Requesting current location with Fused Provider" }
                    locationManager.getCurrentLocation(
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            android.location.LocationManager.FUSED_PROVIDER
                        } else {
                            android.location.LocationManager.GPS_PROVIDER
                        },
                        null,
                        context.mainExecutor
                    ) { location ->
                        cont.resume(
                            location ?: if (Clock.System.now() - Instant.fromEpochMilliseconds(lastKnownLocation?.time ?: 0) > MAX_FALLBACK_TIME) {
                                null
                            } else {
                                lastKnownLocation
                            }
                        )
                    }
                } else {
                    logger.d { "Requesting single update for location" }
                    locationManager.requestSingleUpdate(
                        android.location.LocationManager.GPS_PROVIDER,
                        { location ->
                            cont.resume(location)
                        },
                        context.mainLooper
                    )
                }
            }
            if (location != null) {
                GeolocationPositionResult.Success(
                    timestamp = Instant.fromEpochMilliseconds(location.time),
                    latitude = location.latitude,
                    longitude = location.longitude
                )
            } else {
                GeolocationPositionResult.Error("Location not available")
            }
        } else {
            GeolocationPositionResult.Error("Location permission not granted")
        }
    }

    override suspend fun watchPosition(): Flow<GeolocationPositionResult> = location
}