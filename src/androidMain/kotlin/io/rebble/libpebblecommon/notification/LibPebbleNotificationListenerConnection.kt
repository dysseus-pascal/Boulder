package io.rebble.libpebblecommon.io.rebble.libpebblecommon.notification

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.notification.LibPebbleNotificationListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withTimeout
import kotlin.uuid.Uuid

object LibPebbleNotificationListenerConnection {
    const val ACTION_BIND_LOCAL = "io.rebble.libpebblecommon.notification.LibPebbleNotificationListener.BIND_LOCAL"
    private val logger = Logger.withTag(LibPebbleNotificationListenerConnection::class.simpleName!!)

    private val _service = MutableStateFlow<LibPebbleNotificationListener?>(null)
    private var isBound = false
    private var bindingDied = false
    val notificationSendQueue = _service
        .filterNotNull()
        .flatMapLatest { it.notificationHandler.notificationSendQueue.consumeAsFlow() }
    val notificationDeleteQueue = _service
        .filterNotNull()
        .flatMapLatest { it.notificationHandler.notificationDeleteQueue.consumeAsFlow() }
    val notificationListenerContext: Flow<Context> = _service.filterNotNull()
        .map { it }

    suspend fun getNotificationAction(itemId: Uuid, actionId: UByte): LibPebbleNotificationAction? {
        val service = getService()
        return service.notificationHandler.getNotificationAction(itemId, actionId)
    }

    suspend fun dismissNotification(itemId: Uuid) {
        val service = getService()
        service.cancelNotification(itemId)
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            logger.d { "onServiceConnected() $name" }
            if (service is LibPebbleNotificationListener.LocalBinder) {
                _service.value = service.getService()
            } else {
                error("Invalid service binding")
            }
            bindingDied = false
            isBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            logger.d { "onServiceDisconnected() $name" }
            isBound = false
        }

        override fun onBindingDied(name: ComponentName?) {
            logger.d { "onBindingDied() $name" }
            _service.value = null
            bindingDied = true
        }

        override fun onNullBinding(name: ComponentName?) {
            logger.e { "onNullBinding() $name" }
            _service.value = null
        }
    }

    suspend fun getService(): LibPebbleNotificationListener {
        check(!bindingDied) { "Binding died" }
        return withTimeout(5_000L) {
            _service.value ?: _service.filterNotNull().first()
        }
    }

    fun init(context: Context, scope: CoroutineScope, libPebble: LibPebble) {
        check(bind(context)) { "Failed to bind to LibPebbleNotificationListener" }
        logger.d { "LibPebbleNotificationListener bound" }
        notificationSendQueue.onEach {
            libPebble.sendNotification(it.toTimelineItem())
        }.launchIn(scope)
        notificationDeleteQueue.onEach {
            libPebble.deleteNotification(it)
        }.launchIn(scope)
    }

    private fun bind(context: Context): Boolean {
        if (isBound) {
            logger.d { "Already bound to LibPebbleNotificationListener" }
            return true
        }
        val intent = Intent(context, LibPebbleNotificationListener::class.java)
        intent.action = ACTION_BIND_LOCAL
        return context.bindService(
            intent,
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )
    }

    fun unbind(context: Context) {
        context.unbindService(serviceConnection)
    }
}