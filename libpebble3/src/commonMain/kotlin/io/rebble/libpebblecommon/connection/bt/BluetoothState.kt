package io.rebble.libpebblecommon.connection.bt

import com.juul.kable.Bluetooth
import io.rebble.libpebblecommon.di.LibPebbleCoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

interface BluetoothStateProvider {
    fun init()
    val state: StateFlow<BluetoothState>
}

enum class BluetoothState {
    Enabled,
    Disabled,
    ;

    fun enabled(): Boolean = this == Enabled
}

class RealBluetoothStateProvider(
    private val libPebbleCoroutineScope: LibPebbleCoroutineScope,
) : BluetoothStateProvider {
    private val _state = MutableStateFlow(BluetoothState.Disabled)
    override val state: StateFlow<BluetoothState> = _state.asStateFlow()

    override fun init() {
        libPebbleCoroutineScope.launch {
            Bluetooth.availability.collect {
                when (it) {
                    is Bluetooth.Availability.Available -> _state.value = BluetoothState.Enabled
                    is Bluetooth.Availability.Unavailable -> _state.value = BluetoothState.Disabled
                    else -> throw IllegalArgumentException("not sure why it's making me put an else here")
                }
            }
        }
    }
}