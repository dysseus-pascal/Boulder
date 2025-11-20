package coredevices.pebble.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VerticalAlignTop
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import coredevices.pebble.Platform
import coredevices.pebble.rememberLibPebble
import coredevices.pebble.services.RealPebbleWebServices
import coredevices.ui.PebbleElevatedButton
import io.rebble.libpebblecommon.connection.ConnectedPebbleDevice
import io.rebble.libpebblecommon.connection.KnownPebbleDevice
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.connection.PebbleIdentifier
import io.rebble.libpebblecommon.locker.AppType
import io.rebble.libpebblecommon.locker.SystemApps
import io.rebble.libpebblecommon.metadata.WatchType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

private val logger = Logger.withTag("LockerAppScreen")

class LockerAppViewModel(
    private val pebbleWebServices: RealPebbleWebServices,
    private val platform: Platform,
) : ViewModel() {
    var storeEntry by mutableStateOf<CommonApp?>(null)
    var addedToLocker by mutableStateOf(false)

    fun loadAppFromStore(id: String, watchType: WatchType) {
        viewModelScope.launch {
            val result = pebbleWebServices.fetchAppStoreApp(id, watchType)
            if (result != null) {
                storeEntry = result.data.firstOrNull()?.asCommonApp(watchType, platform)
            }
        }
    }
}

