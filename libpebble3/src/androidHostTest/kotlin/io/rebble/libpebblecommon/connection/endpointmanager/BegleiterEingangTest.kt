package io.rebble.libpebblecommon.connection.endpointmanager

import TestPebbleProtocolHandler
import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import io.rebble.libpebblecommon.LibPebbleConfig
import io.rebble.libpebblecommon.LibPebbleConfigFlow
import io.rebble.libpebblecommon.connection.BuiltInCompanions
import io.rebble.libpebblecommon.connection.CompanionApp
import io.rebble.libpebblecommon.connection.PebbleSocketIdentifier
import io.rebble.libpebblecommon.database.Database
import io.rebble.libpebblecommon.database.testDatenbank
import io.rebble.libpebblecommon.di.ConnectionCoroutineScope
import io.rebble.libpebblecommon.di.LibPebbleCoroutineScope
import io.rebble.libpebblecommon.locker.Locker
import io.rebble.libpebblecommon.locker.StaticLockerPBWCache
import io.rebble.libpebblecommon.packets.AppMessage
import io.rebble.libpebblecommon.packets.AppMessageTuple
import io.rebble.libpebblecommon.packets.AppRunStateMessage
import io.rebble.libpebblecommon.services.WatchInfo
import io.rebble.libpebblecommon.services.app.AppRunStateService
import io.rebble.libpebblecommon.services.appmessage.AppMessageData
import io.rebble.libpebblecommon.services.appmessage.AppMessageService
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Neben einem Begleiter, der jede Nachricht sofort bestaetigt, schickt die Uhr
 * weiter, auch wenn PebbleKit JS noch auf sein JS wartet (bis 6 s je Nachricht).
 * Was sich dabei anstaut, faellt nicht still weg (frueher: 64, die neuesten
 * still verworfen) und waechst nicht ohne Grenze: 256 fasst der Eingang, darueber
 * geht die aelteste mit Log.
 *
 * Ueber den echten CompanionAppLifecycleManager: die Uhr meldet die laufende App
 * (AppRunState), die Nachrichten kommen als AppMessagePush ueber den echten
 * AppMessageService, der eingebaute Begleiter nimmt sie erst spaeter ab.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BegleiterEingangTest {
    private val uuid = Uuid.parse("00000000-0000-0000-0000-0000000000cc")
    private var db: Database? = null
    private val geloggt = mutableListOf<String>()
    private val mitschrift = object : LogWriter() {
        override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
            if (severity >= Severity.Warn) synchronized(geloggt) { geloggt += message }
        }
    }

    @AfterTest
    fun zu() {
        db?.close()
        Logger.setLogWriters(Logger.config.logWriterList - mitschrift)
    }

    /** Haengt hinterher: nimmt den Eingang erst ab, wenn der Test es sagt. */
    private class LangsamerBegleiter : CompanionApp {
        val eingang = CompletableDeferred<Flow<AppMessageData>>()
        override suspend fun start(incomingAppMessages: Flow<AppMessageData>) {
            eingang.complete(incomingAppMessages)
        }
        override suspend fun stop() {}
    }

    // Nur fuer den Konstruktor: der Weg eines eingebauten Begleiters fasst sie nie an
    // (Sammlung, PBW-Speicher, Uhrdaten), ohne Konstruktor angelegt.
    private inline fun <reified T> unbenutzt(): T {
        val unsafe = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null) as sun.misc.Unsafe
        return unsafe.allocateInstance(T::class.java) as T
    }

    private suspend fun TestScope.schicke(uhr: TestPebbleProtocolHandler, anzahl: Int) {
        for (i in 0 until anzahl) {
            // Die Uhr schickt Paket fuer Paket; dazwischen kommt der Empfang zum Zug.
            uhr.receivePacket(AppMessage.AppMessagePush((i % 256).toUByte(), uuid, listOf(AppMessageTuple.createInt(1u, i))))
            runCurrent()
        }
    }

    @Test
    fun einLangsamerBegleiterBekommtDieJuengsten256MitLogFuerDieAelteren() = runTest {
        Logger.addLogWriter(mitschrift)
        val datenbank = testDatenbank(coroutineContext[CoroutineDispatcher]!!).also { db = it }
        val verbindung = ConnectionCoroutineScope(backgroundScope.coroutineContext)
        val uhr = TestPebbleProtocolHandler { }
        val laufend = AppRunStateService(uhr, verbindung).also { it.init() }
        val nachrichten = AppMessageService(uhr, verbindung).also { it.init() }
        val begleiter = LangsamerBegleiter()
        val config = LibPebbleConfigFlow(MutableStateFlow(LibPebbleConfig()))
        val manager = CompanionAppLifecycleManager(
            lockerPBWCache = unbenutzt<StaticLockerPBWCache>(),
            lockerEntryDao = datenbank.lockerEntryDao(),
            appRunStateService = laufend,
            appMessagesService = nachrichten,
            locker = unbenutzt<Locker>(),
            connectionScope = verbindung,
            libPebbleConfigFlow = config,
            libpebbleCoroutineScope = LibPebbleCoroutineScope(backgroundScope.coroutineContext),
            builtInCompanions = BuiltInCompanions { app, _ -> if (app == uuid) begleiter else null },
        )
        manager.init(PebbleSocketIdentifier("test"), unbenutzt<WatchInfo>())
        runCurrent()
        uhr.receivePacket(AppRunStateMessage.AppRunStateStart(uuid))
        runCurrent()
        val eingang = begleiter.eingang.await()

        schicke(uhr, 300)

        // Jetzt holt der Begleiter auf: die juengsten 256, in der Reihenfolge der Uhr.
        val angekommen = mutableListOf<Int>()
        backgroundScope.launch { eingang.collect { angekommen += (it.data[1] as Number).toInt() } }
        runCurrent()
        assertEquals((44 until 300).toList(), angekommen)
        // Jede verworfene steht im Log.
        assertEquals(44, synchronized(geloggt) { geloggt.count { "dropped" in it && "$uuid" in it } })
    }
}
