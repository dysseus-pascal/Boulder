package io.rebble.libpebblecommon.connection

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import io.rebble.libpebblecommon.LibPebbleConfig
import io.rebble.libpebblecommon.calls.Call
import io.rebble.libpebblecommon.connection.bt.BluetoothState
import io.rebble.libpebblecommon.connection.endpointmanager.timeline.CustomTimelineActionHandler
import io.rebble.libpebblecommon.database.asMillisecond
import io.rebble.libpebblecommon.database.entity.CalendarEntity
import io.rebble.libpebblecommon.database.entity.ChannelGroup
import io.rebble.libpebblecommon.database.entity.ChannelItem
import io.rebble.libpebblecommon.database.entity.MuteState
import io.rebble.libpebblecommon.database.entity.NotificationAppItem
import io.rebble.libpebblecommon.database.entity.TimelineNotification
import io.rebble.libpebblecommon.locker.AppPlatform
import io.rebble.libpebblecommon.locker.AppProperties
import io.rebble.libpebblecommon.locker.AppType
import io.rebble.libpebblecommon.locker.LockerWrapper
import io.rebble.libpebblecommon.metadata.WatchType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.datetime.Instant
import kotlinx.io.files.Path
import kotlin.random.Random
import kotlin.random.nextInt
import kotlin.uuid.Uuid

class FakeLibPebble : LibPebble {
    override fun init() {
        // No-op
    }

    override val watches: PebbleDevices = MutableStateFlow(fakeWatches())

    override val config: StateFlow<LibPebbleConfig> = MutableStateFlow(LibPebbleConfig())

    override fun updateConfig(config: LibPebbleConfig) {
        // No-op
    }

    override suspend fun sendNotification(
        notification: TimelineNotification,
        actionHandlers: Map<UByte, CustomTimelineActionHandler>?
    ) {
        // No-op
    }

    override suspend fun deleteNotification(itemId: Uuid) {
        // No-op
    }

    override suspend fun sendPing(cookie: UInt) {
        // No-op
    }

    override suspend fun launchApp(uuid: Uuid) {
        // No-op
    }

    override fun doStuffAfterPermissionsGranted() {
        // No-op
    }

    // Scanning interface
    override val bluetoothEnabled: StateFlow<BluetoothState> =
        MutableStateFlow(BluetoothState.Disabled)

    override fun startBleScan() {
        // No-op
    }

    override fun stopBleScan() {
        // No-op
    }

    override fun startClassicScan() {
        // No-op
    }

    override fun stopClassicScan() {
        // No-op
    }

    // RequestSync interface
    override fun requestLockerSync(): Deferred<Unit> {
        return CompletableDeferred(Unit)
    }

    // LockerApi interface
    override suspend fun sideloadApp(pbwPath: Path) {
        // No-op
    }

    val locker = MutableStateFlow(fakeLockerEntries())

    override fun getLocker(): Flow<List<LockerWrapper>> {
        return locker
    }

    override suspend fun setAppOrder(id: Uuid, order: Int) {
        // No-op
    }

    val _notificationApps = MutableStateFlow(fakeNotificationApps())

    override val notificationApps: Flow<List<NotificationAppItem>> = _notificationApps

    override fun updateNotificationAppMuteState(packageName: String, muteState: MuteState) {
        // No-op
    }

    override fun updateNotificationChannelMuteState(
        packageName: String,
        channelId: String,
        muteState: MuteState
    ) {
        // No-op
    }

    override suspend fun getAppIcon(packageName: String): ImageBitmap? {
        // Return a green square as a placeholder
        val width = 48
        val height = 48
        val buffer = IntArray(width * height) { Color.Green.toArgb() }
        return ImageBitmap(width, height).apply { readPixels(buffer) }
    }

    // CallManagement interface
    override val currentCall: MutableStateFlow<Call?> = MutableStateFlow(null)

    // Calendar interface
    override fun calendars(): Flow<List<CalendarEntity>> {
        return emptyFlow()
    }

    override fun updateCalendarEnabled(calendarId: Int, enabled: Boolean) {
        // No-op
    }

    // OtherPebbleApps interface
    override val otherPebbleCompanionAppsInstalled: StateFlow<List<OtherPebbleApp>> =
        MutableStateFlow(emptyList())
}

