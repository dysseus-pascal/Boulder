package coredevices.util.usage

import com.russhwolf.settings.MapSettings
import coredevices.database.CactusUsageEventDao
import coredevices.database.CactusUsageEventEntity
import coredevices.util.Platform
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CactusUsageTest {

    // ---------- wire format ----------

    @Test
    fun wireFormatMatchesSupabaseColumns() {
        val encoded = json.encodeToString(CactusUsageEventEntity.serializer(), CactusUsageEventEntity(
            clientEventId = "00000000-0000-0000-0000-000000000001",
            installId = "install-abc",
            deviceType = "ring",
            deviceId = "ring-sat-42",
            eventType = "transcribe",
            warmup = false,
            modelName = "parakeet-tdt-0.6b-v3",
            success = false,
            failureReason = "OutOfMemoryError",
            durationMs = 1234L,
            appPlatform = "android",
            appVersion = "abc123",
            clientEventAt = "2026-05-29T12:00:00Z",
        ))
        listOf(
            "\"client_event_id\":\"00000000-0000-0000-0000-000000000001\"",
            "\"install_id\":\"install-abc\"",
            "\"device_type\":\"ring\"",
            "\"device_id\":\"ring-sat-42\"",
            "\"event_type\":\"transcribe\"",
            "\"warmup\":false",
            "\"model_name\":\"parakeet-tdt-0.6b-v3\"",
            "\"success\":false",
            "\"failure_reason\":\"OutOfMemoryError\"",
            "\"duration_ms\":1234",
            "\"app_platform\":\"android\"",
            "\"app_version\":\"abc123\"",
            "\"client_event_at\":\"2026-05-29T12:00:00Z\"",
        ).forEach { assertTrue(encoded.contains(it), "expected $it in $encoded") }
        assertFalse(encoded.contains("user_id"), "user_id must not be on the wire: $encoded")
    }

    // ---------- install id ----------

    @Test
    fun installIdPersistsAcrossReadsAndInstances() {
        val settings = MapSettings()
        val first = SettingsInstallIdProvider(settings).installId
        assertEquals(first, SettingsInstallIdProvider(settings).installId)
    }

    @Test
    fun installIdDistinctAcrossInstalls() {
        assertNotEquals(
            SettingsInstallIdProvider(MapSettings()).installId,
            SettingsInstallIdProvider(MapSettings()).installId,
        )
    }

    @Test
    fun installIdKeyConstantIsStableContract() {
        // Changing this string orphans every existing install_id and double-counts on next launch.
        assertEquals("cactus_usage_install_id", SettingsInstallIdProvider.KEY_INSTALL_ID)
    }

    // ---------- queue ----------

    @Test
    fun queueClearsDbOnSuccessfulUpload() = runTest {
        val dao = FakeDao()
        val uploader = RecordingUploader(succeed = true)
        val queue = CactusUsageEventQueue(dao, uploader)
        dao.insert(sampleEntity("a", ts(0)))
        dao.insert(sampleEntity("b", ts(1)))

        queue.uploadPendingFromDb()

        assertEquals(0L, dao.count())
        assertEquals(listOf("a", "b"), uploader.uploaded.flatten().map { it.clientEventId })
    }

    @Test
    fun queueRetainsEventsOnFailedUploadForRetry() = runTest {
        val dao = FakeDao()
        val queue = CactusUsageEventQueue(dao, RecordingUploader(succeed = false))
        dao.insert(sampleEntity())

        queue.uploadPendingFromDb()

        assertEquals(1L, dao.count())
    }

    @Test
    fun queueEvictsOldestPastStorageCap() = runTest {
        val dao = FakeDao()
        val queue = CactusUsageEventQueue(dao, RecordingUploader(succeed = false))
        repeat(1005) { dao.insert(sampleEntity("evt-$it", ts(it))) }

        queue.uploadPendingFromDb()

        assertEquals(1000L, dao.count())
        val remaining = dao.rows.map { it.clientEventId }.toSet()
        (0..4).forEach { assertFalse("evt-$it" in remaining) }
        assertTrue("evt-5" in remaining)
    }

    // ---------- tracker ----------

    @Test
    fun trackerDistinguishesAllThreeCallTypesAndDeviceMapping() = runTrackerTest { tracker, dao ->
        tracker.recordTranscribe(DeviceType.Watch, "w-1", "m", success = true, durationMs = 1L)
        tracker.recordComplete(DeviceType.Ring, "r-1", "m", success = true, durationMs = 1L)
        tracker.recordTranscribeWarmup("m", success = true, durationMs = 1L)
        flush()
        val realTranscribe = dao.rows.single { it.eventType == "transcribe" && !it.warmup }
        val complete = dao.rows.single { it.eventType == "complete" }
        val warmup = dao.rows.single { it.warmup }
        assertEquals("watch", realTranscribe.deviceType)
        assertEquals("ring", complete.deviceType)
        assertEquals("transcribe", warmup.eventType)
        assertEquals(null, warmup.deviceType)
        assertEquals(null, warmup.deviceId)
    }

    @Test
    fun trackerNullOrBlankDeviceIdFallsBackToUnknown() = runTrackerTest { tracker, dao ->
        tracker.recordTranscribe(DeviceType.Ring, deviceId = null, modelName = "m", success = true, durationMs = 1L)
        tracker.recordTranscribe(DeviceType.Ring, deviceId = "", modelName = "m", success = true, durationMs = 1L)
        flush()
        assertEquals(listOf("unknown", "unknown"), dao.rows.map { it.deviceId })
    }

    @Test
    fun trackerStampsInstallIdAndPlatformFromInjectedSources() = runTrackerTest(
        installId = "fixed-install",
        platform = stubPlatform(android = false),
    ) { tracker, dao ->
        tracker.recordTranscribe(DeviceType.Ring, "sat", "m", success = true, durationMs = 1L)
        flush()
        val row = dao.rows.single()
        assertEquals("fixed-install", row.installId)
        assertEquals("ios", row.appPlatform)
    }

    @Test
    fun trackerGivesDistinctClientEventIdsPerCall() = runTrackerTest { tracker, dao ->
        repeat(5) { tracker.recordTranscribe(DeviceType.Ring, "sat", "m", success = true, durationMs = 1L) }
        flush()
        assertEquals(5, dao.rows.map { it.clientEventId }.toSet().size)
    }

    // ---------- uploader ----------

    @Test
    fun uploaderReturnsTrueOn2xx() = runBlocking {
        val uploader = SupabaseCactusUsageUploader(
            HttpClient(MockEngine { respond("", HttpStatusCode.Created) }), URL, KEY,
        )
        assertTrue(uploader.upload(listOf(sampleEntity())))
    }

    @Test
    fun uploaderReturnsFalseOn5xxSoQueueKeepsEvents() = runBlocking {
        val uploader = SupabaseCactusUsageUploader(
            HttpClient(MockEngine { respondError(HttpStatusCode.ServiceUnavailable) }), URL, KEY,
        )
        assertFalse(uploader.upload(listOf(sampleEntity())))
    }

    @Test
    fun uploaderShortCircuitsWithoutNetworkCallWhenCredentialsMissing() = runBlocking {
        var calls = 0
        val engine = MockEngine { calls++; respond("", HttpStatusCode.Created) }
        val uploader = SupabaseCactusUsageUploader(HttpClient(engine), supabaseUrl = null, supabaseKey = null)
        assertTrue(uploader.upload(listOf(sampleEntity())))
        assertEquals(0, calls)
    }

    @Test
    fun uploaderRequestShapeMatchesSupabaseExpectations() = runBlocking {
        val captured = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request -> captured.add(request); respond("", HttpStatusCode.Created) }
        val uploader = SupabaseCactusUsageUploader(HttpClient(engine), URL, KEY)
        uploader.upload(listOf(sampleEntity("alpha"), sampleEntity("beta")))

        val req = captured.single()
        assertEquals("$URL/rest/v1/rpc/cactus_pebble_usage_insert", req.url.toString())
        assertEquals(KEY, req.headers["apikey"])
        assertEquals("Bearer $KEY", req.headers[HttpHeaders.Authorization])
        val body = req.bodyText()
        assertTrue(body.startsWith("""{"events":[""") && body.endsWith("]}"), body)
        assertTrue(body.contains("alpha") && body.contains("beta"))
        assertFalse(body.contains("user_id"))
    }

    // ---------- harness ----------

    private val json = Json { encodeDefaults = true; explicitNulls = true }

    private fun runTrackerTest(
        installId: String = "test-install",
        platform: Platform = stubPlatform(android = true),
        block: suspend Flusher.(tracker: CactusUsageTracker, dao: FakeDao) -> Unit,
    ) = runTest {
        val dao = FakeDao()
        val queue = CactusUsageEventQueue(dao, NoOpFailUploader)
        queue.startProcessing(backgroundScope)
        val tracker = RealCactusUsageTracker(
            queue = queue,
            installIdProvider = object : InstallIdProvider { override val installId: String = installId },
            platform = platform,
            appVersion = "v",
        )
        Flusher(this).block(tracker, dao)
    }

    private class Flusher(val scope: TestScope) {
        fun flush() { scope.runCurrent(); scope.advanceTimeBy(11_000); scope.advanceUntilIdle() }
    }

    private fun stubPlatform(android: Boolean) = object : Platform {
        override val name: String = if (android) "Android 34" else "iOS 17"
        override val deviceModelName: String = "T"
        override suspend fun openUrl(url: String) = Unit
        override suspend fun runWithBgTask(name: String, task: suspend () -> Unit) = task()
    }

    private fun ts(i: Int): String =
        "2026-05-29T12:${(i / 60).toString().padStart(2, '0')}:${(i % 60).toString().padStart(2, '0')}Z"

    private fun sampleEntity(clientEventId: String = "x", clientEventAt: String = "2026-05-29T12:00:00Z") =
        CactusUsageEventEntity(
            clientEventId, "install", "ring", "sat", "transcribe", false, "m",
            true, null, 1L, "android", "v", clientEventAt,
        )

    private fun HttpRequestData.bodyText(): String = when (val content = body) {
        is TextContent -> content.text
        is ByteArrayContent -> content.bytes().decodeToString()
        is OutgoingContent.ByteArrayContent -> content.bytes().decodeToString()
        else -> error("Unexpected body type: ${content::class.simpleName}")
    }

    private class RecordingUploader(val succeed: Boolean) : CactusUsageUploader {
        val uploaded = mutableListOf<List<CactusUsageEventEntity>>()
        override suspend fun upload(events: List<CactusUsageEventEntity>): Boolean {
            uploaded.add(events); return succeed
        }
    }

    private object NoOpFailUploader : CactusUsageUploader {
        override suspend fun upload(events: List<CactusUsageEventEntity>) = false
    }

    private class FakeDao : CactusUsageEventDao {
        val rows = mutableListOf<CactusUsageEventEntity>()
        override suspend fun insert(row: CactusUsageEventEntity) {
            if (rows.none { it.clientEventId == row.clientEventId }) rows.add(row)
        }
        override suspend fun getBatch(limit: Int) = rows.sortedBy { it.clientEventAt }.take(limit)
        override suspend fun deleteByIds(ids: List<String>) { rows.removeAll { it.clientEventId in ids } }
        override suspend fun count() = rows.size.toLong()
        override suspend fun deleteOldest(count: Long) {
            val toDrop = rows.sortedBy { it.clientEventAt }.take(count.toInt()).map { it.clientEventId }
            rows.removeAll { it.clientEventId in toDrop }
        }
    }

    companion object {
        private const val URL = "https://usage.example.supabase.co"
        private const val KEY = "test-anon-key"
    }
}
