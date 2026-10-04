package coredevices.pebble.kiesel.ablauf

import android.content.Context
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import coredevices.pebble.health.schreibeNaechte
import io.rebble.libpebblecommon.database.entity.NachtAbschnittEntity
import io.rebble.libpebblecommon.database.entity.NachtEntity
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Naechte in die Akte, jede fuer sich: erst den neuen Schlaf bauen und
 * einfuegen, dann nur Boulders alten Schlaf derselben Zeit loeschen. Bis 0.49
 * loeschte der Sync zuerst und baute danach - warf der Bau (HRV ueber 200 ms),
 * war der Schlaf weg, und die Schleife brach fuer alle spaeteren Naechte ab.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NachtSchreibenTest {
    private lateinit var context: Context
    private lateinit var akte: FakeAkte

    // Drei Naechte im Abstand eines Tages, je ab 20:00 UTC.
    private val a = 1_790_000_000L
    private val b = a + 86_400
    private val c = b + 86_400

    @Before
    fun auf() {
        context = RuntimeEnvironment.getApplication()
        akte = FakeAkte(context.packageName)
    }

    private fun schlaf(von: Long, bis: Long, kennung: String) = SleepSessionRecord(
        startTime = Instant.ofEpochSecond(von), startZoneOffset = ZoneOffset.UTC,
        endTime = Instant.ofEpochSecond(bis), endZoneOffset = ZoneOffset.UTC,
        metadata = Metadata.autoRecorded(Device(type = Device.TYPE_WATCH), kennung),
    )

    private fun nacht(beginn: Long, hrv: Int = 40) =
        NachtEntity(beginn, 7200, einschlafen = 60, aufwachen = 540, schlafMinuten = 450, ruhepuls = 50, hrv = hrv)

    private fun kennungen() = akte.alle().filter { it.satz is SleepSessionRecord }.map { it.satz.metadata.clientRecordId to it.herkunft }

    @Test
    fun eine_kaputte_nacht_loescht_nichts_und_haelt_die_naechsten_nicht_auf() {
        val paket = context.packageName
        // Was der Sync frueher aus den Aktivitaetssitzungen schrieb, und eine fremde App.
        akte.legeAb(schlaf(a + 3600, a + 30_000, "pebble-sleep-a"), paket)
        akte.legeAb(schlaf(a + 4000, a + 20_000, "fremd"), "com.andere.app")
        akte.legeAb(schlaf(b + 3600, b + 30_000, "pebble-sleep-b"), paket)
        // Die Akte weist Bs Schlaf ab, wie ohne Recht.
        akte.weistAb = { r -> if (r.metadata.clientRecordId == "boulder-nacht-$b") SecurityException("not allowed") else null }

        val phasen = listOf(NachtAbschnittEntity(a, 50, 300, 1), NachtAbschnittEntity(a, 300, 540, 2))
        val alle = runBlocking {
            schreibeNaechte(context, akte, listOf(nacht(a, hrv = 250) to phasen, nacht(b) to emptyList(), nacht(c) to emptyList()))
        }

        assertFalse(alle, "Bs Abweisung muss zum Wiederholen fuehren")
        val da = kennungen()
        // A steht da - trotz HRV 250 und einer Phase vor dem Einschlafen -, ihr alter Schlaf ist weg.
        assertTrue(("boulder-nacht-$a" to paket) in da, "$da")
        assertTrue(("pebble-sleep-a" to paket) !in da, "$da")
        // Fremdes bleibt immer.
        assertTrue(("fremd" to "com.andere.app") in da, "$da")
        // B wurde abgewiesen: ihr alter Schlaf bleibt, bis der neue steht.
        assertTrue(("pebble-sleep-b" to paket) in da, "$da")
        // C kommt trotzdem.
        assertTrue(("boulder-nacht-$c" to paket) in da, "$da")
    }

    @Test
    fun eine_neu_gerechnete_nacht_ersetzt_sich_selbst_und_laesst_die_nachbarin_stehen() {
        val paket = context.packageName
        runBlocking { schreibeNaechte(context, akte, listOf(nacht(a) to emptyList())) }
        // Die Nachbarin beginnt eine Stunde nach As Aufwachen, im Fenster von zwei Stunden.
        val nachbarin = NachtEntity(a + 600 * 60, 7200, einschlafen = 0, aufwachen = 60, schlafMinuten = 55, ruhepuls = 0, hrv = 0)
        val spaeter = nacht(a).copy(aufwachen = 560, schlafMinuten = 470)
        val alle = runBlocking { schreibeNaechte(context, akte, listOf(nachbarin to emptyList(), spaeter to emptyList())) }

        assertTrue(alle)
        val naechte = akte.saetze<SleepSessionRecord>().associateBy { it.metadata.clientRecordId }
        assertEquals(setOf("boulder-nacht-$a", "boulder-nacht-${a + 36_000}"), naechte.keys)
        assertEquals(a + 560 * 60, naechte.getValue("boulder-nacht-$a").endTime.epochSecond)
        assertEquals(listOf(paket), akte.alle().filter { it.satz is SleepSessionRecord }.map { it.herkunft }.distinct())
    }
}
