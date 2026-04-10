package coredevices.ring.service

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import co.touchlab.kermit.Logger
import coredevices.haversine.KMPHaversineSatelliteManager
import coredevices.ring.service.recordings.RecordingProcessingQueue
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class RingService: Service(), KoinComponent {
    companion object {
        const val NOTIFICATION_CHANNEL_ID = "ring"
        const val DEBUG_NOTIFICATION_CHANNEL_ID = "ring_debug"
        const val NOTIFICATION_CHANNEL_NAME = "Ring Service"
        const val DEBUG_NOTIFICATION_CHANNEL_NAME = "Ring Debug"
        const val ACTION_STOP = "STOP"

        private val logger = Logger.withTag("RingService")
    }

    private val satelliteManager: KMPHaversineSatelliteManager by inject()
    private lateinit var notificationManagerCompat: NotificationManagerCompat
    private val scope: RecordingBackgroundScope by inject()
    private var recordingDebugNotificationJob: Job? = null
    private var ringSyncJob: Job? = null
    private val ringSync: RingSync by inject()
    private val ringBackgroundManager: RingBackgroundManager by inject()
    private val indexNotificationManager: IndexNotificationManager by inject()
    private val recordingProcessingQueue: RecordingProcessingQueue by inject()
    private var firstRun: Boolean = true

    private fun handleIntent(intent: Intent) {
        when (intent.action) {
            ACTION_STOP -> {
                logger.i { "Stopping service due to intent request" }
                stopSelf()
            }
        }
    }

    private fun startRecordingDebugNotificationJob() {
        recordingDebugNotificationJob?.cancel()
        recordingDebugNotificationJob = scope.launch {
            val notificationChannel = NotificationChannelCompat.Builder(
                DEBUG_NOTIFICATION_CHANNEL_ID,
                NotificationManager.IMPORTANCE_DEFAULT)
                .setName(DEBUG_NOTIFICATION_CHANNEL_NAME)
                .build()
            notificationManagerCompat.createNotificationChannel(notificationChannel)

            indexNotificationManager.startNotificationProcessingJob(scope)
        }
    }

    private fun startRingSyncJob() {
        if (firstRun) {
            logger.i { "Starting ring sync job for the first time, resuming pending recording processing tasks" }
            firstRun = false
            recordingProcessingQueue.resumePendingTasks()
        }
        if (ringSyncJob?.isActive == true) {
            logger.w { "Ring sync job is already running" }
            return
        }
        ringSyncJob = scope.launch {
            ringSync.startSyncJob(satelliteManager)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        logger.v { "onStartCommand()" }
        if (intent != null) {
            handleIntent(intent)
        }
        notificationManagerCompat = NotificationManagerCompat.from(this)
        val notificationChannel = NotificationChannelCompat.Builder(
            NOTIFICATION_CHANNEL_ID,
            NotificationManager.IMPORTANCE_MIN)
        .setName(NOTIFICATION_CHANNEL_NAME)
        .build()
        notificationManagerCompat.createNotificationChannel(notificationChannel)

        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Ring Service")
            .setContentText("Ring Service is running")
            .setOngoing(true)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
        ServiceCompat.startForeground(
            this,
            1,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            } else {
                0
            }
        )
        startRingSyncJob()
        startRecordingDebugNotificationJob()
        ringBackgroundManager.onServiceStarted()
        return START_STICKY
    }



    override fun onDestroy() {
        ringBackgroundManager.onServiceStopped()
        runBlocking {
            recordingDebugNotificationJob?.cancelAndJoin()
            ringSync.stop()
        }
        scope.cancel("Service destroyed")
        notificationManagerCompat.cancel(1)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}