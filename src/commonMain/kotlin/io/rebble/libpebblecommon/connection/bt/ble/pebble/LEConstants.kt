package io.rebble.libpebblecommon.connection.bt.ble.pebble

import io.rebble.libpebblecommon.connection.bt.ble.transport.GattCharacteristic
import io.rebble.libpebblecommon.connection.bt.ble.transport.GattDescriptor
import io.rebble.libpebblecommon.connection.bt.ble.transport.GattService

object LEConstants {
    object UUIDs {
        // TODO lower-case all of these (android) or provide a util for comparing them
        // ... or use new kotlin Uuid everywhere
        val CHARACTERISTIC_CONFIGURATION_DESCRIPTOR = "00002902-0000-1000-8000-00805f9b34fb"

        val PAIRING_SERVICE_UUID = "0000fed9-0000-1000-8000-00805f9b34fb"
        val APPLAUNCH_SERVICE_UUID = "20000000-328E-0FBB-C642-1AA6699BDADA"

        val CONNECTIVITY_CHARACTERISTIC = "00000001-328E-0FBB-C642-1AA6699BDADA"
        val PAIRING_TRIGGER_CHARACTERISTIC = "00000002-328E-0FBB-C642-1AA6699BDADA"
        val META_CHARACTERISTIC_SERVER = "10000002-328E-0FBB-C642-1AA6699BDADA"
        val APPLAUNCH_CHARACTERISTIC = "20000001-328E-0FBB-C642-1AA6699BDADA"

        val PPOGATT_DEVICE_SERVICE_UUID_CLIENT = "30000003-328E-0FBB-C642-1AA6699BDADA"
        val PPOGATT_DEVICE_SERVICE_UUID_SERVER = "10000000-328E-0FBB-C642-1AA6699BDADA"
        val PPOGATT_DEVICE_CHARACTERISTIC_READ = "30000004-328E-0FBB-C642-1AA6699BDADA"
        val PPOGATT_DEVICE_CHARACTERISTIC_WRITE = "30000006-328e-0fbb-c642-1aa6699bdada"
        val PPOGATT_DEVICE_CHARACTERISTIC_SERVER = "10000001-328E-0FBB-C642-1AA6699BDADA"

        val CONNECTION_PARAMETERS_CHARACTERISTIC = "00000005-328E-0FBB-C642-1AA6699BDADA"

        val MTU_CHARACTERISTIC = "00000003-328E-0FBB-C642-1AA6699BDADA"

        val FAKE_SERVICE_UUID = "BADBADBA-DBAD-BADB-ADBA-BADBADBADBAD"
    }

    val CHARACTERISTIC_SUBSCRIBE_VALUE = byteArrayOf(1, 0)
    val DEFAULT_MTU = 23
    val TARGET_MTU = 339
    val MAX_RX_WINDOW: Int = 25
    val MAX_TX_WINDOW: Int = 25

    // PPoGConnectionVersion.minSupportedVersion(), PPoGConnectionVersion.maxSupportedVersion(), ??? (magic numbers in stock app too)
    val SERVER_META_RESPONSE = byteArrayOf(0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)

    const val PROPERTY_READ = 0x02
    const val PROPERTY_NOTIFY = 0x10
    const val PROPERTY_WRITE_NO_RESPONSE = 0x04
    const val PROPERTY_WRITE = 0x08

    const val PERMISSION_READ = 0x01
    const val PERMISSION_WRITE = 0x10
    const val PERMISSION_READ_ENCRYPTED = 0x02
    const val PERMISSION_WRITE_ENCRYPTED = 0x20

    const val BOND_BONDED = 12 // TODO ios compatible?

    val PPOG_META_CHARACTERISTIC = GattCharacteristic(
        uuid = UUIDs.META_CHARACTERISTIC_SERVER,
        properties = PROPERTY_READ,
        permissions = PERMISSION_READ_ENCRYPTED,
        descriptors = emptyList(),
    )

    val PPOG_DATA_CHARACTERISTIC = GattCharacteristic(
        uuid = UUIDs.PPOGATT_DEVICE_CHARACTERISTIC_SERVER,
        properties = PROPERTY_WRITE_NO_RESPONSE or PROPERTY_NOTIFY,
        permissions = PERMISSION_WRITE_ENCRYPTED,
        descriptors = listOf(
            GattDescriptor(
                uuid = UUIDs.CHARACTERISTIC_CONFIGURATION_DESCRIPTOR,
                permissions = PERMISSION_WRITE,
            )
        ),
    )

    val PPOG_SERVICE = GattService(
        uuid = UUIDs.PPOGATT_DEVICE_SERVICE_UUID_SERVER,
        characteristics = listOf(PPOG_META_CHARACTERISTIC, PPOG_DATA_CHARACTERISTIC),
    )

    val FAKE_CHARACTERISTIC = GattCharacteristic(
        uuid = UUIDs.FAKE_SERVICE_UUID,
        properties = PROPERTY_READ,
        permissions = PERMISSION_READ_ENCRYPTED,
        descriptors = emptyList(),
    )

    val FAKE_SERVICE = GattService(
        uuid = UUIDs.FAKE_SERVICE_UUID,
        characteristics = listOf(FAKE_CHARACTERISTIC),
    )

    val GATT_SERVICES = listOf(PPOG_SERVICE, FAKE_SERVICE)
}