@Composable
fun LockerAppScreen(topBarParams: TopBarParams, uuid: Uuid, navBarNav: NavBarNav, storeId: String?) {
    Box(modifier = Modifier.background(MaterialTheme.colorScheme.background)) {
        val scope = rememberCoroutineScope()
        val libPebble = rememberLibPebble()
        val watchesFiltered = remember {
            libPebble.watches.map {
                it.sortedWith(PebbleDeviceComparator).filterIsInstance<KnownPebbleDevice>()
                    .firstOrNull()
            }
        }
        val lastConnectedWatch by watchesFiltered.collectAsState(null)
        val runningApp by (lastConnectedWatch as? ConnectedPebbleDevice)?.runningApp?.collectAsState(
            null
        ) ?: mutableStateOf(null)
        val appIsRunning = runningApp == uuid
        val platform: Platform = koinInject()
        val connected = lastConnectedWatch is ConnectedPebbleDevice
        val watchType = lastConnectedWatch?.watchType?.watchType ?: WatchType.DIORITE
        val viewModel = koinViewModel<LockerAppViewModel>()
        val webServices = koinInject<RealPebbleWebServices>()
        var showRemoveConfirmDialog by remember { mutableStateOf(false) }
        var loadingToWatch by remember { mutableStateOf(false) }

        val lockerEntryFlow = remember(uuid) { libPebble.getLockerApp(uuid) }
        val lockerEntry by lockerEntryFlow.collectAsState(null)
        val entry = remember(lockerEntry, viewModel.storeEntry) {
            lockerEntry?.asCommonApp(watchType) ?: viewModel.storeEntry
        }

        if (storeId != null) {
            LaunchedEffect(Unit) {
                viewModel.loadAppFromStore(storeId, watchType)
            }
        }

        LaunchedEffect(entry) {
            topBarParams.searchAvailable(false)
            topBarParams.actions {
                if (showRemoveConfirmDialog && entry != null) {
                    AlertDialog(
                        onDismissRequest = { showRemoveConfirmDialog = false },
                        title = { Text("Remove ${entry.title}?") },
                        text = { Text("Are you sure you want to remove this app from your Pebble?") },
                        confirmButton = {
                            TextButton(onClick = {
                                // Don't use local scope: that will die because we moved back
                                GlobalScope.launch {
                                    showRemoveConfirmDialog = false
                                    logger.d { "removing app ${entry.uuid}" }
                                    topBarParams.showSnackbar("Removing ${entry.title}")
                                    withContext(Dispatchers.Main) {
                                        navBarNav.goBack()
                                    }
                                    val removed = libPebble.removeApp(entry.uuid)
                                    logger.d { "removed = $removed" }
                                    topBarParams.showSnackbar("Removed ${entry.title}")
                                }
                            }) { Text("Remove") }
                        },
                        dismissButton = {
                            TextButton(onClick = {
                                showRemoveConfirmDialog = false
                            }) { Text("Cancel") }
                        }
                    )
                }
                val showRemove = entry?.commonAppType is CommonAppType.Locker
                if (showRemove) {
                    TopBarIconButtonWithToolTip(
                        onClick = { showRemoveConfirmDialog = true },
                        icon = Icons.Filled.Delete,
                        description = "Remove",
                        enabled = !showRemoveConfirmDialog,
                    )
                }
            }
            topBarParams.title(entry?.type?.name ?: "")
            topBarParams.canGoBack(true)
            topBarParams.goBack.collect {
                navBarNav.goBack()
            }
        }
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 5.dp)
        ) {
            entry?.let { entry ->
                Row {
                    AppImage(
                        entry = entry,
                        modifier = Modifier.padding(10.dp),
                        size = 140.dp,
                    )
                    Column(modifier = Modifier.padding(horizontal = 5.dp)) {
                        Text(
                            text = entry.title,
                            fontSize = 22.sp,
                            modifier = Modifier.padding(vertical = 5.dp),
                        )
                        if (entry.hearts != null) {
                            Row {
                                Icon(
                                    Icons.Outlined.Favorite,
                                    contentDescription = "Hearts",
                                    modifier = Modifier.padding(vertical = 5.dp),
                                )
                                Text(
                                    text = "${entry.hearts}",
                                    modifier = Modifier.padding(vertical = 5.dp),
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        val watchName = lastConnectedWatch?.displayName() ?: ""
                        val onWatchText = if (entry.isCompatible && entry.isSynced()) {
                            if (appIsRunning) {
                                "Running On Watch $watchName"
                            } else {
                                "On Watch $watchName"
                            }
                        } else if (entry.isCompatible) {
                            "Not On Watch $watchName"
                        } else {
                            "Not Compatible with $watchName"
                        }
                        Text(
                            onWatchText,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(vertical = 5.dp),
                        )
                    }
                }

                val showCompanionWarning = platform == Platform.Android && entry.androidCompanion != null
                if (showCompanionWarning) {
                    ElevatedCard(
                        modifier = Modifier.padding(10.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        ),
                        elevation = CardDefaults.cardElevation(
                            defaultElevation = 6.dp
                        ),
                    ) {
                        Text(
                            "This app requires an Android companion to be installed - this is not yet supported in the Pebble app",
                            modifier = Modifier.padding(10.dp),
                        )
                    }
                }

                val connectedIdentifier = lastConnectedWatch?.identifier
                val showStartApp =
                    entry.isCompatible && entry.commonAppType is CommonAppType.Locker && !appIsRunning
                if (showStartApp) {
                    val loadToWatchText = if (entry.isSynced()) {
                        ""
                    } else {
                        "Load To Watch & "
                    }
                    val text = if (entry.type == AppType.Watchapp) {
                        "${loadToWatchText}Start App"
                    } else {
                        "${loadToWatchText}Start Watchface"
                    }
                    PebbleElevatedButton(
                        text = text,
                        onClick = {
                            // Global scope because it could take a second to download/sync/load app
                            GlobalScope.launch {
                                if (connectedIdentifier != null) {
                                    loadingToWatch = true
                                    libPebble.launchApp(
                                        entry,
                                        topBarParams,
                                        connectedIdentifier
                                    )
                                    loadingToWatch = false
                                }
                            }
                        },
                        enabled = connected && !loadingToWatch,
                        icon = Icons.Default.PlayCircle,
                        contentDescription = text,
                        primaryColor = true,
                        modifier = Modifier.padding(5.dp),
                    )
                }
                if (entry.commonAppType is CommonAppType.Store && !viewModel.addedToLocker) {
                    PebbleElevatedButton(
                        text = "Add To Locker",
                        onClick = {
                            // Global scope because it could take a second to download/sync/load app
                            GlobalScope.launch {
                                viewModel.addedToLocker = true
                                webServices.addToLocker(entry.uuid.toString())
                                libPebble.requestLockerSync()
                            }
                        },
                        icon = Icons.Default.Add,
                        contentDescription = "Add To Locker",
                        primaryColor = true,
                        modifier = Modifier.padding(5.dp),
                    )
                }

                Row {
                    if (entry.hasSettings()) {
                        val settingsEnabled =
                            remember(
                                entry,
                                connected
                            ) { entry.isCompatible && entry.isSynced() && (connected || entry.commonAppType is CommonAppType.System) }

                        PebbleElevatedButton(
                            text = "Settings",
                            onClick = {
                                scope.launch {
                                    loadingToWatch = true
                                    entry.showSettings(navBarNav, libPebble, topBarParams)
                                    loadingToWatch = false
                                }
                            },
                            enabled = settingsEnabled && !loadingToWatch,
                            icon = Icons.Default.Settings,
                            contentDescription = "Settings",
                            primaryColor = false,
                            modifier = Modifier.padding(5.dp),
                        )
                    }
                    if (entry.commonAppType is CommonAppTypeLocal && entry.commonAppType.order > 0) {
                        PebbleElevatedButton(
                            text = "Move To Top",
                            onClick = {
                                scope.launch {
                                    libPebble.setAppOrder(entry.uuid, -1)
                                }
                            },
                            icon = Icons.Default.VerticalAlignTop,
                            contentDescription = "Move To Top",
                            primaryColor = false,
                            modifier = Modifier.padding(5.dp),
                        )
                    }
                }

                if (entry.description != null) {
                    Text(
                        text = entry.description,
                        modifier = Modifier.padding(vertical = 8.dp, horizontal = 5.dp),
                        fontSize = 12.sp
                    )
                }

                PropertyRow(name = "DEVELOPER", value = entry.developerName)
                entry.category?.let { category ->
                    PropertyRow(name = "CATEGORY", value = category)
                }
                entry.version?.let { version ->
                    val sideloadedText = if (entry.commonAppType is CommonAppType.Locker && entry.commonAppType.sideloaded) {
                        " (sideloaded)"
                    } else {
                        ""
                    }
                    PropertyRow(name = "VERSION", value = "$version$sideloadedText")
                }
            }
        }
    }
}

@Composable
private fun PropertyRow(name: String, value: String) {
    Row(modifier = Modifier.padding(5.dp)) {
        Text(
            text = name,
            color = Color.Gray,
            modifier = Modifier.width(120.dp),
            maxLines = 1
        )
        Text(text = value, modifier = Modifier.padding(start = 8.dp), maxLines = 1)
    }
}

suspend fun LibPebble.launchApp(
    entry: CommonApp,
    topBarParams: TopBarParams,
    connectedIdentifier: PebbleIdentifier
): Boolean {
    logger.d { "launchApp: ${entry.uuid} - ${entry.title}" }
    val typeText = when (entry.type) {
        AppType.Watchface -> "Watchface"
        AppType.Watchapp -> "WatchApp"
    }
    if (!entry.isSynced()) {
        try {
            withTimeout(15.seconds) {
                topBarParams.showSnackbar("Waiting to sync $typeText to watch...")
                setAppOrder(entry.uuid, -1)
            }
        } catch (_: TimeoutCancellationException) {
            logger.w { "timed out waiting for order change to sync to watch" }
            topBarParams.showSnackbar("$typeText failed to sync to watch")
            return false
        }
    }
    if (!waitUntilAppSyncedToWatch(entry.uuid, connectedIdentifier, 15.seconds)) {
        logger.w { "timed out waiting for blobdb item to sync to watch" }
        topBarParams.showSnackbar("$typeText failed to sync to watch")
        return false
    }
    // Give it a small bit of time to settle
    delay(0.5.seconds)
    topBarParams.showSnackbar("Loading $typeText")
    val launched = withTimeoutOrNull(30.seconds) {
        launchApp(entry.uuid)
        true
    }
    logger.d { "launched = $launched" }
    if (launched == null) {
        topBarParams.showSnackbar("$typeText failed to load")
        return false
    }
    return true
}

fun SnackbarHostState.showSnackbar(scope: CoroutineScope, message: String) {
    scope.launch {
        showSnackbar(message)
    }
}

fun CommonApp.hasSettings(): Boolean = when (commonAppType) {
    is CommonAppType.Locker -> commonAppType.configurable
    is CommonAppType.System -> when (commonAppType.app) {
        SystemApps.Calendar -> true
    }
    is CommonAppType.Store -> false
}

suspend fun CommonApp.showSettings(
    navBarNav: NavBarNav,
    libPebble: LibPebble,
    topBarParams: TopBarParams,
) {
    when (commonAppType) {
        is CommonAppType.Locker -> {
            val watch = libPebble.watches.value.filterIsInstance<ConnectedPebbleDevice>()
                .firstOrNull()
            //TODO: Handle multiple watches connected, selector?
            if (watch == null) {
                logger.w("No connected watch found, cannot show settings")
                return
            }
            val session = if (watch.currentPKJSSession.value?.uuid == uuid) {
                watch.currentPKJSSession.value
            } else {
                logger.d("Launching app $uuid for pkjs settings...")
                libPebble.launchApp(this, topBarParams, watch.identifier)
                watch.currentPKJSSession.first { it?.uuid == uuid }
            }
            if (session?.uuid != uuid) {
                logger.e("PKJS session UUID mismatch: expected $uuid, got ${session?.uuid}")
            } else {
                logger.d { "Opening app settings for $uuid" }
                val url = session.requestConfigurationUrl()
                url ?: run {
                    logger.e("No configuration URL returned for app $uuid")
                    return
                }
                logger.d { "Got app settings URL" }
                navBarNav.navigateTo(
                    PebbleRoutes.WatchappSettingsRoute(
                        watch.identifier.asString,
                        title = title,
                        url = url
                    )
                )
            }
        }

        is CommonAppType.System -> when (commonAppType.app) {
            SystemApps.Calendar -> {
                logger.d("show calendar settings...")
                navBarNav.navigateTo(PebbleRoutes.CalendarsRoute)
            }
        }

        is CommonAppType.Store -> Unit
    }
}