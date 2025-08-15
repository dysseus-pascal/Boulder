package io.rebble.libpebblecommon.connection.bt

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.AppContext

private val logger = Logger.withTag("registerNativeBtStateLogging")

actual fun registerNativeBtStateLogging(appContext: AppContext) {
    val adapter = BluetoothAdapter.getDefaultAdapter()
    logger.v { "btState native at init: ${adapter.state}" }
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            logger.v { "btState native changed: ${adapter.state}" }
        }
    }
    val intentFilter = IntentFilter(ACTION_STATE_CHANGED)
    appContext.context.registerReceiver(receiver, intentFilter)
}