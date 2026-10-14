package coredevices.pebble.kiesel.ablauf

import android.content.Context
import android.os.Bundle
import coredevices.pebble.kiesel.Kieselstrasse
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.osmand.aidlapi.IOsmAndAidlInterface
import net.osmand.aidlapi.info.AppInfoParams
import net.osmand.aidlapi.navigation.ANavigationUpdateParams
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Was Kieselstrasse ausserhalb ihrer Sperre an OsmAnd fragt (siehe
 * KieselstrasseSperreTest), kommt in beliebiger Reihenfolge zurueck: eine
 * aeltere Antwort darf keine neuere ueberholen, und eine Bindung, die waehrend
 * eines haengenden Abonnierens kam, bekommt trotzdem ihr Abonnement.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class KieselstrasseWettlaufTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val los = CountDownLatch(1)

    @After
    fun ab() {
        los.countDown()
        Kieselstrasse.api = null
        // Das Abo der Attrappe gilt nicht fuer den naechsten Test; einen Weg dafuer ohne Bindung gibt es nicht.
        Kieselstrasse::class.java.getDeclaredField("aboNummer").apply { isAccessible = true }.setLong(null, -1L)
    }

    /** OsmAnd als Attrappe: nur [antworten] gibt es, jeder andere Aufruf wirft. */
    private fun osmand(name: String, antworten: Map<String, (Array<Any?>) -> Any?>) = Proxy.newProxyInstance(
        IOsmAndAidlInterface::class.java.classLoader, arrayOf(IOsmAndAidlInterface::class.java),
    ) { selbst, methode, args ->
        when (methode.name) {
            "hashCode" -> System.identityHashCode(selbst)
            "equals" -> selbst === args?.get(0)
            "toString" -> name
            else -> antworten[methode.name]?.invoke(args ?: emptyArray()) ?: error("unerwartet: ${methode.name}")
        }
    } as IOsmAndAidlInterface

    /** Was getAppInfo waehrend einer Navigation liefert: die naechste Abzweigung. */
    private fun abzweigung(kuerzel: String, meter: Int) = AppInfoParams(
        null, null,
        Bundle().apply {
            putInt("next_turn_distance", meter)
            putString("next_turn_type", kuerzel)
            putString("next_turn_name", "")
        },
        600, 5_000, 0L, true,
    )

    @Test
    fun eine_aeltere_antwort_ueberholt_keine_neuere() {
        // Takt und Rueckruf fragen fast gleichzeitig. Die erste Frage haengt in OsmAnd und
        // bringt die alte Abbiegung (links); die zweite kommt sofort mit der neuen (rechts).
        val drin = CountDownLatch(1)
        var fragen = 0
        Kieselstrasse.api = osmand(
            "OsmAnd",
            mapOf(
                "getAppInfo" to { _ ->
                    if (synchronized(this) { ++fragen } == 1) {
                        drin.countDown()
                        los.await(10, TimeUnit.SECONDS)
                        abzweigung("TL", 300)
                    } else {
                        abzweigung("TR", 120)
                    }
                },
            ),
        )
        val links = Kieselstrasse.artAusText("TL")
        val rechts = Kieselstrasse.artAusText("TR")

        val ersteFrage = thread { Kieselstrasse.frageAb(context) }
        assertTrue(drin.await(5, TimeUnit.SECONDS), "Erste Frage kam nicht bei OsmAnd an")
        Kieselstrasse.frageAb(context)
        assertEquals(rechts, Kieselstrasse.letzteArt)

        // Jetzt kommt die aeltere Antwort zurueck - die Uhr soll nicht auf "links" zurueckspringen.
        los.countDown()
        ersteFrage.join(5_000)
        assertEquals(rechts, Kieselstrasse.letzteArt, "die alte Abbiegung (Art $links) ueberholte die neue")
    }

    @Test
    fun eine_neue_bindung_bekommt_ihr_abo_auch_wenn_das_alte_noch_haengt() {
        // Bindung A haengt beim Abonnieren; inzwischen wird sie geloest und B gebunden.
        val drin = CountDownLatch(1)
        val anA = Collections.synchronizedList(mutableListOf<Boolean>())
        val anB = Collections.synchronizedList(mutableListOf<Boolean>())
        val a = osmand(
            "A",
            mapOf(
                "registerForNavigationUpdates" to { args ->
                    val bestellen = (args[0] as ANavigationUpdateParams).isSubscribeToUpdates
                    anA += bestellen
                    if (bestellen) {
                        drin.countDown()
                        los.await(10, TimeUnit.SECONDS)
                    }
                    11L
                },
            ),
        )
        val b = osmand(
            "B",
            mapOf("registerForNavigationUpdates" to { args -> anB += (args[0] as ANavigationUpdateParams).isSubscribeToUpdates; 22L }),
        )
        Kieselstrasse.api = a
        val aboA = thread { Kieselstrasse.abonniere(context) }
        assertTrue(drin.await(5, TimeUnit.SECONDS), "Abonnieren kam nicht bei A an")

        // B ist gebunden und will abonnieren - waehrend A noch haengt, geht das nicht.
        Kieselstrasse.api = b
        Kieselstrasse.abonniere(context)
        assertEquals(emptyList(), anB.toList())

        // A kehrt zurueck: sein Abo gehoert zu keiner Bindung mehr, B bekommt seines.
        los.countDown()
        aboA.join(5_000)
        assertEquals(listOf(true), anB.toList(), "B blieb ohne Abonnement")
        assertEquals(Kieselstrasse.Lage.ABONNIERT, Kieselstrasse.lage)
        // Und A wird wieder abbestellt (im Hintergrund).
        runBlocking { withTimeout(5_000) { while (anA.toList() != listOf(true, false)) delay(20) } }
    }
}
