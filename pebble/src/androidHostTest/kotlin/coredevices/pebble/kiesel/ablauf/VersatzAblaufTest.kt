package coredevices.pebble.kiesel.ablauf

import android.content.Context
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.NutritionRecord
import coredevices.pebble.kiesel.Akte
import coredevices.pebble.kiesel.Akteneintraege
import coredevices.pebble.kiesel.Drinktervall
import coredevices.pebble.kiesel.KieselsportBegleiter
import coredevices.pebble.kiesel.Wartend
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Zeitzone und Kennung der Eintraege: ein Glas, das in Zuerich ankam und erst
 * nach dem Flug nach New York eingetragen wird, steht in der Akte mit dem
 * Versatz von Zuerich. Und zwei Tassen von Hand in derselben Sekunde sind
 * zwei Eintraege - bei gleicher Kennung ersetzte die Akte die erste.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VersatzAblaufTest {
    private lateinit var context: Context
    private lateinit var akte: FakeAkte
    private val vorher = TimeZone.getDefault()

    @Before
    fun auf() {
        context = RuntimeEnvironment.getApplication()
        akte = FakeAkte(context.packageName)
        Akte.ersatz = akte
    }

    @After
    fun ab() {
        Akte.ersatz = null
        TimeZone.setDefault(vorher)
    }

    @Test
    fun ein_nachgeholtes_glas_traegt_den_versatz_vom_empfang() {
        val zuerich = ZoneId.of("Europe/Zurich")
        TimeZone.setDefault(TimeZone.getTimeZone(zuerich))
        val wann = Instant.now().epochSecond - 3600
        // Der Weg der Uhr: zerlegt beim Empfang, abgelegt, bestaetigt.
        val n = KieselsportBegleiter.zerlege(mapOf(Drinktervall.DT_DRANK_AT to wann.toInt(), Drinktervall.DT_GLASS_ML to 250, Drinktervall.DT_GLASSES to 8))
        val datei = assertNotNull(Wartend.lege(context, n, "uhrapp-wartend-test"))

        // Der Flug: nachgetragen wird in New York.
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        val gelesen = assertNotNull(Wartend.lies(datei))
        runBlocking { Drinktervall.verarbeite(context, gelesen, nachgeholt = true) }

        val glas = akte.saetze<HydrationRecord>().single()
        assertEquals(zuerich.rules.getOffset(Instant.ofEpochSecond(wann)), glas.startZoneOffset)
    }

    @Test
    fun zwei_tassen_in_derselben_sekunde_sind_zwei_eintraege() {
        val jetzt = Instant.ofEpochSecond(Instant.now().epochSecond - 60)
        assertTrue(runBlocking { Akteneintraege.trinkeKoffein(context, 0x11, jetzt) })
        assertTrue(runBlocking { Akteneintraege.trinkeKoffein(context, 0x11, jetzt) })
        val tassen = akte.saetze<NutritionRecord>()
        assertEquals(2, tassen.size)
        // Die Gesundheitsseite erkennt sie am Anfang der Kennung.
        assertTrue(tassen.all { it.metadata.clientRecordId!!.startsWith("koffein-") })
    }
}
