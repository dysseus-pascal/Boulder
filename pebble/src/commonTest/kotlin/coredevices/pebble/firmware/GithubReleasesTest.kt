package coredevices.pebble.firmware

import CoreAppVersion
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
import kotlinx.io.IOException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the GitHub-releases checker against the traps found during research:
 * GitHub's Latest badge and publish dates must never drive selection, factory
 * tags are never offered, an equal version is never re-offered (upstream's
 * timestamp tiebreak would re-offer forever), unverifiable assets are treated
 * as absent, and the request must carry zero device data.
 */
class GithubReleasesTest {

    private fun digest(char: Char) = "sha256:" + char.toString().repeat(64)
    private fun hex(char: Char) = char.toString().repeat(64)

    /** Two main-line minors, one soaked; a factory hotfix is the newest publish. */
    private fun standardBody() = "[" + listOf(
        releaseJson("v4.9.142.4", 0, listOf(normalAsset("asterix", "v4.9.142.4", digest('d'), 400))),
        releaseJson("v4.31.1", 1, listOf(normalAsset("asterix", "v4.31.1", digest('b'), 222))),
        releaseJson("v4.32.0", 2, listOf(normalAsset("asterix", "v4.32.0", digest('a'), 111))),
        releaseJson("v4.31.0", 10, listOf(normalAsset("asterix", "v4.31.0", digest('c'), 333))),
        releaseJson("v4.30.0", 20, listOf(normalAsset("asterix", "v4.30.0", digest('e'), 555))),
    ).joinToString(",") + "]"

