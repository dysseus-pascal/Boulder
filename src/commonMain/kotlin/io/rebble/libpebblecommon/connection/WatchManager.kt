package io.rebble.libpebblecommon.connection

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.Transport.BluetoothTransport.BleTransport
import io.rebble.libpebblecommon.connection.bt.ble.pebble.PebbleBle
import io.rebble.libpebblecommon.connection.bt.ble.transport.gattConnector
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds

class WatchManager(
    private val config: LibPebbleConfig,
) {
    // Scan results - may or may not have extra scan record data
    private val scanResults = MutableStateFlow<Map<Transport, PebbleDevice>>(emptyMap())

    // Known devices (may not be KnownPebbleDevice if we never negotiated with it)
    private val knownDevices = MutableStateFlow<Map<Transport, PebbleDevice>>(emptyMap())
    private val _watches = MutableStateFlow<List<PebbleDevice>>(emptyList())
    private val activeConnections = MutableStateFlow<Map<Transport, ActiveConnection>>(emptyMap())

    // Putting this outside of [PebbleDevice] right now - it would be hard to keep it synced inside
    // of there (e.g. from the connector generating new states), unless we refactor
    private val devicesWithConnectGoal = MutableStateFlow<Set<Transport>>(emptySet())

    val watches: StateFlow<List<PebbleDevice>> = _watches

    fun init() {
        GlobalScope.launch {
            val activeConnectionStates = activeConnections.flowOfAllDevices()
            combine(
                scanResults,
                knownDevices,
                activeConnectionStates,
                devicesWithConnectGoal,
            ) { scan, known, active, connectGoal ->
                // Known device takes priority over scan result for the same device
                scan.toMutableMap().apply {
                    putAll(known)
                    putAll(active)
                }.values.toList().also { allDevices ->
                    allDevices.forEach { device ->
                        val hasConnectGoal = device.transport in connectGoal
                        val hasConnectionAttempt = active.containsKey(device.transport)
                        if (hasConnectGoal && !hasConnectionAttempt) {
                            connectTo(device)
                        } else if (!hasConnectGoal && hasConnectionAttempt) {
                            disconnectFrom(device)
                        }
                    }
                }
            }.collect {
                _watches.value = it
            }
        }
    }

    fun addScanResult(device: PebbleDevice) {
        Logger.d("addScanResult: $device")
        scanResults.value += device.transport to device
    }

    fun requestConnection(pebbleDevice: PebbleDevice) {
        Logger.d("requestConnection: $pebbleDevice")
        devicesWithConnectGoal.value += pebbleDevice.transport
    }

    fun requestDisconnection(pebbleDevice: PebbleDevice) {
        Logger.d("requestDisconnection: $pebbleDevice")
        devicesWithConnectGoal.value -= pebbleDevice.transport
    }

    private fun connectTo(pebbleDevice: PebbleDevice) {
        Logger.d("connectTo: $pebbleDevice")
        val existingDevice = activeConnections.value[pebbleDevice.transport]
        if (existingDevice != null) {
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
        val transportConnector = pebbleDevice.createConnector(connectionScope)
        val pebbleConnector =
            PebbleConnector(transportConnector, pebbleDevice, connectionScope)
        activeConnections.value +=
            pebbleDevice.transport to ActiveConnection(pebbleDevice.transport, pebbleConnector)

        connectionScope.launch {
            try {
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
        activeConnections.value -= pebbleDevice.transport
        Logger.d("${pebbleDevice.transport}: cleanup: cancelling scope")
        scope.cancel()
    }

    private fun disconnectFrom(pebbleDevice: PebbleDevice) {
        Logger.d("disconnectFrom: $pebbleDevice")
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