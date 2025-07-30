package io.rebble.libpebblecommon.connection

import android.bluetooth.le.ScanFilter
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.IntentSender
import android.os.Build
import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.WatchConfigFlow
import io.rebble.libpebblecommon.di.LibPebbleCoroutineScope
import io.rebble.libpebblecommon.notification.LibPebbleNotificationListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

class AndroidCompanionDevice(
    private val libPebbleCoroutineScope: LibPebbleCoroutineScope,
    private val watchConfigFlow: WatchConfigFlow,
) : CompanionDevice {
    private val logger = Logger.withTag("AndroidCompanionDevice")
    private val _companionAccessGranted = MutableSharedFlow<Unit>()
    override val companionAccessGranted = _companionAccessGranted.asSharedFlow()
    private val _notificationAccessGranted = MutableSharedFlow<Unit>()
    override val notificationAccessGranted = _notificationAccessGranted.asSharedFlow()

    override suspend fun registerDevice(transport: Transport, uiContext: UIContext?): Boolean {
        if (transport !is Transport.BluetoothTransport) {
            return true
        }
        if (!watchConfigFlow.value.useAndroidCompanionDeviceManager) {
            logger.i { "Not using companion device manager; disable in watch config" }
            return true
        }
        val context = uiContext?.activity
        if (context == null) {
            logger.w("context is null")
            return false
        }
        val service = context.getSystemService(CompanionDeviceManager::class.java)

        @Suppress("DEPRECATION")
        val existingBoundDevices = service.associations
        val macAddress = transport.identifier.macAddress
        if (existingBoundDevices.contains(macAddress)) {
            service.requestNotificationAccessIfRequired(uiContext)
            return true
        }

        val filter = when (transport) {
            is Transport.BluetoothTransport.BtClassicTransport -> BluetoothDeviceFilter.Builder()
                .setAddress(macAddress)
                .build()

            is Transport.BluetoothTransport.BleTransport -> BluetoothLeDeviceFilter.Builder()
                .setScanFilter(ScanFilter.Builder().setDeviceAddress(macAddress).build())
                .build()
        }
        val associationRequest = AssociationRequest.Builder().apply {
            addDeviceFilter(filter)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setDeviceProfile(AssociationRequest.DEVICE_PROFILE_WATCH)
            }
            setSingleDevice(true)
        }.build()

        val result = CompletableDeferred<Boolean>()
        val callback = object : CompanionDeviceManager.Callback() {
            // Called on API levels below 33
            @Deprecated("Deprecated in API 33 / Android 13, use onAssociationPending instead")
            @Suppress("DEPRECATION") // Suppress lint for using deprecated onDeviceFound
            override fun onDeviceFound(intentSender: IntentSender) {
                logger.d("onDeviceFound (API < 33)")
                context.startIntentSender(intentSender, null, 0, 0, 0)
            }

            // Called on API levels 33 and above
            override fun onAssociationPending(intentSender: IntentSender) {
                logger.d("onAssociationPending (API >= 33)")
                context.startIntentSender(intentSender, null, 0, 0, 0)
            }

            override fun onAssociationCreated(associationInfo: AssociationInfo) {
                logger.d("onAssociationCreated")
                libPebbleCoroutineScope.launch {
                    _companionAccessGranted.emit(Unit)
                }
                result.complete(true)
            }

            override fun onFailure(error: CharSequence?) {
                logger.d("onFailure: $error")
                result.complete(false)
            }
        }
        logger.d("requesting association")
        service.associate(associationRequest, callback, null)
        val succeeded = withTimeoutOrNull(30.seconds) {
            if (!result.await()) {
                false
            } else {
                service.requestNotificationAccessIfRequired(uiContext)
                true
            }
        } ?: false
        return succeeded
    }

    private fun CompanionDeviceManager.requestNotificationAccessIfRequired(context: UIContext) {
        val component = LibPebbleNotificationListener.componentName(context.activity.applicationContext)
        if (hasNotificationAccess(component)) {
            libPebbleCoroutineScope.launch {
                _notificationAccessGranted.emit(Unit)
            }
            return
        }
        requestNotificationAccess(component)
        libPebbleCoroutineScope.launch {
            delay(5.seconds)
            _notificationAccessGranted.emit(Unit)
        }
    }
}