    private fun checker(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
        expectations: FirmwareArtifactExpectations = FirmwareArtifactExpectations(),
        requests: MutableList<HttpRequestData> = mutableListOf(),
        app: CoreAppVersion = TEST_APP_FASSUNG,
    ): GithubReleases {
        val client = HttpClient(MockEngine { request ->
            requests += request
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
        return GithubReleases(client, expectations, fixedTestClock, app)
    }

    @Test
    fun soakedOffersSoakedMinorHotfixAndRecordsExpectation() = runTest {
        val expectations = FirmwareArtifactExpectations()
        val checker = checker(standardBody(), expectations = expectations)
        val result = checker.getLatestFirmware(testWatchInfo(WatchHardwarePlatform.CORE_ASTERIX, "v4.30.0"), FirmwareUpdateChannel.Soaked)
        val update = assertIs<FirmwareUpdateCheckResult.FoundUpdate>(result)
        // Minor 4.31 soaked (first release 10 days old), hotfix taken despite
        // being 1 day old; 4.32 (2 days) not soaked; factory tag ignored.
        assertEquals("v4.31.1", update.version.stringVersion)
        assertContains(update.url, "normal_asterix_v4.31.1.pbz")
        assertEquals("", update.notes)
        assertEquals(
            ExpectedFirmwareArtifact(hex('b'), 222, "v4.31.1", Signaturquelle.Signiert(sigUrl("asterix", "v4.31.1"), "normal_asterix_v4.31.1.pbz")),
            expectations.lookup(update.url),
        )
    }

    @Test
    fun earlyOffersNewestMainLineTagNeverTheFactoryTag() = runTest {
        val checker = checker(standardBody())
        val result = checker.getLatestFirmware(testWatchInfo(WatchHardwarePlatform.CORE_ASTERIX, "v4.30.0"), FirmwareUpdateChannel.Early)
        val update = assertIs<FirmwareUpdateCheckResult.FoundUpdate>(result)
        // The factory hotfix v4.9.142.4 is the most recent publish (it would
        // hold GitHub's Latest badge) and has the biggest patch number.
        assertEquals("v4.32.0", update.version.stringVersion)
    }

    @Test
    fun equalVersionIsNeverReOffered() = runTest {
        // The release's published_at is later than any firmware build
        // timestamp, so upstream FirmwareVersion comparison would call the
        // same version "newer" on its timestamp tiebreak. The checker must
        // compare tags and say no.
        val checker = checker(standardBody())
        val result = checker.getLatestFirmware(testWatchInfo(WatchHardwarePlatform.CORE_ASTERIX, "v4.31.1"), FirmwareUpdateChannel.Soaked)
        assertIs<FirmwareUpdateCheckResult.FoundNoUpdate>(result)
    }

    @Test
    fun downgradeIsNeverOffered() = runTest {
        // Running the Early tier, then switching back to Soaked: the soaked
        // candidate is older than the running firmware.
        val checker = checker(standardBody())
        val result = checker.getLatestFirmware(testWatchInfo(WatchHardwarePlatform.CORE_ASTERIX, "v4.32.0"), FirmwareUpdateChannel.Soaked)
        assertIs<FirmwareUpdateCheckResult.FoundNoUpdate>(result)
    }

    @Test
    fun recoveryIsAlwaysOffered() = runTest {
        val checker = checker(standardBody())
        val equalVersion = checker.getLatestFirmware(
            testWatchInfo(WatchHardwarePlatform.CORE_ASTERIX, "v4.31.1", isRecovery = true),
            FirmwareUpdateChannel.Soaked,
        )
        assertIs<FirmwareUpdateCheckResult.FoundUpdate>(equalVersion)
        val unparseable = checker.getLatestFirmware(
            testWatchInfo(WatchHardwarePlatform.CORE_ASTERIX, "unknown", isRecovery = true),
            FirmwareUpdateChannel.Soaked,
        )
        assertIs<FirmwareUpdateCheckResult.FoundUpdate>(unparseable)
    }

    @Test
    fun unparseableRunningVersionFailsClosed() = runTest {
        val checker = checker(standardBody())
        val result = checker.getLatestFirmware(testWatchInfo(WatchHardwarePlatform.CORE_ASTERIX, "unknown"), FirmwareUpdateChannel.Soaked)
        assertIs<FirmwareUpdateCheckResult.UpdateCheckFailed>(result)
    }

    @Test
    fun unverifiableAssetIsTreatedAsAbsentAndSelectionWalksForward() = runTest {
        val body = "[" + listOf(
            releaseJson("v4.32.0", 2, listOf(normalAsset("asterix", "v4.32.0", digest('a'), 111))),
            releaseJson("v4.31.1", 1, listOf(normalAsset("asterix", "v4.31.1", null, 222))),
            releaseJson("v4.31.0", 10, listOf(normalAsset("asterix", "v4.31.0", "not-a-digest", 333))),
        ).joinToString(",") + "]"
        val expectations = FirmwareArtifactExpectations()
        val checker = checker(body, expectations = expectations)
        val result = checker.getLatestFirmware(testWatchInfo(WatchHardwarePlatform.CORE_ASTERIX, "v4.30.0"), FirmwareUpdateChannel.Soaked)
        val update = assertIs<FirmwareUpdateCheckResult.FoundUpdate>(result)
        // Soaked target is 4.31.1 (digest null) and 4.31.0's digest is
        // malformed; the nearest newer verifiable release wins.
        assertEquals("v4.32.0", update.version.stringVersion)
        assertEquals(
            ExpectedFirmwareArtifact(hex('a'), 111, "v4.32.0", Signaturquelle.Signiert(sigUrl("asterix", "v4.32.0"), "normal_asterix_v4.32.0.pbz")),
            expectations.lookup(update.url),
        )
    }

    @Test
    fun releaseWithoutSignatureIsTreatedAsAbsent() = runTest {
        // Boulder: das gereifte Ziel 4.31.1 hat eine Signatur mit 63 Byte, 4.31.0
        // gar keine - beide zaehlen nicht, und die Auswahl geht weiter zum
        // naechsten pruefbaren Release, wie beim fehlenden Digest.
        val body = "[" + listOf(
            releaseJson("v4.32.0", 2, listOf(normalAsset("asterix", "v4.32.0", digest('a'), 111))),
            releaseJson("v4.31.1", 12, listOf(normalAsset("asterix", "v4.31.1", digest('b'), 222, sigSize = 63))),
            releaseJson("v4.31.0", 14, listOf(normalAsset("asterix", "v4.31.0", digest('c'), 333, signiert = false))),
        ).joinToString(",") + "]"
        val expectations = FirmwareArtifactExpectations()
        val checker = checker(body, expectations = expectations)
        val result = checker.getLatestFirmware(testWatchInfo(WatchHardwarePlatform.CORE_ASTERIX, "v4.30.0"), FirmwareUpdateChannel.Soaked)
        val update = assertIs<FirmwareUpdateCheckResult.FoundUpdate>(result)
        assertEquals("v4.32.0", update.version.stringVersion)
    }

    @Test
    fun perEntryMalformedReleasesAreSkippedNotFatal() = runTest {
        // The skip-vs-fail split is deliberate: one odd upstream entry (bad
        // tag, missing or garbage date) must cost only itself, or a single
        // unusual tag would break every Core watch's update check.
        val body = "[" + listOf(
            """{"tag_name":"totally-unparseable","prerelease":false,"draft":false,""" +
                """"published_at":"${TEST_NOW}","assets":[]}""",
            """{"tag_name":"v4.33.0","prerelease":false,"draft":false,""" +
                """"assets":[${normalAsset("asterix", "v4.33.0", digest('f'), 111)}]}""",
            """{"tag_name":"v4.34.0","prerelease":false,"draft":false,"published_at":"not-a-date",""" +
                """"assets":[${normalAsset("asterix", "v4.34.0", digest('f'), 111)}]}""",
            releaseJson("v4.31.0", 10, listOf(normalAsset("asterix", "v4.31.0", digest('c'), 333))),
        ).joinToString(",") + "]"
        val result = checker(body).getLatestFirmware(
            testWatchInfo(WatchHardwarePlatform.CORE_ASTERIX, "v4.30.0"), FirmwareUpdateChannel.Soaked,
        )
        val update = assertIs<FirmwareUpdateCheckResult.FoundUpdate>(result)
        assertEquals("v4.31.0", update.version.stringVersion)
    }

    @Test
    fun zeroSizeAssetIsTreatedAsUnverifiable() = runTest {
        // A zero-size asset cannot be size-verified, so it counts as absent
        // and selection moves on, like the null/garbage digest cases.
        val body = "[" + listOf(
            releaseJson("v4.32.0", 2, listOf(normalAsset("asterix", "v4.32.0", digest('a'), 0))),
            releaseJson("v4.31.1", 3, listOf(normalAsset("asterix", "v4.31.1", digest('b'), 222))),
        ).joinToString(",") + "]"
        val result = checker(body).getLatestFirmware(
            testWatchInfo(WatchHardwarePlatform.CORE_ASTERIX, "v4.30.0"), FirmwareUpdateChannel.Early,
        )
        val update = assertIs<FirmwareUpdateCheckResult.FoundUpdate>(result)
        assertEquals("v4.31.1", update.version.stringVersion)
    }

    @Test
    fun noAssetForThisHardwareFailsClosed() = runTest {
        val body = "[" + releaseJson(
            "v4.31.0", 10,
            listOf(normalAsset("obelix_pvt", "v4.31.0", digest('a'), 111)),
        ) + "]"
        val checker = checker(body)
        val result = checker.getLatestFirmware(testWatchInfo(WatchHardwarePlatform.CORE_ASTERIX, "v4.30.0"), FirmwareUpdateChannel.Soaked)
        assertIs<FirmwareUpdateCheckResult.UpdateCheckFailed>(result)
    }

    @Test
    fun prereleaseAndDraftAreSkipped() = runTest {
        val body = "[" + listOf(
            releaseJson("v4.33.0", 10, listOf(normalAsset("asterix", "v4.33.0", digest('a'), 111)), prerelease = true),
            releaseJson("v4.34.0", 10, listOf(normalAsset("asterix", "v4.34.0", digest('b'), 222)), draft = true),
            releaseJson("v4.31.0", 10, listOf(normalAsset("asterix", "v4.31.0", digest('c'), 333))),
        ).joinToString(",") + "]"
        val checker = checker(body)
        val result = checker.getLatestFirmware(testWatchInfo(WatchHardwarePlatform.CORE_ASTERIX, "v4.30.0"), FirmwareUpdateChannel.Soaked)
        val update = assertIs<FirmwareUpdateCheckResult.FoundUpdate>(result)
        assertEquals("v4.31.0", update.version.stringVersion)
    }

    @Test
    fun rateLimitFailsWithARetryableMessage() = runTest {
        val checker = checker("""{"message":"API rate limit exceeded"}""", status = HttpStatusCode.Forbidden)
        val result = checker.getLatestFirmware(testWatchInfo(WatchHardwarePlatform.CORE_ASTERIX, "v4.30.0"), FirmwareUpdateChannel.Soaked)
        val failure = assertIs<FirmwareUpdateCheckResult.UpdateCheckFailed>(result)
        assertContains(failure.error, "rate limited")
    }

    @Test
    fun serverErrorsAndMalformedBodiesFailClosed() = runTest {
        val serverError = checker("oops", status = HttpStatusCode.InternalServerError)
            .getLatestFirmware(testWatchInfo(WatchHardwarePlatform.CORE_ASTERIX, "v4.30.0"), FirmwareUpdateChannel.Soaked)
        assertIs<FirmwareUpdateCheckResult.UpdateCheckFailed>(serverError)

        val notJson = checker("this is not json")
            .getLatestFirmware(testWatchInfo(WatchHardwarePlatform.CORE_ASTERIX, "v4.30.0"), FirmwareUpdateChannel.Soaked)
        assertIs<FirmwareUpdateCheckResult.UpdateCheckFailed>(notJson)
    }

    @Test
    fun networkErrorFailsClosed() = runTest {
        val client = HttpClient(MockEngine { throw IOException("network down") }) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val checker = GithubReleases(client, FirmwareArtifactExpectations(), fixedTestClock, TEST_APP_FASSUNG)
        val result = checker.getLatestFirmware(testWatchInfo(WatchHardwarePlatform.CORE_ASTERIX, "v4.30.0"), FirmwareUpdateChannel.Soaked)
        assertIs<FirmwareUpdateCheckResult.UpdateCheckFailed>(result)
    }

    @Test
    fun requestIsAnonymousAndTargetsTheReleaseList() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val checker = checker(standardBody(), requests = requests)
        checker.getLatestFirmware(testWatchInfo(WatchHardwarePlatform.CORE_ASTERIX, "v4.30.0"), FirmwareUpdateChannel.Soaked)
        assertEquals(1, requests.size)
        val request = requests.single()
        assertEquals("api.github.com", request.url.host)
        assertEquals("/repos/dysseus-pascal/boulder-firmware/releases", request.url.encodedPath)
        // The only query parameter is the page size: no hardware, serial, or
        // version leaves the device.
        assertEquals(setOf("per_page"), request.url.parameters.names())
        val urlText = request.url.toString()
        assertFalse(urlText.contains(TEST_WATCH_SERIAL))
        assertFalse(urlText.contains("asterix"))
        assertEquals("application/vnd.github+json", request.headers["Accept"])
        assertTrue(request.headers.names().none { name ->
            request.headers.getAll(name).orEmpty().any { it.contains(TEST_WATCH_SERIAL) }
        })
    }

    @Test
    fun nothingIsRecordedWhenNoUpdateIsOffered() = runTest {
        val expectations = FirmwareArtifactExpectations()
        val checker = checker(standardBody(), expectations = expectations)
        checker.getLatestFirmware(testWatchInfo(WatchHardwarePlatform.CORE_ASTERIX, "v4.31.1"), FirmwareUpdateChannel.Soaked)
        assertNull(expectations.lookup("https://github.com/dysseus-pascal/boulder-firmware/releases/download/x/normal_asterix_v4.31.1.pbz"))
    }

    // --- Boulder-Firmware (v4.38.1-bN) ---

    private fun boulderBody() = "[" + listOf(
        releaseJson("v4.38.1-b2", 0, listOf(normalAsset("obelix_pvt", "v4.38.1-b2", digest('b'), 222))),
        releaseJson("v4.38.1-b1", 3, listOf(normalAsset("obelix_pvt", "v4.38.1-b1", digest('a'), 111))),
    ).joinToString(",") + "]"

    @Test
    fun boulderReleaseIsOfferedOnTheDayItAppears() = runTest {
        // Ein Entwicklungsbau ohne Boulder-Nummer (so meldet sich die Uhr vor
        // dem ersten Release): die neueste Boulder-Fassung, ohne Wartewoche.
        val result = checker(boulderBody()).getLatestFirmware(
            testWatchInfo(WatchHardwarePlatform.CORE_OBELIX_PVT, "v4.38.1-12-gb29995ee2"),
            FirmwareUpdateChannel.Soaked,
        )
        val update = assertIs<FirmwareUpdateCheckResult.FoundUpdate>(result)
        assertEquals("v4.38.1-b2", update.version.stringVersion)
        assertContains(update.url, "normal_obelix_pvt_v4.38.1-b2.pbz")
    }

    @Test
    fun boulderReleaseIsNotOfferedAgainOnAFollowUpBuild() = runTest {
        // Ein Bau nach b2 (b2 plus Commits) hat b2 schon.
        val result = checker(boulderBody()).getLatestFirmware(
            testWatchInfo(WatchHardwarePlatform.CORE_OBELIX_PVT, "v4.38.1-b2-3-gabcdef12"),
            FirmwareUpdateChannel.Soaked,
        )
        assertIs<FirmwareUpdateCheckResult.FoundNoUpdate>(result)
    }

    // --- Boulder: Mindest-App ---

    /** Ein Boulder-Release b3, dessen Notizen [notizen] sind; die Uhr laeuft b2. */
    private fun mitNotizen(notizen: String) = "[" + releaseJson(
        "v4.38.1-b3", 0, listOf(normalAsset("obelix_pvt", "v4.38.1-b3", digest('c'), 333)), body = notizen,
    ) + "]"

    private suspend fun pruefe(notizen: String, app: String, expectations: FirmwareArtifactExpectations = FirmwareArtifactExpectations()) =
        checker(mitNotizen(notizen), expectations = expectations, app = CoreAppVersion(app)).getLatestFirmware(
            testWatchInfo(WatchHardwarePlatform.CORE_OBELIX_PVT, "v4.38.1-b2"),
            FirmwareUpdateChannel.Soaked,
        )

    @Test
    fun eineFirmwareFuerEineNeuereAppWirdNichtAngeboten() = runTest {
        val expectations = FirmwareArtifactExpectations()
        val result = pruefe("Neue Tags fuer die Nacht.\n\nMindest-App: 0.50.0\n", "0.49.0", expectations)
        // Kein Fehler, nur ein Stand: die Firmware wartet auf das App-Update.
        assertEquals(FirmwareUpdateCheckResult.BrauchtNeuereApp("0.50.0"), result)
        // Nichts zum Einspielen vorgemerkt.
        assertNull(expectations.lookup("https://github.com/dysseus-pascal/boulder-firmware/releases/download/x/normal_obelix_pvt_v4.38.1-b3.pbz"))
    }

    @Test
    fun abDerMindestAppWirdSieAngeboten() = runTest {
        for (app in listOf("0.50.0", "0.50.1", "0.51.0", "1.0.0")) {
            val result = pruefe("Mindest-App: 0.50.0", app)
            assertEquals("v4.38.1-b3", assertIs<FirmwareUpdateCheckResult.FoundUpdate>(result, app).version.stringVersion)
        }
    }

    @Test
    fun verglichenWirdZahlFuerZahl() = runTest {
        // Als Text waere 0.9.0 groesser als 0.10.0.
        assertIs<FirmwareUpdateCheckResult.BrauchtNeuereApp>(pruefe("Mindest-App: 0.10.0", "0.9.0"))
        assertIs<FirmwareUpdateCheckResult.BrauchtNeuereApp>(pruefe("Mindest-App: 1.0.0", "0.99.99"))
        assertIs<FirmwareUpdateCheckResult.FoundUpdate>(pruefe("Mindest-App: 0.9.0", "0.10.0"))
    }

    @Test
    fun ohneZeileGiltKeineGrenze() = runTest {
        assertIs<FirmwareUpdateCheckResult.FoundUpdate>(pruefe("", "0.0.1"))
        assertIs<FirmwareUpdateCheckResult.FoundUpdate>(pruefe("Nur Text, keine Grenze.", "0.0.1"))
    }

    @Test
    fun dieZeileGiltNurGenauSo() = runTest {
        // Mitten in den Notizen und mit Windows-Zeilenenden (so speichert GitHub oft) gilt sie.
        assertIs<FirmwareUpdateCheckResult.BrauchtNeuereApp>(pruefe("Titel\r\nMindest-App: 0.50.0\r\nMehr", "0.49.0"))
        // Anders geschrieben ist es keine Grenze.
        for (zeile in listOf("Mindest-App: 0.50", "Mindest-App: 0.50.0 (Nacht)", " Mindest-App: 0.50.0", "mindest-app: 0.50.0", "Mindest-App:0.50.0")) {
            assertIs<FirmwareUpdateCheckResult.FoundUpdate>(pruefe(zeile, "0.49.0"), zeile)
        }
    }

    @Test
    fun eineUnlesbareAppFassungBekommtKeineFirmwareMitGrenze() = runTest {
        assertIs<FirmwareUpdateCheckResult.BrauchtNeuereApp>(pruefe("Mindest-App: 0.50.0", "dev"))
        assertIs<FirmwareUpdateCheckResult.FoundUpdate>(pruefe("Mindest-App: 0.50.0", "0.50.0-3-gabcdef12"))
    }

    // --- Boulder: Mindest-App ueber mehrere Releases ---

    /** b4 verlangt App 0.51.0, b3 App 0.50.0, b2 nichts. */
    private fun dreiFassungen() = "[" + listOf(
        releaseJson("v4.38.1-b4", 0, listOf(normalAsset("obelix_pvt", "v4.38.1-b4", digest('d'), 444)), body = "Mindest-App: 0.51.0"),
        releaseJson("v4.38.1-b3", 1, listOf(normalAsset("obelix_pvt", "v4.38.1-b3", digest('c'), 333)), body = "Mindest-App: 0.50.0"),
        releaseJson("v4.38.1-b2", 2, listOf(normalAsset("obelix_pvt", "v4.38.1-b2", digest('b'), 222))),
    ).joinToString(",") + "]"

    private fun paketUrl(tag: String) = "https://github.com/dysseus-pascal/boulder-firmware/releases/download/x/normal_obelix_pvt_$tag.pbz"

    private suspend fun pruefeDrei(
        app: String,
        laeuft: String,
        recovery: Boolean = false,
        kanal: FirmwareUpdateChannel = FirmwareUpdateChannel.Soaked,
        expectations: FirmwareArtifactExpectations = FirmwareArtifactExpectations(),
    ) = checker(dreiFassungen(), expectations = expectations, app = CoreAppVersion(app)).getLatestFirmware(
        testWatchInfo(WatchHardwarePlatform.CORE_OBELIX_PVT, laeuft, isRecovery = recovery),
        kanal,
    )

    @Test
    fun istDieNeuesteZuNeuKommtDieNeuestePassende() = runTest {
        for (kanal in FirmwareUpdateChannel.entries) {
            val expectations = FirmwareArtifactExpectations()
            val update = assertIs<FirmwareUpdateCheckResult.FoundUpdate>(pruefeDrei("0.50.0", "v4.38.1-b2", kanal = kanal, expectations = expectations), "$kanal")
            assertEquals("v4.38.1-b3", update.version.stringVersion, "$kanal")
            assertEquals(paketUrl("v4.38.1-b3"), update.url)
            // Vorgemerkt ist nur, was angeboten wird.
            assertEquals("v4.38.1-b3", expectations.lookup(paketUrl("v4.38.1-b3"))?.versionTag)
            assertNull(expectations.lookup(paketUrl("v4.38.1-b4")))
        }
        // Eine App ohne beide Fassungen bekommt b2, das keine Grenze nennt.
        val alt = assertIs<FirmwareUpdateCheckResult.FoundUpdate>(pruefeDrei("0.49.0", "v4.38.1-b1"))
        assertEquals("v4.38.1-b2", alt.version.stringVersion)
    }

    @Test
    fun derStandBrauchtNeuereAppNurWennKeinePassendeNeuerIst() = runTest {
        // Die Uhr hat b3: neuer ist nur b4, und das braucht 0.51.0.
        assertEquals(FirmwareUpdateCheckResult.BrauchtNeuereApp("0.51.0"), pruefeDrei("0.50.0", "v4.38.1-b3"))
        // Die Uhr hat b2, die App ist 0.49.0: b3 und b4 warten, gemeldet wird die Grenze der neuesten.
        assertEquals(FirmwareUpdateCheckResult.BrauchtNeuereApp("0.51.0"), pruefeDrei("0.49.0", "v4.38.1-b2"))
        // Laeuft schon die neueste, wartet nichts.
        assertIs<FirmwareUpdateCheckResult.FoundNoUpdate>(pruefeDrei("0.49.0", "v4.38.1-b4"))
        // Mit der neuen App die neueste.
        val neu = assertIs<FirmwareUpdateCheckResult.FoundUpdate>(pruefeDrei("0.51.0", "v4.38.1-b3"))
        assertEquals("v4.38.1-b4", neu.version.stringVersion)
    }

    @Test
    fun eineUhrInRecoveryBekommtDieNeuestePassende() = runTest {
        for ((app, erwartet) in listOf("0.49.0" to "v4.38.1-b2", "0.50.0" to "v4.38.1-b3", "0.51.0" to "v4.38.1-b4")) {
            for (laeuft in listOf("v4.38.1-b4", "unknown")) {
                val update = assertIs<FirmwareUpdateCheckResult.FoundUpdate>(pruefeDrei(app, laeuft, recovery = true), "$app $laeuft")
                assertEquals(erwartet, update.version.stringVersion, "$app $laeuft")
            }
        }
        // Passt keine, bleibt nur der Stand: auch Recovery bekommt nichts, das diese App nicht liest.
        val keine = checker(mitNotizen("Mindest-App: 0.50.0"), app = CoreAppVersion("0.49.0")).getLatestFirmware(
            testWatchInfo(WatchHardwarePlatform.CORE_OBELIX_PVT, "v4.38.1-b2", isRecovery = true),
            FirmwareUpdateChannel.Soaked,
        )
        assertEquals(FirmwareUpdateCheckResult.BrauchtNeuereApp("0.50.0"), keine)
    }
}
