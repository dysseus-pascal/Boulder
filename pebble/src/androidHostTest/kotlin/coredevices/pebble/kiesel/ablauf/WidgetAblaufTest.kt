package coredevices.pebble.kiesel.ablauf

import android.content.Context
import coredevices.pebble.kiesel.Akte
import coredevices.pebble.kiesel.GesundheitWidget
import coredevices.pebble.kiesel.KieselNachricht
import coredevices.pebble.kiesel.Kieselsport
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * DIE DREI STUNDEN DES WIDGETS AM ECHTEN WEG: ein Training aus der Uhr in die
 * Akte (FakeAkte), dann das, was die Kachel daraus nimmt
 * (GesundheitWidget.trainingFuerWidget): knapp unter drei Stunden steht das
 * Training da, knapp darueber nicht mehr.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WidgetAblaufTest {
    private lateinit var context: Context
    private lateinit var akte: FakeAkte
    private val zone = ZoneId.systemDefault()

    @Before
    fun auf() {
        context = RuntimeEnvironment.getApplication()
        akte = FakeAkte(context.packageName)
        Akte.ersatz = akte
    }

    @After
    fun ab() {
        Akte.ersatz = null
    }

    /** Ein Krafttraining von 20 min, das [vorSekunden] vor jetzt endete - nur die Zusammenfassung, wie von der Uhr. */
    private fun training(vorSekunden: Long): Long {
        val ende = Instant.now().epochSecond - vorSekunden
        val beginn = ende - 1200
        runBlocking {
            Kieselsport.verarbeite(
                context,
                KieselNachricht(mapOf(Kieselsport.SP_ART to 3L, Kieselsport.SP_BEGINN to beginn, Kieselsport.SP_DAUER to 1200L, Kieselsport.SP_ENDE to ende)),
                ende + 10,
            )
        }
        return beginn
    }

    private fun kachel(jetzt: Instant) = runBlocking { GesundheitWidget.trainingFuerWidget(context, jetzt, zone) }

    @Test
    fun knapp_unter_drei_stunden_steht_das_training_da() {
        val beginn = training(vorSekunden = 3 * 3600L - 300)
        val t = assertNotNull(kachel(Instant.now()))
        assertEquals(beginn, t.beginn)
        assertEquals(20L, t.minuten)
    }

    @Test
    fun knapp_ueber_drei_stunden_nicht_mehr() {
        // Gelesen wird es noch (2 h 55 min), aber das Widget prueft zehn Minuten spaeter: 3 h 05 min.
        training(vorSekunden = 3 * 3600L - 300)
        assertNull(kachel(Instant.now().plusSeconds(600)))
        // Und was schon beim Lesen zu alt ist, kommt gar nicht erst.
        akte.alle().forEach { akte.loesche(it.satz.metadata.id) }
        training(vorSekunden = 3 * 3600L + 300)
        assertNull(kachel(Instant.now()))
    }
}
