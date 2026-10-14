package coredevices.pebble.firmware

import com.russhwolf.settings.MapSettings
import coredevices.analytics.CoreAnalytics
import coredevices.api.EngDashLatestResult
import coredevices.api.EngDashOtaApi
import coredevices.pebble.Platform
import coredevices.pebble.services.EngDashOta
import coredevices.pebble.services.Memfault
import coredevices.util.CoreConfig
import coredevices.util.CoreConfigFlow
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.rebble.libpebblecommon.connection.FirmwareUpdateCheckResult
import io.rebble.libpebblecommon.metadata.WatchHardwarePlatform
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertIs
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Boulder: Core-Uhren bekommen Firmware nur aus den Releases von boulder-firmware. Cores
 * dash.repebble.com (EngDashOta) und Cohorts fragt die Pruefung fuer sie nie - sonst boete die App
 * Core-Firmware an, und die ersetzte die Boulder-Firmware. Aeltere Pebbles bleiben wie bei Core bei
 * Cohorts und fragen GitHub nie.
 */
class FirmwareUpdateCheckRoutingTest {

    private val testJson = Json { ignoreUnknownKeys = true }

    private fun failingClient(label: String) = HttpClient(MockEngine { request ->
        fail("$label must not be contacted for this watch, but got a request to ${request.url}")
    }) {
        install(ContentNegotiation) { json(testJson) }
    }

    private fun engDashNeverContacted() = EngDashOta(object : EngDashOtaApi {
        override suspend fun getLatestFirmware(
            deviceSerial: String,
            hardwareVersion: String,
            currentVersion: String?,
            deviceMac: String?,
        ): EngDashLatestResult? = fail("dash.repebble.com must not be asked for a Boulder watch")
    })

    private fun analyticsNeverCalled(): CoreAnalytics = object : CoreAnalytics {
        override fun logEvent(name: String, parameters: Map<String, Any>?) = fail("analytics: $name")
        override suspend fun logHeartbeatState(name: String, value: Boolean, timestamp: Instant) = fail("analytics")
        override suspend fun processHeartbeat() = fail("analytics")
        override fun updateLastConnectedSerial(serial: String?) = fail("analytics")
        override fun updateRingTransferDurationMetric(duration: Duration) = fail("analytics")
        override fun updateRingLifetimeCollectionCount(serial: String, count: Int) = fail("analytics")
        override fun updateRingBatteryVoltage(voltageMilliV: Int) = fail("analytics")
        override fun incrementHeartbeatCounter(name: String) = fail("analytics")
    }

    private val githubBody = releaseList(
        releaseJson("v4.31.0", 10, listOf(normalAsset("asterix", "v4.31.0", "sha256:" + "a".repeat(64), 100))),
    )

    private fun check(cohortsClient: HttpClient, githubClient: HttpClient) = FirmwareUpdateCheck(
        memfault = Memfault(failingClient("Memfault"), MapSettings(), Platform.Android),
        engDashOta = engDashNeverContacted(),
        cohorts = testCohorts(cohortsClient),
        githubReleases = GithubReleases(githubClient, FirmwareArtifactExpectations(), fixedTestClock, TEST_APP_FASSUNG),
        coreConfig = CoreConfigFlow(MutableStateFlow(CoreConfig())),
        coreAnalytics = analyticsNeverCalled(),
        clock = fixedTestClock,
    )

    @Test
    fun coreWatchRoutesToGithubReleasesOnly() = runTest {
        val result = check(failingClient("Cohorts"), jsonRespondingClient(githubBody)).checkForUpdates(
            testWatchInfo(WatchHardwarePlatform.CORE_ASTERIX, "v4.30.0"),
            force = false,
        )
        val update = assertIs<FirmwareUpdateCheckResult.FoundUpdate>(result)
        assertContains(update.url, "normal_asterix_v4.31.0.pbz")
    }

    @Test
    fun legacyWatchRoutesToCohortsOnly() = runTest {
        val result = check(jsonRespondingClient(cohortsBody()), failingClient("GitHub")).checkForUpdates(
            testWatchInfo(WatchHardwarePlatform.PEBBLE_SILK, "v4.0.0"),
            force = false,
        )
        val update = assertIs<FirmwareUpdateCheckResult.FoundUpdate>(result)
        assertContains(update.url, "binaries.rebble.io")
    }
}
