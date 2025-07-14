package io.rebble.libpebblecommon.io.rebble.libpebblecommon.notification

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.database.entity.ChannelGroup
import io.rebble.libpebblecommon.di.LibPebbleCoroutineScope
import io.rebble.libpebblecommon.notification.LibPebbleNotificationListener
import io.rebble.libpebblecommon.notification.NotificationListenerConnection
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.uuid.Uuid

class AndroidPebbleNotificationListenerConnection(
    private val libPebbleCoroutineScope: LibPebbleCoroutineScope,
    private val context: Application,
    private val notificationHandler: NotificationHandler,
) : NotificationListenerConnection {
    companion object {
        const val ACTION_BIND_LOCAL =
            "io.rebble.libpebblecommon.notification.LibPebbleNotificationListener.BIND_LOCAL"
    }
    private val logger = Logger.withTag("AndroidPebbleNotificationListenerConnection")

    private val _service = MutableStateFlow<LibPebbleNotificationListener?>(null)
    private var isBound = false
    private var bindingDied = false
    val notificationSendQueue = _service
        .filterNotNull()
        .flatMapLatest { notificationHandler.notificationSendQueue.consumeAsFlow() }
    val notificationDeleteQueue = _service
        .filterNotNull()
        .flatMapLatest { notificationHandler.notificationDeleteQueue.consumeAsFlow() }
    val notificationListenerContext: Flow<Context> = _service.filterNotNull()
        .map { it }

    fun getNotificationAction(itemId: Uuid, actionId: UByte): LibPebbleNotificationAction? {
        return notificationHandler.getNotificationAction(itemId, actionId)
    }

    suspend fun dismissNotification(itemId: Uuid) {
        val service = getService()
        if (service == null) {
            logger.w { "Couldn't get service to dismiss notification" }
            return
        }
        service.cancelNotification(itemId)
    }

    suspend fun getChannelsForApp(packageName: String): List<ChannelGroup> {
        val service = getService()
        if (service == null) {
            logger.w { "Couldn't get service to dismiss notification" }
            return emptyList()
        }
        return service.getChannelsForApp(packageName)
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

    suspend fun getService(): LibPebbleNotificationListener? {
        if (!isBound) {
            bind(context)
        }
        return try {
            withTimeout(5_000L) {
                _service.value ?: _service.filterNotNull().first()
            }
        } catch (e: TimeoutCancellationException) {
            throw IllegalStateException("Timed out waiting for service", e)
        }
    }

    override fun init(libPebble: LibPebble) {
        if (!bind(context)) {
            logger.w { "Couldn't bind to LibPebbleNotificationListener on init" }
        }
        logger.d { "LibPebbleNotificationListener bound" }
        notificationSendQueue.onEach {
            libPebble.sendNotification(it.toTimelineNotification())
        }.launchIn(libPebbleCoroutineScope)
        notificationDeleteQueue.onEach {
            libPebble.deleteNotification(it)
        }.launchIn(libPebbleCoroutineScope)
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