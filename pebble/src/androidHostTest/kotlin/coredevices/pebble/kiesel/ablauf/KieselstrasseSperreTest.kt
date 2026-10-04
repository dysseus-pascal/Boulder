package coredevices.pebble.kiesel.ablauf

import android.content.Context
import coredevices.pebble.kiesel.Kieselstrasse
import net.osmand.aidlapi.IOsmAndAidlInterface
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Haengt OsmAnd in einer Nachfrage, wartet sonst niemand: der Aufruf in
 * OsmAnds Prozess laeuft ohne die Sperre von Kieselstrasse. Frueher hielt
 * frageAb sie, und das Oeffnen und Schliessen der Uhr-App stand still, bis
 * OsmAnd antwortete - ohne Zeitlimit.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class KieselstrasseSperreTest {
    private val los = CountDownLatch(1)

    @After
    fun ab() {
        los.countDown()
        Kieselstrasse.api = null
    }

    /** OsmAnd, das in getAppInfo haengt, bis [los] faellt. */
    private fun haengendesOsmand(drin: CountDownLatch) = Proxy.newProxyInstance(
        IOsmAndAidlInterface::class.java.classLoader, arrayOf(IOsmAndAidlInterface::class.java),
    ) { _, methode, _ ->
        when (methode.name) {
            "getAppInfo" -> {
                drin.countDown()
                los.await(10, TimeUnit.SECONDS)
                null
            }
            "hashCode" -> 0
            "equals" -> false
            "toString" -> "OsmAnd-Attrappe"
            else -> error("unerwartet: ${methode.name}")
        }
    } as IOsmAndAidlInterface

    @Test
    fun eine_haengende_nachfrage_haelt_die_uhr_app_nicht_auf() {
        val context: Context = RuntimeEnvironment.getApplication()
        val drin = CountDownLatch(1)
        Kieselstrasse.api = haengendesOsmand(drin)

        val nachfrage = thread { Kieselstrasse.frageAb(context) }
        assertTrue(drin.await(5, TimeUnit.SECONDS), "Nachfrage kam nicht bei OsmAnd an")
        val uhrApp = thread { Kieselstrasse.uhrApp(context, false) }
        uhrApp.join(500)
        val wartete = uhrApp.isAlive
        los.countDown()
        uhrApp.join(5_000)
        nachfrage.join(5_000)
        assertFalse(wartete, "Die Uhr-App wartete auf OsmAnd")
    }
}
