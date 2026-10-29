package coredevices.pebble.firmware

import com.russhwolf.settings.MapSettings
import coredevices.util.CoreConfig
import coredevices.util.CoreConfigFlow
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.connection.FakeConnectedDevice
import io.rebble.libpebblecommon.connection.FakeLibPebble
import io.rebble.libpebblecommon.connection.FirmwareUpdateCheckResult
import io.rebble.libpebblecommon.connection.FirmwareUpdateCheckState
import io.rebble.libpebblecommon.connection.PebbleDevice
import io.rebble.libpebblecommon.connection.asPebbleBleIdentifier
import io.rebble.libpebblecommon.connection.endpointmanager.FirmwareUpdater.FirmwareUpdateStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Pins the security-relevant routing in RealFirmwareUpdateUiTracker: a
 * notification-triggered install must enter the fork's verified pipeline.
 * An upstream merge resolving updateWatchNow back to the raw
 * updateFirmware(update) call would compile cleanly and silently bypass
 * every verification layer; this test fails instead.
 */
class FirmwareUpdateUiTrackerTest {

    // AppContext's Android actual wraps a real Context that updateWatchNow
    // never dereferences; allocating it without running the constructor
    // avoids pulling in Robolectric for a field the tested path cannot
    // touch.
    private fun bareAppContext(): AppContext {
        val field = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
        field.isAccessible = true
        val unsafe = field.get(null) as sun.misc.Unsafe
        return unsafe.allocateInstance(AppContext::class.java) as AppContext
    }

    @Test
    fun notificationTapRoutesThroughTheVerifiedInstaller() = runTest {
        val tempDir = Path(Files.createTempDirectory("fork-fw-tracker-test").toString())
        val installer = VerifiedFirmwareInstaller(
            httpClient = HttpClient(MockEngine { respond("gone", HttpStatusCode.NotFound) }),
            expectations = FirmwareArtifactExpectations(),
            downloadDirectory = { tempDir },
            watchUpdateStates = {
                MutableStateFlow<FirmwareUpdateStatus?>(FirmwareUpdateStatus.NotInProgress.Idle())
            },
            scope = backgroundScope,
        )
        val update = FirmwareUpdateCheckResult.FoundUpdate(
            version = testFwVersion("v4.31.1"),
            url = "https://release-assets.example.com/normal_asterix_v4.31.1.pbz",
            notes = "",
        )
        val watch = FakeConnectedDevice(
            identifier = "00:11:22:33:44:55".asPebbleBleIdentifier(),
            firmwareUpdateAvailable = FirmwareUpdateCheckState(checkingForUpdates = false, result = update),
            firmwareUpdateState = FirmwareUpdateStatus.NotInProgress.Idle(),
            name = "Test Watch",
            nickname = null,
            connectionFailureInfo = null,
        )
        val libPebble = FakeLibPebble()
        @Suppress("UNCHECKED_CAST")
        (libPebble.watches as MutableStateFlow<List<PebbleDevice>>).value = listOf(watch)

        val tracker = RealFirmwareUpdateUiTracker(
            settings = MapSettings(),
            clock = fixedTestClock,
            appContext = bareAppContext(),
            coreConfigFlow = CoreConfigFlow(MutableStateFlow(CoreConfig())),
            installer = installer,
        )
        tracker.updateWatchNow(libPebble, watch.identifier.asString)

        // Entering the pipeline is observable at its first fail-closed gate:
        // nothing recorded an expectation for this URL, so the fork install
        // state for exactly this watch must become the refusal.
        val failed = withTimeout(5_000) {
            installer.stateFor(watch.identifier).first { it is ForkFirmwareInstallState.Failed }
        }
        assertContains(assertIs<ForkFirmwareInstallState.Failed>(failed).reason, "no integrity data")
    }

    // Boulder: Core laesst Uhren mit Suffix in der Fassung ohne automatisches Update; das darf
    // Boulders Fassungen (vX.Y.Z-bN) nicht treffen, einen Entwicklungsbau aber schon.
    @Test
    fun automatischesUpdateLaeuftAufEinerBoulderFassung() = runTest {
        val installer = installer(backgroundScope)
        val uhr = uhrMitUpdate(laeuft = "v4.42.0-b41")
        tracker(installer, CoreConfig(autoFirmwareUpdate = true)).onConnectedWatch(uhr)

        val failed = withTimeout(5_000) {
            installer.stateFor(uhr.identifier).first { it is ForkFirmwareInstallState.Failed }
        }
        assertContains(assertIs<ForkFirmwareInstallState.Failed>(failed).reason, "no integrity data")
    }

    @Test
    fun keinAutomatischesUpdateAufEinemEntwicklungsbau() = runTest {
        val installer = installer(backgroundScope)
        val uhr = uhrMitUpdate(laeuft = "v4.42.0-b41-2-gabcdef12")
        tracker(installer, CoreConfig(autoFirmwareUpdate = true)).onConnectedWatch(uhr)

        // Der Installer laeuft im backgroundScope; runCurrent fuehrt auch dessen faellige Arbeit aus.
        testScheduler.runCurrent()
        assertEquals(ForkFirmwareInstallState.Idle, installer.stateFor(uhr.identifier).value)
    }

    private fun installer(scope: CoroutineScope): VerifiedFirmwareInstaller {
        val tempDir = Path(Files.createTempDirectory("fork-fw-tracker-test").toString())
        return VerifiedFirmwareInstaller(
            httpClient = HttpClient(MockEngine { respond("gone", HttpStatusCode.NotFound) }),
            expectations = FirmwareArtifactExpectations(),
            downloadDirectory = { tempDir },
            watchUpdateStates = {
                MutableStateFlow<FirmwareUpdateStatus?>(FirmwareUpdateStatus.NotInProgress.Idle())
            },
            scope = scope,
        )
    }

    private fun uhrMitUpdate(laeuft: String) = FakeConnectedDevice(
        identifier = "00:11:22:33:44:55".asPebbleBleIdentifier(),
        firmwareUpdateAvailable = FirmwareUpdateCheckState(
            checkingForUpdates = false,
            result = FirmwareUpdateCheckResult.FoundUpdate(
                version = testFwVersion("v4.43.0-b42"),
                url = "https://release-assets.example.com/normal_asterix_v4.43.0-b42.pbz",
                notes = "",
            ),
        ),
        firmwareUpdateState = FirmwareUpdateStatus.NotInProgress.Idle(),
        name = "Test Watch",
        nickname = null,
        runningFwVersion = laeuft,
        connectionFailureInfo = null,
    )

    private fun tracker(installer: VerifiedFirmwareInstaller, config: CoreConfig) = RealFirmwareUpdateUiTracker(
        settings = MapSettings(),
        clock = fixedTestClock,
        appContext = bareAppContext(),
        coreConfigFlow = CoreConfigFlow(MutableStateFlow(config)),
        installer = installer,
    )
}