fun fakeWatches(): List<PebbleDevice> {
    return buildList {
        for (i in 1..3) {
            add(fakeWatch())
        }
    }
}

fun fakeWatch(): PebbleDevice {
    val num = Random.nextInt(1111, 9999)
    return object : DiscoveredPebbleDevice {
        override val transport: Transport = Transport.BluetoothTransport.BleTransport(
            identifier = randomMacAddress().asPebbleBluetoothIdentifier(),
            name = "Core $num",
        )

        override fun connect(uiContext: UIContext?) {
        }
    }
}

fun fakeNotificationApps(): List<NotificationAppItem> {
    return buildList {
        for (i in 1..50) {
            add(fakeNotificationApp())
        }
    }
}

fun fakeNotificationApp(): NotificationAppItem {
    return NotificationAppItem(
        name = randomName(),
        packageName = randomName(),
        muteState = if (Random.nextBoolean()) MuteState.Always else MuteState.Never,
        channelGroups = if (Random.nextBoolean()) emptyList() else fakeChannelGroups(),
        stateUpdated = Instant.DISTANT_PAST.asMillisecond(),
        lastNotified = Instant.DISTANT_PAST.asMillisecond(),
    )
}

fun fakeChannelGroups(): List<ChannelGroup> {
    return buildList {
        for (i in 1..Random.nextInt(2,5)) {
            add(ChannelGroup(
                id = randomName(),
                name = randomName(),
                channels = fakeChannels(),
            ))
        }
    }
}

fun fakeChannels(): List<ChannelItem> {
    return buildList {
        for (i in 1..Random.nextInt(1, 8)) {
            add(
                ChannelItem(
                    id = randomName(),
                    name = randomName(),
                    muteState = if (Random.nextBoolean()) MuteState.Always else MuteState.Never,
                )
            )
        }
    }
}

fun fakeLockerEntries(): List<LockerWrapper> {
    return buildList {
        for (i in 1..40) {
            add(fakeLockerEntry())
        }
    }
}

fun randomName(): String {
    val length = Random.nextInt(5, 20)
    val allowedChars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    return (1..length)
        .map { allowedChars[Random.nextInt(0, allowedChars.length)] }
        .joinToString("")
}

fun randomMacAddress(): String {
    val allowedChars = "0123456789ABCDEF"
    return (1..6).joinToString(":") {
        (1..2).map {
            allowedChars[Random.nextInt(
                0,
                allowedChars.length
            )]
        }.joinToString("")
    }
}

fun fakeLockerEntry(): LockerWrapper {
    val appType = if (Random.nextBoolean()) AppType.Watchface else AppType.Watchapp
    return LockerWrapper.NormalApp(
        properties = AppProperties(
            id = Uuid.random(),
            type = appType,
            title = randomName(),
            developerName = "Core Devices",
            platforms = listOf(
                AppPlatform(
                    watchType = WatchType.CHALK,
                    screenshotImageUrl = "https://assets2.rebble.io/180x180/ZiFWSDWHTwearl6RNBNA",
                    listImageUrl = "https://assets2.rebble.io/exact/180x180/LVK5AGVeS1ufpR8NNk7C",
                    iconImageUrl = "",
                ),
                AppPlatform(
                    watchType = WatchType.DIORITE,
                    screenshotImageUrl = "https://assets2.rebble.io/144x168/u8q7BQv0QjGkLXy4WydA",
                    listImageUrl = "https://assets2.rebble.io/exact/144x168/LVK5AGVeS1ufpR8NNk7C",
                    iconImageUrl = "",
                ), AppPlatform(
                    watchType = WatchType.BASALT,
                    screenshotImageUrl = "https://assets2.rebble.io/144x168/LVK5AGVeS1ufpR8NNk7C",
                    listImageUrl = "https://assets2.rebble.io/exact/144x168/LVK5AGVeS1ufpR8NNk7C",
                    iconImageUrl = "",
                ),
                AppPlatform(
                    watchType = WatchType.APLITE,
                    screenshotImageUrl = "https://assets2.rebble.io/144x168/7fNxWcZ3RZ2clRNWA68Q",
                    listImageUrl = "https://assets2.rebble.io/exact/144x168/LVK5AGVeS1ufpR8NNk7C",
                    iconImageUrl = "",
                )
            ),
        ),
        sideloaded = false,
        configurable = Random.nextBoolean(),
        sync = true,
    )
}
