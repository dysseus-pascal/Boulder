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
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.rebble.libpebblecommon.connection.FirmwareUpdateCheckResult
import io.rebble.libpebblecommon.metadata.WatchHardwarePlatform
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.fail
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Boulder: die Firmware-Pruefung fragt GitHub seltener (Cache 5 h, damit der
 * 6-h-Lauf frisch fragt) und bedingt (ETag), ohne dass sich das Angebot aendert.
 */
class FirmwareSparTest {
    private val json = Json { ignoreUnknownKeys = true }
    private var jetzt = TEST_NOW
    private val uhr = object : Clock {
        override fun now(): Instant = jetzt
    }
    private val body = releaseList(
        releaseJson("v4.31.0", 10, listOf(normalAsset("asterix", "v4.31.0", "sha256:" + "a".repeat(64), 100))),
    )
    private val watch = testWatchInfo(WatchHardwarePlatform.CORE_ASTERIX, "v4.30.0")

    /** GitHub mit ETag: kennt der Aufrufer ihn, kommt 304 ohne Inhalt. */
    private fun github(anfragen: MutableList<HttpRequestData>, etag: String? = "W/\"abc\"") =
        HttpClient(MockEngine { request ->
            anfragen += request
            if (etag != null && request.headers[HttpHeaders.IfNoneMatch] == etag) {
                respond("", HttpStatusCode.NotModified)
            } else {
                val kopf = if (etag != null) {
                    headersOf(HttpHeaders.ContentType to listOf("application/json"), HttpHeaders.ETag to listOf(etag))
                } else {
                    headersOf(HttpHeaders.ContentType, "application/json")
                }
                respond(body, HttpStatusCode.OK, kopf)
            }
        }) {
            install(ContentNegotiation) { json(json) }
        }

    @Test
    fun zweiteAbfrageIstBedingtUndBietetDasselbe() = runTest {
        val anfragen = mutableListOf<HttpRequestData>()
        val releases = GithubReleases(github(anfragen), FirmwareArtifactExpectations(), uhr, TEST_APP_FASSUNG)
        val erste = assertIs<FirmwareUpdateCheckResult.FoundUpdate>(releases.getLatestFirmware(watch, FirmwareUpdateChannel.Soaked))
        val zweite = assertIs<FirmwareUpdateCheckResult.FoundUpdate>(releases.getLatestFirmware(watch, FirmwareUpdateChannel.Soaked))
        assertEquals(erste, zweite)
        assertNull(anfragen[0].headers[HttpHeaders.IfNoneMatch])
        assertEquals("W/\"abc\"", anfragen[1].headers[HttpHeaders.IfNoneMatch])
    }

    @Test
    fun ohneEtagWieBisherVolleAbfrage() = runTest {
        val anfragen = mutableListOf<HttpRequestData>()
        val releases = GithubReleases(github(anfragen, etag = null), FirmwareArtifactExpectations(), uhr, TEST_APP_FASSUNG)
        releases.getLatestFirmware(watch, FirmwareUpdateChannel.Soaked)
        releases.getLatestFirmware(watch, FirmwareUpdateChannel.Soaked)
        assertEquals(listOf(null, null), anfragen.map { it.headers[HttpHeaders.IfNoneMatch] })
    }

    @Test
    fun wiederverbindenInnerhalbFuenfStundenFragtNicht() = runTest {
        val anfragen = mutableListOf<HttpRequestData>()
        val check = pruefung(github(anfragen))
        check.checkForUpdates(watch, force = false)
        jetzt += 4.hours + 59.minutes
        check.checkForUpdates(watch, force = false)
        assertEquals(1, anfragen.size)
        // Der 6-h-Lauf startet ab 5,4 h - dann ist der Eintrag abgelaufen.
        jetzt = TEST_NOW + 5.hours + 24.minutes
        check.checkForUpdates(watch, force = false)
        assertEquals(2, anfragen.size)
    }

    @Test
    fun checkForUpdatesInDenEinstellungenFragtSofort() = runTest {
        val anfragen = mutableListOf<HttpRequestData>()
        val check = pruefung(github(anfragen))
        check.checkForUpdates(watch, force = false)
        jetzt += 1.minutes
        check.checkForUpdates(watch, force = true)
        assertEquals(2, anfragen.size)
    }

    private fun pruefung(client: HttpClient): FirmwareUpdateCheck {
        return FirmwareUpdateCheck(
            memfault = Memfault(nieGefragt(), MapSettings(), Platform.Android),
            engDashOta = EngDashOta(object : EngDashOtaApi {
                override suspend fun getLatestFirmware(
                    deviceSerial: String,
                    hardwareVersion: String,
                    currentVersion: String?,
                    deviceMac: String?,
                ): EngDashLatestResult? = fail("dash.repebble.com")
            }),
            cohorts = testCohorts(nieGefragt()),
            githubReleases = GithubReleases(client, FirmwareArtifactExpectations(), uhr, TEST_APP_FASSUNG),
            coreConfig = CoreConfigFlow(MutableStateFlow(CoreConfig())),
            coreAnalytics = keineAnalytics(),
            clock = uhr,
        )
    }

    private fun nieGefragt() = HttpClient(MockEngine { request -> fail("unerwartete Anfrage an ${request.url}") }) {
        install(ContentNegotiation) { json(json) }
    }

    private fun keineAnalytics(): CoreAnalytics = object : CoreAnalytics {
        override fun logEvent(name: String, parameters: Map<String, Any>?) = fail("analytics: $name")
        override suspend fun logHeartbeatState(name: String, value: Boolean, timestamp: Instant) = fail("analytics")
        override suspend fun processHeartbeat() = fail("analytics")
        override fun updateLastConnectedSerial(serial: String?) = fail("analytics")
        override fun updateRingTransferDurationMetric(duration: Duration) = fail("analytics")
        override fun updateRingLifetimeCollectionCount(serial: String, count: Int) = fail("analytics")
        override fun updateRingBatteryVoltage(voltageMilliV: Int) = fail("analytics")
        override fun incrementHeartbeatCounter(name: String) = fail("analytics")
    }
}
