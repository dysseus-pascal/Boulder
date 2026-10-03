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
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import org.koin.core.module.Module
import org.koin.dsl.module
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * Boulder: PKJSApp bestaetigt eine Nachricht der Uhr beim Empfang - ausser ein
 * Begleiter daneben tut es selbst nach dem Sichern (BestaetigtSelbst). Dann
 * darf es weder bestaetigen noch ablehnen, sonst gibt die Uhr ihre Kopie
 * zu frueh auf. Das JS der Uhr-App bekommt die Nachricht trotzdem.
 */
class PKJSAppBestaetigungTest {
    private val uuid = Uuid.parse("00000000-0000-0000-0000-0000000000bb")
    private val koin = (object : LibPebbleKoinComponent {}).getKoin()
    private var geladen: Module? = null

    /** Ein JS ohne Engine: es merkt sich nur, was ankam. */
    private class Laeufer(appInfo: PbwAppInfo, lockerEntry: LockerEntry, device: CompanionAppDevice) :
        JsRunner(appInfo, lockerEntry, Path("unused.js"), device, Channel(Channel.UNLIMITED)) {
        val empfangen = mutableListOf<String?>()

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
        override suspend fun signalShowConfiguration() {}
        override suspend fun signalWebviewClosed(data: String?) {}
        override suspend fun eval(js: String) {}
        override suspend fun evalWithResult(js: String): Any? = null
        override fun debugForceGC() {}
    }

    private class Aufbau(
        val app: PKJSApp,
        val laeufer: Laeufer,
        val eingang: Channel<AppMessageData>,
        val antworten: List<AppMessageResult>,
    )

    private fun TestScope.starte(bestaetigt: Boolean, bereit: Boolean = true): Aufbau {
        val nachrichten = FakeAppMessages()
        val watch = fakeWatch(connected = true) as ConnectedPebbleDevice
        val device = CompanionAppDevice(watch.identifier, watch.watchInfo, nachrichten)
        val appInfo = PbwAppInfo(uuid = uuid.toString(), shortName = "Test App", versionLabel = "1.0", resources = Resources(emptyList()))
        val lockerEntry = LockerEntry(
            id = uuid, version = "1.0", title = "Test App", type = "watchapp", developerName = "Test Developer",
            configurable = false, pbwVersionCode = "1", platforms = emptyList(),
        )
        val laeufer = Laeufer(appInfo, lockerEntry, device)
        // Ein JS, das sein "ready" schon gemeldet hat - sonst wartet PKJSApp bis zu 6 s.
        if (bereit) laeufer.onReadyConfirmed(true)
        val modul = module { factory<JsRunner> { laeufer } }
        koin.loadModules(listOf(modul), allowOverride = true)
        geladen = modul
        // Was an die Uhr zurueckginge, ACK wie NACK.
        val antworten = mutableListOf<AppMessageResult>()
        backgroundScope.launch { for (a in nachrichten.outboundResults) antworten += a }
        val eingang = Channel<AppMessageData>(Channel.UNLIMITED)
        val app = PKJSApp(device, Path("unused.js"), appInfo, lockerEntry, ConnectionCoroutineScope(backgroundScope.coroutineContext), bestaetigt = bestaetigt)
        backgroundScope.launch { app.start(eingang.consumeAsFlow()) }
        runCurrent()
        return Aufbau(app, laeufer, eingang, antworten)
    }

    private fun nachricht(id: Int) = AppMessageData(id.toUByte(), uuid, mapOf(1 to 7))

    @AfterTest
    fun aufraeumen() {
        geladen?.let { koin.unloadModules(listOf(it)) }
        geladen = null
    }

    @Test
    fun allein_bestaetigt_pebblekit_js_beim_empfang() = runTest {
        val a = starte(bestaetigt = true)
        a.eingang.send(nachricht(5))
        runCurrent()
        assertEquals(listOf(5.toUByte()), a.antworten.map { it.transactionId })
        assertTrue(a.antworten.single() is AppMessageResult.ACK)
        assertEquals(1, a.laeufer.empfangen.size)
        a.app.stop()
    }

    @Test
    fun neben_einem_sichernden_begleiter_schweigt_es_und_reicht_weiter() = runTest {
        val a = starte(bestaetigt = false)
        a.eingang.send(nachricht(5))
        a.eingang.send(nachricht(6))
        runCurrent()
        assertEquals(emptyList(), a.antworten)
        // Pins und Konfigseite haengen am JS: es bekommt jede Nachricht.
        assertEquals(2, a.laeufer.empfangen.size)
        a.app.stop()
    }

    @Test
    fun auch_ein_js_das_nicht_bereit_wird_antwortet_nicht() = runTest {
        // Zum Vergleich: allein antwortet PKJSApp nach 6 s Warten doch.
        val allein = starte(bestaetigt = true, bereit = false)
        allein.eingang.send(nachricht(5))
        advanceTimeBy(7.seconds)
        runCurrent()
        assertEquals(1, allein.antworten.size)
        allein.app.stop()
        aufraeumen()

        val daneben = starte(bestaetigt = false, bereit = false)
        daneben.eingang.send(nachricht(5))
        advanceTimeBy(7.seconds)
        runCurrent()
        assertEquals(emptyList(), daneben.antworten)
        assertEquals(0, daneben.laeufer.empfangen.size)
        daneben.app.stop()
    }
}
