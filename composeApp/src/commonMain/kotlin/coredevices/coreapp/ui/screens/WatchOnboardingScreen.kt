package coredevices.coreapp.ui.screens

import CoreNav
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.touchlab.kermit.Logger
import com.russhwolf.settings.Settings
import coredevices.pebble.Platform
import coredevices.pebble.rememberLibPebble
import coredevices.pebble.services.AppStoreHomeResult
import coredevices.pebble.services.PebbleWebServices
import coredevices.pebble.services.StoreOnboarding
import coredevices.pebble.ui.CommonAppType
import coredevices.pebble.ui.NativeLockerAddUtil
import coredevices.pebble.ui.NativeWatchfaceMainContent
import coredevices.pebble.ui.allCollectionUuids
import coredevices.pebble.ui.asCommonApp
import coredevices.pebble.ui.connectedWatch
import coredevices.ui.PebbleElevatedButton
import io.rebble.libpebblecommon.connection.ConnectedPebbleDevice
import io.rebble.libpebblecommon.connection.ConnectedPebbleDeviceInRecovery
import io.rebble.libpebblecommon.connection.FirmwareUpdateCheckResult
import io.rebble.libpebblecommon.locker.AppType
import io.rebble.libpebblecommon.metadata.WatchType
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

//class OnboardingViewModel : ViewModel() {
//    val stage = mutableStateOf(OnboardingStage.Welcome)
//    val requestedPermissions = mutableStateOf(emptySet<Permission>())
//}

private val logger = Logger.withTag("OnboardingScreen")

@Composable
fun WatchOnboardingScreen(
    coreNav: CoreNav,
) {
//    val viewModel = koinViewModel<OnboardingViewModel>()
    val scope = rememberCoroutineScope()
    val settings: Settings = koinInject()
    val libPebble = rememberLibPebble()
    val connectedWatch = connectedWatch()
    val pebbleWebServices: PebbleWebServices = koinInject()
    val pebbleStoreHomes = remember { mutableStateMapOf<AppType, AppStoreHomeResult?>() }

    if (connectedWatch != null) {
        LaunchedEffect(Unit) {
            val results = pebbleWebServices.fetchPebbleAppStoreHomes(
                connectedWatch.watchType.watchType,
                useCache = true
            )
            pebbleStoreHomes.clear()
            pebbleStoreHomes.putAll(results)
        }
    }

    Scaffold(
        // TODO setStatusBarTheme(dark) - needs white text (but doing that without changes probably breaks stuff)
        containerColor = MaterialTheme.colorScheme.primary,
    ) { windowInsets ->
        Box(modifier = Modifier.padding(windowInsets).fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "Get Started!",
                    fontSize = 35.sp,
                )

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    "Let's get started by setting up your Pebble..",
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(15.dp))

                if (connectedWatch == null) {
                    Text(
                        "Waiting for your Pebble to connect..",
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(15.dp))

                    PebbleElevatedButton(
                        text = "Skip",
                        onClick = { coreNav.goBack() },
                        primaryColor = false,
                    )
                    return@Scaffold
                }

                if (connectedWatch is ConnectedPebbleDeviceInRecovery) {
                    val firmwareUpdateAvailable = connectedWatch.firmwareUpdateAvailable.result
                    if (firmwareUpdateAvailable !is FirmwareUpdateCheckResult.FoundUpdate) {
                        Text(
                            "Checking for PebbleOS updates..",
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(15.dp))

                        PebbleElevatedButton(
                            text = "Skip",
                            onClick = { coreNav.goBack() },
                            primaryColor = false,
                        )
                        return@Scaffold
                    }

                    Text(
                        "Update your watch to the latest version of PebbleOS",
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(15.dp))

                    PebbleElevatedButton(
                        text = "Update PebbleOS",
                        onClick = {
                            logger.d { "Starting firmware update from onboarding screen" }
                            connectedWatch.updateFirmware(firmwareUpdateAvailable)
                        },
                        icon = Icons.Default.SystemUpdateAlt,
                        contentDescription = "Update PebbleOS",
                        primaryColor = true,
                        modifier = Modifier.padding(vertical = 5.dp),
                    )

                    Spacer(modifier = Modifier.height(15.dp))

                    PebbleElevatedButton(
                        text = "Skip",
                        onClick = { coreNav.goBack() },
                        primaryColor = false,
                    )
                    return@Scaffold
                }


                if (connectedWatch is ConnectedPebbleDevice) {
                    OnboardingAppCarousel(
                        header = "Add some Watchfaces",
                        storeHome = pebbleStoreHomes[AppType.Watchface],
                        connectedWatch,
                    )
                    OnboardingAppCarousel(
                        header = "Add some Watchapps",
                        storeHome = pebbleStoreHomes[AppType.Watchapp],
                        connectedWatch,
                    )
                }

                PebbleElevatedButton(
                    text = "Done",
                    onClick = { coreNav.goBack() },
                    primaryColor = false,
                )
            }
        }
    }
}

@Composable
fun OnboardingAppCarousel(
    header: String,
    storeHome: AppStoreHomeResult?,
    watch: ConnectedPebbleDevice,
) {
    if (storeHome == null) {
        return
    }
    val platform: Platform = koinInject()
    val allCollectionUuids = allCollectionUuids()
    val nativeLockerAddUtil: NativeLockerAddUtil = koinInject()
    val watchType = watch.watchType.watchType
    val apps = remember(storeHome, watchType, allCollectionUuids) {
        storeHome.result.onboarding?.forType(watchType)?.mapNotNull { appId ->
            storeHome.result.applications.find { app ->
                app.id == appId
            }?.asCommonApp(
                watchType,
                platform,
                storeHome.source,
                storeHome.result.categories
            )
        }?.filter {
            it.isNativelyCompatible && it.uuid !in allCollectionUuids
        }?.take(5)
    }
    if (apps.isNullOrEmpty()) {
        return
    }
    Text(
        header,
        textAlign = TextAlign.Center
    )

    Spacer(modifier = Modifier.height(15.dp))

    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        contentPadding = PaddingValues(horizontal = 5.dp),
    ) {
        items(apps, key = { it.uuid } ) { entry ->
            var added by remember { mutableStateOf(false) }
            val commonAppStore = entry.commonAppType as? CommonAppType.Store ?: return@items
            Card(
                modifier = Modifier.padding(3.dp)
                    .width(125.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    NativeWatchfaceMainContent(entry = entry, highlightInLocker = false, topBarParams = null)
                    PebbleElevatedButton(
                        text = "Add",
                        onClick = {
                            added = true
                            GlobalScope.launch {
                                val addResult = nativeLockerAddUtil.addAppToLocker(
                                    commonAppStore,
                                    commonAppStore.storeSource
                                )
                                logger.v { "Add to locker from watch onboarding ${commonAppStore.storeApp?.title} result=$addResult" }
                            }
                        },
                        primaryColor = false,
                        enabled = !added,
                    )
                    Spacer(modifier = Modifier.height(5.dp))
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(15.dp))
}

fun StoreOnboarding.forType(watchType: WatchType): List<String>? = when (watchType) {
    WatchType.APLITE -> aplite
    WatchType.BASALT -> basalt
    WatchType.CHALK -> chalk
    WatchType.DIORITE -> diorite
    WatchType.EMERY -> emery
    WatchType.FLINT -> flint
    WatchType.GABBRO -> gabbro
}