package coredevices.pebble.kiesel.ablauf

import android.content.Context
import coredevices.pebble.kiesel.Drinktervall
import coredevices.pebble.kiesel.KieselNachricht
import coredevices.pebble.kiesel.Wartend
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Wartend schreibt ganz oder gar nicht: gleich nach dem Schreiben geht das
 * ACK an die Uhr. Die Platte ist hier eine, die nach einigen Bytes voll ist -
 * wie nach einem Stromausfall in der Schreibpause liegt dann nur ein Teil da.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WartendAtomarTest {
    private lateinit var context: Context
    private val echtePlatte = Wartend.platte

    @Before
    fun auf() {
        context = RuntimeEnvironment.getApplication()
    }

    @After
    fun ab() {
        Wartend.platte = echtePlatte
    }

    /** Schreibt nur die ersten [n] Bytes, dann ist die Platte voll. */
    private fun volleNach(n: Int) {
        Wartend.platte = { f, b ->
            f.writeBytes(b.copyOf(minOf(n, b.size)))
            throw IOException("No space left on device")
        }
    }

    private fun glas(ml: Long, mitKaffee: Boolean) = KieselNachricht(
        buildMap {
            put(Drinktervall.DT_DRANK_AT, 1_790_000_000L)
            put(Drinktervall.DT_GLASS_ML, ml)
            if (mitKaffee) put(Drinktervall.DT_COFFEE_AT, 1_790_000_100L)
        },
    )

    @Test
    fun ein_abgebrochenes_ersetzen_laesst_den_alten_stand_lesbar() {
        val datei = assertNotNull(Wartend.lege(context, glas(250, mitKaffee = true), "wartend-test"))
        volleNach(10)
        Wartend.ersetze(datei, glas(250, mitKaffee = false))
        // Der alte Stand mit dem Kaffee ist noch ganz da - nicht gekuerzt und dann geloescht.
        val gelesen = assertNotNull(Wartend.lies(datei), "Datei nach Abbruch unlesbar")
        assertEquals(1_790_000_100L, gelesen.zahlen[Drinktervall.DT_COFFEE_AT])
    }

    @Test
    fun ein_abgebrochenes_ablegen_hinterlaesst_keine_halbe_nachricht() {
        volleNach(10)
        assertNull(Wartend.lege(context, glas(250, mitKaffee = false), "wartend-test"))
        val ordner = File(context.filesDir, "wartend-test")
        // Keine halbe .json: die Uhr bekam ein NACK und schickt die Nachricht wieder.
        assertTrue(ordner.listFiles().orEmpty().none { it.name.endsWith(".json") }, ordner.list().orEmpty().joinToString())
        assertTrue(Wartend.alle(context, "wartend-test").isEmpty())
    }

    @Test
    fun ersetzen_ersetzt() {
        val datei = assertNotNull(Wartend.lege(context, glas(250, mitKaffee = true), "wartend-test"))
        Wartend.ersetze(datei, glas(300, mitKaffee = false))
        assertEquals(300L, Wartend.lies(datei)!!.zahlen[Drinktervall.DT_GLASS_ML])
        assertEquals(listOf(datei.name), File(context.filesDir, "wartend-test").list()!!.toList())
    }
}
