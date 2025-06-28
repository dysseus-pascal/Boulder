package io.rebble.libpebblecommon.connection.bt.ble.transport.impl

import co.touchlab.kermit.Logger
import com.juul.kable.DiscoveredCharacteristic
import com.juul.kable.DiscoveredService
import com.juul.kable.Peripheral
import com.juul.kable.State
import com.juul.kable.WriteType
import io.rebble.libpebblecommon.connection.Transport.BluetoothTransport.BleTransport
import io.rebble.libpebblecommon.connection.bt.ble.transport.ConnectedGattClient
import io.rebble.libpebblecommon.connection.bt.ble.transport.GattCharacteristic
import io.rebble.libpebblecommon.connection.bt.ble.transport.GattConnector
import io.rebble.libpebblecommon.connection.bt.ble.transport.GattDescriptor
import io.rebble.libpebblecommon.connection.bt.ble.transport.GattService
import io.rebble.libpebblecommon.connection.bt.ble.transport.GattWriteType
import io.rebble.libpebblecommon.di.ConnectionCoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.io.IOException
import kotlin.uuid.Uuid

fun kableGattConnector(transport: BleTransport, scope: ConnectionCoroutineScope): GattConnector? {
    val peripheral = peripheralFromIdentifier(transport)
    if (peripheral == null) return null
    return KableGattConnector(transport, peripheral, scope)
}


expect fun peripheralFromIdentifier(transport: BleTransport): Peripheral?

class KableGattConnector(
    private val transport: BleTransport,
    private val peripheral: Peripheral,
    private val scope: ConnectionCoroutineScope,
) : GattConnector {
    private val logger = Logger.withTag("KableGattConnector/${transport.identifier.asString}")

    private val _disconnected = CompletableDeferred<Unit>()
    override val disconnected: Deferred<Unit> = _disconnected

    override suspend fun connect(): ConnectedGattClient? {
        if (!peripheral.scope.isActive) {
            logger.w { "connect(): peripheral already closed!" }
            _disconnected.complete(Unit)
            return null
        }
        scope.async {
            val disconnected = peripheral.state.dropWhile {
                // Skip initial disconnected state before we connect
                it is State.Disconnected
            }.filterIsInstance<State.Disconnected>().first()
            logger.i { "Disconnection: status=${disconnected.status}" }
            _disconnected.complete(Unit)
        }
        try {
            val kableScope = peripheral.connect()
            return KableConnectedGattClient(transport, peripheral)
        } catch (e: Exception) {
            logger.e("error connecting", e)
            return null
        }
    }

    override suspend fun disconnect() {
        logger.d { "disconnect()..." }
        peripheral.disconnect()
        peripheral.close()
        logger.d { "/disconnect()..." }
    }

    override fun close() {
        peripheral.close()
    }
}

expect suspend fun Peripheral.requestMtuNative(mtu: Int): Int

class KableConnectedGattClient(
    val transport: BleTransport,
    val peripheral: Peripheral,
) : ConnectedGattClient {
    private val logger = Logger.withTag("KableConnectedGattClient-${transport.identifier.asString}")

    override suspend fun discoverServices(): Boolean {
        // Kable already discovered upon connect
        return true
    }

    private fun mapServices() = peripheral.services.value?.map { it.asGattService() }

    override suspend fun subscribeToCharacteristic(
        serviceUuid: Uuid,
        characteristicUuid: Uuid,
    ): Flow<ByteArray>? {
        val c = findCharacteristic(serviceUuid, characteristicUuid)
        if (c == null) {
            logger.e("couldn't find characteristic: $characteristicUuid")
            return null
        }
        return peripheral.observe(c)
    }

    override suspend fun isBonded(): Boolean {
        return io.rebble.libpebblecommon.connection.bt.isBonded(transport)
    }

    fun GattWriteType.asKableWriteType() = when (this) {
        GattWriteType.WithResponse -> WriteType.WithResponse
        GattWriteType.NoResponse -> WriteType.WithoutResponse
    }

    override suspend fun writeCharacteristic(
        serviceUuid: Uuid,
        characteristicUuid: Uuid,
        value: ByteArray,
        writeType: GattWriteType,
    ): Boolean {
        val c = findCharacteristic(serviceUuid, characteristicUuid)
        if (c == null) {
            logger.e("couldn't find characteristic: $characteristicUuid")
            return false
        }
        return try {
            peripheral.write(c, value, writeType.asKableWriteType())
            true
        } catch (e: com.juul.kable.GattStatusException) {
            logger.v("error writing characteristic", e)
            false
        } catch (e: IOException) {
            logger.v("error writing characteristic", e)
            false
        }
    }

    override suspend fun readCharacteristic(
        serviceUuid: Uuid,
        characteristicUuid: Uuid
    ): ByteArray? {
        val c = findCharacteristic(serviceUuid, characteristicUuid)
        if (c == null) {
            logger.e("couldn't find characteristic: $characteristicUuid")
            return null
        }
        return peripheral.read(c)
    }

    override val services: List<GattService>? = mapServices()

    override suspend fun requestMtu(mtu: Int): Int {
        return peripheral.requestMtuNative(mtu)
    }

    override suspend fun getMtu(): Int {
        return peripheral.maximumWriteValueLengthForType(WriteType.WithoutResponse) + MTU_OVERHEAD
    }

    override fun close() {
        peripheral.close()
    }

    private fun findCharacteristic(
        serviceUuid: Uuid,
        characteristicUuid: Uuid
    ): DiscoveredCharacteristic? {
        return peripheral.services.value
            ?.firstOrNull { it.serviceUuid == serviceUuid }
            ?.characteristics
            ?.firstOrNull { it.characteristicUuid == characteristicUuid }
    }

    companion object {
        /** Kable is nice and computes what is useable by us - but we already do that elsewhere, so
         * put the overhead back in */
        private const val MTU_OVERHEAD = 3
    }
}

private fun DiscoveredService.asGattService(): GattService = GattService(
    uuid = serviceUuid,
    characteristics = characteristics.map { c ->
        GattCharacteristic(
            uuid = c.characteristicUuid,
            properties = c.properties.value,
            permissions = c.properties.value, // TODO right?
            descriptors = c.descriptors.map { d ->
                GattDescriptor(
                    uuid = d.descriptorUuid,
                    permissions = 0, // not provided by kable
                )
            },
        )
    },
)
