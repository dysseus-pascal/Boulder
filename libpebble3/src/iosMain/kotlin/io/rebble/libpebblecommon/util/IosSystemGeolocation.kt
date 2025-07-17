package io.rebble.libpebblecommon.io.rebble.libpebblecommon.util

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.util.GeolocationPositionResult
import io.rebble.libpebblecommon.util.SystemGeolocation
import kotlinx.cinterop.useContents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.shareIn
import kotlinx.datetime.Instant
import platform.CoreLocation.CLActivityTypeOther
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.Foundation.NSError
import platform.Foundation.timeIntervalSince1970
import platform.darwin.NSObject

class IosSystemGeolocation: SystemGeolocation {
    private val logger = Logger.withTag("IosSystemGeolocation")
    private val locationManager = CLLocationManager().apply {
        activityType = CLActivityTypeOther
    }
    private val location = callbackFlow {
        val delegate = object : CLLocationManagerDelegateProtocol, NSObject() {
            override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) {
                logger.d { "Authorization changed: ${manager.authorizationStatus}" }
                if (!CLLocationManager.locationServicesEnabled()) {
                    trySend(GeolocationPositionResult.Error("Location services are not enabled"))
                    close()
                }
            }

            override fun locationManager(
                manager: CLLocationManager,
                didFailWithError: NSError
            ) {
                logger.e { "Failed to get location: ${didFailWithError.localizedDescription}" }
                trySend(GeolocationPositionResult.Error("Failed to get location: ${didFailWithError.localizedDescription}"))
            }

            override fun locationManagerDidPauseLocationUpdates(manager: CLLocationManager) {
                logger.d { "Location updates paused" }
            }

            override fun locationManagerDidResumeLocationUpdates(manager: CLLocationManager) {
                logger.d { "Location updates resumed" }
            }

            override fun locationManager(
                manager: CLLocationManager,
                didUpdateLocations: List<*>
            ) {
                @Suppress("UNCHECKED_CAST")
                didUpdateLocations as List<CLLocation>
                logger.d { "Received location updates: ${didUpdateLocations.size} locations" }
                if (didUpdateLocations.isNotEmpty()) {
                    val location = didUpdateLocations.last()
                    location.coordinate.useContents {
                        trySend(
                            GeolocationPositionResult.Success(
                                timestamp = Instant.fromEpochMilliseconds((location.timestamp.timeIntervalSince1970 * 1000).toLong()),
                                latitude = latitude,
                                longitude = longitude,
                                accuracy = location.horizontalAccuracy,
                                altitude = location.altitude,
                                heading = location.course,
                                speed = location.speed,
                            )
                        )
                    }
                }
            }
        }
        locationManager.delegate = delegate
        locationManager.requestWhenInUseAuthorization()
        locationManager.startUpdatingLocation()
        awaitClose {
            logger.d { "Stopping location updates" }
            locationManager.stopUpdatingLocation()
        }
    }.flowOn(Dispatchers.Main).shareIn(GlobalScope, SharingStarted.WhileSubscribed(1000), 1)

    override suspend fun getCurrentPosition(): GeolocationPositionResult {
        logger.d { "getCurrentPosition()" }
        return location.first()
    }

    override suspend fun watchPosition(): Flow<GeolocationPositionResult> = location
}