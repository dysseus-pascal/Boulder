package coredevices.pebble.kiesel.ablauf

import android.content.Context
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import coredevices.pebble.health.alleAkteRechte
import coredevices.pebble.kiesel.Akte
import coredevices.pebble.kiesel.KOERPER_RECHTE
import coredevices.pebble.kiesel.Rechtefrage
import coredevices.pebble.kiesel.TrainingsDaten
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Die Rechte der Akte im Ablauf: was als fehlend zaehlt (Zahl, Abzeichen und
 * Sportzeile) und wann der Start fragt.
 *
 * Die Rechte der Waage zaehlen nicht als fehlend - wer keine Waage hat,
 * verweigert sie zu Recht und soll keine Dauerwarnung sehen. Und eine
 * Fassung, die schon gefragt hat, liest die Akte beim Start gar nicht erst.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RechteAblaufTest {
    private lateinit var context: Context
    private lateinit var akte: FakeAkte
    private val training = HealthPermission.getWritePermission(ExerciseSessionRecord::class)

    @Before
    fun auf() {
        context = RuntimeEnvironment.getApplication()
        akte = FakeAkte(context.packageName)
        Akte.ersatz = akte
        // Leer: der Dialog selbst (PlatformHealthSync) ist nicht Teil dieses Tests.
        startKoin { }
    }

    @After
    fun ab() {
        Akte.ersatz = null
        stopKoin()
    }

    private fun fehlend() = runBlocking { TrainingsDaten.fehlendeRechte(context) }

    @Test
    fun ohne_die_rechte_der_waage_fehlt_nichts() {
        val alle = alleAkteRechte(akte)
        assertTrue(KOERPER_RECHTE.isNotEmpty() && alle.containsAll(KOERPER_RECHTE))
        akte.erteilt = alle - KOERPER_RECHTE
        assertEquals(emptySet(), fehlend())
        // Fehlt eines fuer die Trainings, steht genau das da - die der Waage nicht dazu.
        akte.erteilt = alle - KOERPER_RECHTE - training
        assertEquals(setOf(training), fehlend())
    }

    @Test
    fun eine_akte_die_nicht_antwortet_meldet_nichts_als_fehlend() {
        akte.rechteWerfen = IllegalStateException("Akte beschaeftigt")
        assertEquals(emptySet(), fehlend())
    }

    private val prefs get() = context.getSharedPreferences("boulder_rechtefrage", Context.MODE_PRIVATE)
    private val fassung get() = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()

    @Test
    fun eine_fassung_fragt_einmal_und_liest_danach_die_akte_nicht_mehr() {
        akte.erteilt = alleAkteRechte(akte) - training
        runBlocking { Rechtefrage.beimStart(context) }
        // Gemerkt VOR dem Dialog: stirbt die App darin, fragt der naechste Start nicht in einer Schleife.
        assertEquals(fassung, prefs.getString("akte_rechte_gefragt_fassung", null))
        val gelesen = akte.rechteGefragt
        assertEquals(1, gelesen)
        runBlocking { Rechtefrage.beimStart(context) }
        // Derselbe Start der Fassung liest die Akte nicht noch einmal.
        assertEquals(gelesen, akte.rechteGefragt)
    }

    @Test
    fun ohne_fehlendes_recht_wird_nichts_gemerkt() {
        akte.erteilt = alleAkteRechte(akte)
        runBlocking { Rechtefrage.beimStart(context) }
        assertEquals(1, akte.rechteGefragt)
        // Fehlt nichts, bleibt die Fassung offen: verliert die App spaeter ein Recht, fragt sie doch noch.
        assertNull(prefs.getString("akte_rechte_gefragt_fassung", null))
    }
}
