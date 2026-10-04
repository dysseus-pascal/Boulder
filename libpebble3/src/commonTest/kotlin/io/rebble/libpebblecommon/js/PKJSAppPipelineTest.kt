package io.rebble.libpebblecommon.js

import io.rebble.libpebblecommon.connection.ConnectedPebbleDevice
import io.rebble.libpebblecommon.connection.FakeAppMessages
import io.rebble.libpebblecommon.connection.fakeWatch
import io.rebble.libpebblecommon.database.entity.LockerEntry
import io.rebble.libpebblecommon.di.ConnectionCoroutineScope
import io.rebble.libpebblecommon.di.LibPebbleKoinComponent
import io.rebble.libpebblecommon.metadata.pbw.appinfo.PbwAppInfo
import io.rebble.libpebblecommon.metadata.pbw.appinfo.Resources
import io.rebble.libpebblecommon.services.appmessage.AppMessageData
import io.rebble.libpebblecommon.services.appmessage.AppMessageResult
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import org.koin.core.module.Module
import org.koin.dsl.module
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Die AppMessage-Wege einer PebbleKit-JS-Sitzung: eine einzelne kaputte
 * Nachricht in eine Richtung darf die Sitzung nicht stumm machen. Dazu die
 * Einstellungsseite, deren Adresse aus dem JS der Uhr-App kommt.
 */
class PKJSAppPipelineTest {
    private val uuid = Uuid.parse("00000000-0000-0000-0000-0000000000ee")
    private val koin = (object : LibPebbleKoinComponent {}).getKoin()
    private var geladen: Module? = null

    /** Ein JS ohne Engine: es merkt sich, was ankam, sendet auf Zuruf und oeffnet [seite]. */
    private class Laeufer(
        appInfo: PbwAppInfo,
        lockerEntry: LockerEntry,
        device: CompanionAppDevice,
        urls: Channel<String>,
    ) : JsRunner(appInfo, lockerEntry, Path("unused.js"), device, urls) {
        val empfangen = mutableListOf<String?>()
        var seite = "https://example.org/settings"

        fun sende(json: String): AppMessageRequest = AppMessageRequest(json).also { _outgoingAppMessages.tryEmit(it) }

        override suspend fun start() {}
        override suspend fun stop() {}
        override suspend fun loadAppJs(jsUrl: String) {}
        override suspend fun signalInterceptResponse(callbackId: String, result: InterceptResponse) {}
        override suspend fun signalNewAppMessageData(data: String?): Boolean {
            empfangen += data
            return true
        }
        override suspend fun signalTimelineToken(callId: String, token: String) {}
        override suspend fun signalTimelineTokenFail(callId: String) {}
        override suspend fun signalReady() {}
        override suspend fun signalShowConfiguration() {
            loadUrl(seite)
        }
        override suspend fun signalWebviewClosed(data: String?) {}
        override suspend fun eval(js: String) {}
        override suspend fun evalWithResult(js: String): Any? = null
        override fun debugForceGC() {}
    }

    private class Aufbau(
        val app: PKJSApp,
        val laeufer: () -> Laeufer,
        val eingang: Channel<AppMessageData>,
        val uhr: FakeAppMessages,
    )

    private fun TestScope.starte(): Aufbau {
        val nachrichten = FakeAppMessages()
        val watch = fakeWatch(connected = true) as ConnectedPebbleDevice
        val device = CompanionAppDevice(watch.identifier, watch.watchInfo, nachrichten)
        val appInfo = PbwAppInfo(uuid = uuid.toString(), shortName = "Test App", versionLabel = "1.0", resources = Resources(emptyList()))
        val lockerEntry = LockerEntry(
            id = uuid, version = "1.0", title = "Test App", type = "watchapp", developerName = "Test Developer",
            configurable = true, pbwVersionCode = "1", platforms = emptyList(),
        )
        var laeufer: Laeufer? = null
        // Mit dem Kanal, den PKJSApp fuer die Einstellungsseite uebergibt (Parameter 5).
        val modul = module {
            factory<JsRunner> { p ->
                Laeufer(appInfo, lockerEntry, device, p.get(5)).also { it.onReadyConfirmed(true); laeufer = it }
            }
        }
        koin.loadModules(listOf(modul), allowOverride = true)
        geladen = modul
        backgroundScope.launch { for (a in nachrichten.outboundResults) Unit }
        val eingang = Channel<AppMessageData>(Channel.UNLIMITED)
        val app = PKJSApp(device, Path("unused.js"), appInfo, lockerEntry, ConnectionCoroutineScope(backgroundScope.coroutineContext))
        backgroundScope.launch { app.start(eingang.consumeAsFlow()) }
        runCurrent()
        return Aufbau(app, { laeufer!! }, eingang, nachrichten)
    }

    @AfterTest
    fun aufraeumen() {
        geladen?.let { koin.unloadModules(listOf(it)) }
        geladen = null
    }

    @Test
    fun einVerschachteltesObjektBeendetDasSendenNicht() = runTest {
        val a = starte()
        val kaputt = a.laeufer().sende("""{"1":{"a":1}}""")
        runCurrent()
        assertEquals(AppMessageRequest.State.DataError, kaputt.state.value)
        val gut = a.laeufer().sende("""{"1":5}""")
        runCurrent()
        assertEquals(mapOf(1 to 5), a.uhr.outbound.tryReceive().getOrNull()?.data)
        assertTrue((gut.state.value as AppMessageRequest.State.Sent).result is AppMessageResult.ACK)
        a.app.stop()
    }

    @Test
    fun eineUnlesbareNachrichtDerUhrBeendetDenEmpfangNicht() = runTest {
        val a = starte()
        // Ein Wert, den toJSData nicht kennt.
        a.eingang.send(AppMessageData(5u, uuid, mapOf(1 to listOf(1))))
        a.eingang.send(AppMessageData(6u, uuid, mapOf(1 to 7)))
        runCurrent()
        assertEquals<List<String?>>(listOf("""{"1":7}"""), a.laeufer().empfangen)
        a.app.stop()
    }

    @Test
    fun nurWebseitenOeffnenDieEinstellungen() = runTest {
        val a = starte()
        assertEquals("https://example.org/settings", a.app.requestConfigurationUrl())
        a.laeufer().seite = "data:text/html,<p>Clay</p>"
        assertEquals("data:text/html,<p>Clay</p>", a.app.requestConfigurationUrl())
        for (fremd in listOf("javascript:alert(1)", " JavaScript:alert(1)", "file:///data/data/x", "content://x/y", "intent://x#Intent;end")) {
            a.laeufer().seite = fremd
            assertNull(a.app.requestConfigurationUrl(), fremd)
        }
        a.app.stop()
    }
}
