package io.rebble.libpebblecommon.connection

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.Transport.BluetoothTransport.BleTransport
import io.rebble.libpebblecommon.connection.bt.ble.pebble.PebbleBle
import io.rebble.libpebblecommon.connection.bt.ble.transport.gattConnector
import io.rebble.libpebblecommon.database.Database
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

class WatchManager(
    private val config: LibPebbleConfig,
    private val database: Database,
) {
    // Scan results - may or may not have extra scan record data
    private val scanResults = MutableStateFlow<Map<Transport, PebbleDevice>>(emptyMap())

    // Known devices (may not be KnownPebbleDevice if we never negotiated with it)
    private val knownDevices = MutableStateFlow<Map<Transport, PebbleDevice>>(emptyMap())
    private val _watches = MutableStateFlow<List<PebbleDevice>>(emptyList())
    private val activeConnections = MutableStateFlow<Map<Transport, ActiveConnection>>(emptyMap())
    val watches: StateFlow<List<PebbleDevice>> = _watches

    fun init() {
        GlobalScope.launch {
            val activeConnectionStates = activeConnections.flowOfAllDevices()
            combine(
                scanResults,
                knownDevices,
                activeConnectionStates
            ) { scanResults, knownDevices, activeConnections ->
                // Known device takes priority over scan result for the same device
                scanResults.toMutableMap().apply {
                    putAll(knownDevices)
                    putAll(activeConnections)
                }.values.toList()
            }.collect {
                _watches.value = it
            }
        }

    }

    fun addScanResult(device: PebbleDevice) {
        Logger.d("addScanResult: $device")
        scanResults.value = scanResults.value.toMutableMap().apply {
            put(device.transport, device)
        }
    }

    fun connectTo(pebbleDevice: PebbleDevice) {
        val existingDevice = knownDevices.value[pebbleDevice.transport]
        if (existingDevice != null && existingDevice is ActiveDevice) {
            Logger.d("Already connecting to $pebbleDevice")
            return
        }
        val exceptionHandler = CoroutineExceptionHandler { coroutineContext, throwable ->
            Logger.e("watchmanager caught exception for ${pebbleDevice.transport}", throwable)
            val connection = activeConnections.value[pebbleDevice.transport]
            connection?.let { connection ->
                GlobalScope.launch {
                    connection.connector.cleanup()
                }
            }
        }
        val deviceIdString = pebbleDevice.transport.identifier.asString
        val coroutineContext =
            SupervisorJob() + exceptionHandler + CoroutineName("con-$deviceIdString")
        val connectionScope = CoroutineScope(coroutineContext)
        connectionScope.launch {
            val transportConnector = pebbleDevice.createConnector(connectionScope)
            val pebbleConnector =
                PebbleConnector(transportConnector, database, pebbleDevice, connectionScope)
            try {
                activeConnections.value = activeConnections.value.toMutableMap().apply {
                    put(
                        pebbleDevice.transport,
                        ActiveConnection(pebbleDevice.transport, pebbleConnector),
                    )
                }

                pebbleConnector.connect()
                Logger.d("watchmanager connected; waiting for disconnect: ${pebbleDevice.transport}")
                pebbleConnector.disconnected.first()
                Logger.d("watchmanager got disconnection: ${pebbleDevice.transport}")
            } finally {
                pebbleConnector.cleanup()
            }
        }
    }

    private suspend fun PebbleConnector.cleanup() {
        Logger.d("${pebbleDevice.transport}: cleanup")
        disconnect()
        try {
            withTimeout(DISCONNECT_TIMEOUT) {
                Logger.d("${pebbleDevice.transport}: cleanup: waiting for disconnection")
                disconnected.first()
            }
        } catch (e: TimeoutCancellationException) {
            Logger.w("cleanup: timed out waiting for disconnection from ${pebbleDevice.transport}")
        }
        Logger.d("${pebbleDevice.transport}: cleanup: removing active device")
        activeConnections.value = activeConnections.value.toMutableMap().apply {
            remove(pebbleDevice.transport)
        }
        Logger.d("${pebbleDevice.transport}: cleanup: cancelling scope")
        scope.cancel()
    }

    fun disconnectFrom(pebbleDevice: PebbleDevice) {
        val activeConnection = activeConnections.value[pebbleDevice.transport]
        if (activeConnection == null) {
            Logger.d("disconnectFrom / not an active device")
            return
        }
        activeConnection.connector.disconnect()
    }

    private fun PebbleDevice.createConnector(scope: CoroutineScope): TransportConnector {
        val pebbleTransport = transport
        return when (pebbleTransport) {
            is BleTransport -> PebbleBle(
                config = config,
                pebbleDevice = this,
                scope = scope,
                gattConnector = gattConnector(pebbleTransport, config.context),
            )

            else -> TODO("not implemented")
        }
    }

    companion object {
        private val DISCONNECT_TIMEOUT = 3.seconds
    }
}

fun StateFlow<Map<Transport, ActiveConnection>>.flowOfAllDevices(): Flow<Map<Transport, PebbleDevice>> {
    return flatMapLatest { map ->
        val listOfInnerFlows = map.values.map { it.connector.state }
        if (listOfInnerFlows.isEmpty()) {
            flowOf(emptyMap())
        } else {
            combine(listOfInnerFlows) { innerValues ->
                innerValues.associateBy { it.transport }
            }
        }
    }
}


data class ActiveConnection(
    val transport: Transport,
    val connector: PebbleConnector,
)