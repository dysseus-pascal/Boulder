package coredevices.pebble.kiesel.ablauf

import android.content.Context
import coredevices.pebble.kiesel.Gesundheitsziele
import io.rebble.libpebblecommon.connection.FakeLibPebble
import io.rebble.libpebblecommon.connection.HealthDataApi
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Boulder 0.53: die Ziele aus "Goals" - gemerkt, sofort gueltig und auf dem Weg
 * zur Uhr (setzeZiele schreibt die Zeile "boulder_ziele", siehe ZieleZurUhrTest).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ZieleAblaufTest {
    private lateinit var context: Context
    private val zurUhr: MutableList<Pair<Int, Int>> = Collections.synchronizedList(mutableListOf())
    private val api = object : HealthDataApi by FakeLibPebble() {
        override suspend fun setzeZiele(schritte: Int, schlafMin: Int) {
            zurUhr += schritte to schlafMin
        }
    }

    @Before
    fun auf() {
        context = RuntimeEnvironment.getApplication()
    }

    @Test
    fun ohne_einstellung_die_vorgabe_und_nichts_zur_uhr() {
        assertEquals(Gesundheitsziele.Ziele(10_000, 480), Gesundheitsziele.ziele(context))
        assertNull(Gesundheitsziele.beimStart(context, api))
        assertEquals(emptyList(), zurUhr.toList())
    }

    @Test
    fun gesetzt_gilt_sofort_und_geht_zur_uhr() = runBlocking {
        val job = Gesundheitsziele.setze(context, api, Gesundheitsziele.Ziele(8_000, 450))
        // Die Seiten lesen gleich, nicht erst nach der Datenbank.
        assertEquals(Gesundheitsziele.Ziele(8_000, 450), Gesundheitsziele.ziele(context))
        job.join()
        assertEquals(listOf(8_000 to 450), zurUhr.toList())
        // Beim naechsten Start noch einmal; dieselbe Zeile laesst die Datenbank liegen (ZieleZurUhrTest).
        Gesundheitsziele.beimStart(context, api)!!.join()
        assertEquals(listOf(8_000 to 450, 8_000 to 450), zurUhr.toList())
    }

    @Test
    fun schnelles_tippen_endet_beim_juengsten_wert() = runBlocking {
        (1..20).map { Gesundheitsziele.setze(context, api, Gesundheitsziele.Ziele(8_000 + it * 500, 480)) }.joinAll()
        assertEquals(18_000 to 480, zurUhr.last())
    }
}
