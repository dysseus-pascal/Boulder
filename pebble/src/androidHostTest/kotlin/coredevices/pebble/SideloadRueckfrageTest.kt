package coredevices.pebble

import android.content.Context
import com.eygraber.uri.Uri
import coredevices.analytics.CoreAnalytics
import coredevices.database.AppstoreSourceDao
import coredevices.pebble.account.PebbleAccount
import coredevices.pebble.firmware.FirmwareUpdateUiTracker
import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.connection.FakeLibPebble
import io.rebble.libpebblecommon.connection.LibPebble
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Eine .pbw, die eine andere App der MainActivity schickt, wird erst nach
 * der Rueckfrage installiert - wie eine .pbz. Sonst koennte jede App eine
 * Uhr-App mit der UUID von Drinktervall unterschieben, und der Mithoerer
 * schriebe ihre Meldungen in die Akte. Kopiert wird mit Obergrenze.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SideloadRueckfrageTest {
    private lateinit var context: Context
    private val installiert = Collections.synchronizedList(mutableListOf<ByteArray>())

    /** LibPebble im Speicher; merkt sich, was installiert wurde. */
    private val libPebble = object : LibPebble by FakeLibPebble() {
        override suspend fun sideloadApp(pbwPath: Path): Boolean {
            installiert += File(pbwPath.toString()).readBytes()
            return true
        }
    }

    /** Was der Handler fuer .pbw nicht braucht: jeder Aufruf waere ein Fehler im Test. */
    private inline fun <reified T> nieGebraucht(): T = java.lang.reflect.Proxy.newProxyInstance(
        T::class.java.classLoader, arrayOf(T::class.java),
    ) { _, m, _ -> error("unerwartet: ${m.name}") } as T

    @Before
    fun auf() {
        context = RuntimeEnvironment.getApplication()
    }

    private fun handler() = RealPebbleDeepLinkHandler(
        nieGebraucht<PebbleAccount>(), libPebble, nieGebraucht<CoreAnalytics>(), AppContext(context),
        nieGebraucht<AppstoreSourceDao>(), nieGebraucht<FirmwareUpdateUiTracker>(),
    )

    private fun warte(was: String, bedingung: () -> Boolean) = runBlocking {
        withTimeout(10_000) { while (!bedingung()) delay(20) }
        assertTrue(bedingung(), was)
    }

    @Test
    fun eine_pbw_wird_erst_nach_der_rueckfrage_installiert() {
        val pbw = byteArrayOf(0x50, 0x4B, 3, 4, 1, 2, 3)
        shadowOf(context.contentResolver).registerInputStream(android.net.Uri.parse("content://dateien/app.pbw"), ByteArrayInputStream(pbw))
        val h = handler()
        assertTrue(h.handle(Uri.parse("content://dateien/app.pbw")))

        warte("Rueckfrage") { h.pendingFirmwareSideload.value != null }
        val offen = h.pendingFirmwareSideload.value!!
        assertEquals(SideloadArt.App, offen.art)
        assertEquals("app.pbw", offen.fileName)
        runBlocking { delay(300) }
        assertTrue(installiert.isEmpty(), "ohne Rueckfrage installiert")

        h.confirmPendingFirmwareSideload()
        warte("Installation") { installiert.isNotEmpty() }
        assertContentEquals(pbw, installiert.single())
    }

    @Test
    fun abgelehnt_wird_nichts_installiert_und_die_kopie_ist_weg() {
        shadowOf(context.contentResolver).registerInputStream(android.net.Uri.parse("content://dateien/x.pbl"), ByteArrayInputStream(byteArrayOf(1, 2)))
        val h = handler()
        h.handle(Uri.parse("content://dateien/x.pbl"))
        warte("Rueckfrage") { h.pendingFirmwareSideload.value != null }
        val datei = File(h.pendingFirmwareSideload.value!!.file.toString())
        assertEquals(SideloadArt.Sprachpaket, h.pendingFirmwareSideload.value!!.art)
        assertTrue(datei.exists())
        h.dismissPendingFirmwareSideload()
        assertFalse(datei.exists())
        assertNull(h.pendingFirmwareSideload.value)
        assertTrue(installiert.isEmpty())
    }

    @Test
    fun eine_zu_grosse_datei_wird_nicht_kopiert() {
        // 100 MB Nullen, erzeugt beim Lesen - ein Strom, der den Speicher fuellen wuerde.
        var rest = 100L * 1024 * 1024
        val strom = object : InputStream() {
            override fun read(): Int = if (rest-- > 0) 0 else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (rest <= 0) return -1
                val n = minOf(len.toLong(), rest).toInt()
                b.fill(0, off, off + n)
                rest -= n
                return n
            }
        }
        shadowOf(context.contentResolver).registerInputStream(android.net.Uri.parse("content://dateien/gross.pbw"), strom)
        assertNull(writeFile(AppContext(context), Uri.parse("content://dateien/gross.pbw")))
        assertFalse(File(context.cacheDir, "sideloaded_file").exists())
        // Nicht bis zum Ende gelesen: abgebrochen bei der Grenze.
        assertTrue(rest > 0)
    }

    @Test
    fun handle_kopiert_nicht_auf_dem_aufrufenden_faden() {
        // handle() laeuft auf dem Hauptfaden; ein langsamer Anbieter haelt dort sonst die App an.
        val frei = java.util.concurrent.CountDownLatch(1)
        val strom = object : InputStream() {
            private var gelesen = false
            override fun read(): Int {
                frei.await()
                return if (gelesen) -1 else 1.also { gelesen = true }
            }
        }
        shadowOf(context.contentResolver).registerInputStream(android.net.Uri.parse("content://dateien/langsam.pbw"), strom)
        val h = handler()
        val faden = kotlin.concurrent.thread { h.handle(Uri.parse("content://dateien/langsam.pbw")) }
        faden.join(1_000)
        val wartete = faden.isAlive
        frei.countDown()
        faden.join(5_000)
        assertFalse(wartete, "handle() wartete auf die Datei")
        warte("Rueckfrage") { h.pendingFirmwareSideload.value != null }
    }

    @Test
    fun eine_kleine_datei_wird_kopiert() {
        shadowOf(context.contentResolver).registerInputStream(android.net.Uri.parse("content://dateien/k.pbw"), ByteArrayInputStream(byteArrayOf(7, 8, 9)))
        val pfad = assertNotNull(writeFile(AppContext(context), Uri.parse("content://dateien/k.pbw")))
        assertContentEquals(byteArrayOf(7, 8, 9), File(pfad.toString()).readBytes())
    }
}
