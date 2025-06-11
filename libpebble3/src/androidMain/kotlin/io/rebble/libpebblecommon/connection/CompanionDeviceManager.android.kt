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
import io.rebble.libpebblecommon.di.LibPebbleCoroutineScope
import io.rebble.libpebblecommon.notification.LibPebbleNotificationListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

actual fun createCompanionDeviceManager(appContext: AppContext, libPebbleCoroutineScope: LibPebbleCoroutineScope): CompanionDevice {
    return AndroidCompanionDevice(appContext, libPebbleCoroutineScope)
}

class AndroidCompanionDevice(
    private val appContext: AppContext,
    private val libPebbleCoroutineScope: LibPebbleCoroutineScope,
) : CompanionDevice {
    private val logger = Logger.withTag("AndroidCompanionDevice")
    private val context = appContext.context
    private val _companionAccessGranted = MutableSharedFlow<Unit>()
    override val companionAccessGranted = _companionAccessGranted.asSharedFlow()

    override suspend fun registerDevice(transport: Transport) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        if (transport !is Transport.BluetoothTransport) {
            return
        }
        val service = context.getSystemService(CompanionDeviceManager::class.java)

        @Suppress("DEPRECATION")
        val existingBoundDevices = service.associations
        val macAddress = transport.identifier.macAddress
        if (existingBoundDevices.contains(macAddress)) {
            service.requestNotificationAccessIfRequired()
            return
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

        val result = CompletableDeferred<Unit>()
        val callback = object : CompanionDeviceManager.Callback() {
            override fun onAssociationPending(intentSender: IntentSender) {
                logger.d("onAssociationPending")
                context.startIntentSender(intentSender, null, 0, 0, 0)
            }

            override fun onAssociationCreated(associationInfo: AssociationInfo) {
                logger.d("onAssociationCreated")
                libPebbleCoroutineScope.launch {
                    _companionAccessGranted.emit(Unit)
                }
                result.complete(Unit)
            }

            override fun onFailure(error: CharSequence?) {
                logger.d("onFailure: $error")
                result.complete(Unit)
            }
        }
        logger.d("requesting association")
        service.associate(associationRequest, callback, null)
        withTimeoutOrNull(30.seconds) {
            result.await()
            service.requestNotificationAccessIfRequired()
        }
    }

    private fun CompanionDeviceManager.requestNotificationAccessIfRequired() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return // TODO handle
        val component = LibPebbleNotificationListener.componentName(context)
        if (hasNotificationAccess(component)) {
            return
        }
        requestNotificationAccess(component)
    }
}