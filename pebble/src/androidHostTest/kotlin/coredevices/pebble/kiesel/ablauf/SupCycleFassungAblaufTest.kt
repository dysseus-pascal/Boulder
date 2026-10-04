package coredevices.pebble.kiesel.ablauf

import android.content.Context
import androidx.health.connect.client.records.NutritionRecord
import coredevices.pebble.kiesel.Akte
import coredevices.pebble.kiesel.KieselNachricht
import coredevices.pebble.kiesel.SupCycle
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Uhr-App und Boulder in verschiedenen Fassungen (Audit M1): SupCycle zaehlt
 * seit PLANFASSUNG 2 Kalendertage, bis 0.15.0 die Ortsmitternacht durch
 * 86400. Durch verarbeite und heute, wie die Seiten sie lesen - in Zuerich,
 * wo beide Zaehlungen um einen Tag auseinanderliegen.
 *
 * - DIE HAKEN HAENGEN AM DATUM (TODAY), nicht an einer Tagesnummer: sie
 *   bleiben mit jeder Uhr-App. Das gilt auch fuer Boulder bis 0.49.
 * - DIE VORSCHAU (was heute ansteht, bevor die Uhr sich meldet) rechnet in
 *   der Zaehlung des Plans. Boulder bis 0.49 kennt nur die alte und liegt mit
 *   einer neuen Uhr-App einen Tag daneben, bis die Uhr sich meldet.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SupCycleFassungAblaufTest {
    private lateinit var context: Context
    private lateinit var akte: FakeAkte
    private lateinit var vorherZone: TimeZone
    private lateinit var zone: ZoneId
    private lateinit var heute: LocalDate

    @Before
    fun auf() {
        vorherZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Zurich"))
        zone = ZoneId.systemDefault()
        heute = LocalDate.now(zone)
        context = RuntimeEnvironment.getApplication()
        akte = FakeAkte(context.packageName)
        Akte.ersatz = akte
    }

    @After
    fun ab() {
        Akte.ersatz = null
        TimeZone.setDefault(vorherZone)
    }

    private fun ymd(tag: LocalDate) = (tag.year * 10000 + tag.monthValue * 100 + tag.dayOfMonth).toLong()

    /** So zaehlte die Uhr bis 0.15.0 (plan.c): Ortsmitternacht durch 86400 - in Zuerich der Vortag. */
    private fun tagBis015(tag: LocalDate): Int = Math.floorDiv(tag.atStartOfDay(zone).toEpochSecond(), 86_400L).toInt()

    /** Ein Platz im 26-Byte-Format der Uhr: "Zink", alle 2 Tage ab [anker]. */
    private fun plan(anker: Int): ByteArray {
        val b = ByteArray(26)
        "Zink".toByteArray().copyInto(b)
        b[16] = 8; b[18] = 1; b[19] = 2
        for (i in 0 until 4) b[22 + i] = (anker shr (8 * i)).toByte()
        return b
    }

    /** Die Meldung vom [tag]: Zink faellig und [genommen], mit Plan; [fassung] null = Uhr-App bis 0.15.0. */
    private fun meldung(tag: LocalDate, anker: Int, fassung: Long?, genommen: Long): KieselNachricht {
        val zahlen = mutableMapOf(SupCycle.SC_TODAY to ymd(tag), SupCycle.SC_DUE to 1L, SupCycle.SC_TAKEN to genommen)
        if (fassung != null) zahlen[SupCycle.SC_PLANFASSUNG] = fassung
        val haken = if (genommen != 0L) minOf(Instant.now().epochSecond - 60, tag.atTime(8, 5).atZone(zone).toEpochSecond()) else 0L
        return KieselNachricht(
            zahlen = zahlen,
            texte = mapOf(SupCycle.SC_NAMES to "Zink"),
            bytes = mapOf(SupCycle.SC_PLAN to plan(anker), SupCycle.SC_TAKEN_AT to SupCycle.hakenzeitenBytes(listOf(haken))),
        )
    }

    private fun verarbeite(n: KieselNachricht) = runBlocking { SupCycle.verarbeite(context, n) }

    @Test
    fun neue_uhr_haken_bleiben() {
        verarbeite(meldung(heute, heute.toEpochDay().toInt(), 2L, 0b1))
        assertEquals(listOf("Zink" to true), SupCycle.heute(context)!!.heute { "Slot $it" }.map { it.name to it.genommen })
        assertEquals(listOf("Zink"), akte.saetze<NutritionRecord>().map { it.name })
    }

    @Test
    fun alte_uhr_haken_bleiben() {
        verarbeite(meldung(heute, tagBis015(heute), null, 0b1))
        assertEquals(listOf("Zink" to true), SupCycle.heute(context)!!.heute { "Slot $it" }.map { it.name to it.genommen })
        assertEquals(listOf("Zink"), akte.saetze<NutritionRecord>().map { it.name })
    }

    @Test
    fun neue_uhr_vorschau_rechnet_in_kalendertagen() {
        // Gestern meldete die Uhr (Fassung 2): Zink alle 2 Tage ab gestern. Heute hat sie sich
        // noch nicht gemeldet - Zink steht heute nicht an.
        val gestern = heute.minusDays(1)
        verarbeite(meldung(gestern, gestern.toEpochDay().toInt(), 2L, 0b1))
        assertEquals(0L, SupCycle.heute(context)!!.faellig)
    }

    @Test
    fun alte_uhr_vorschau_rechnet_wie_die_alte_uhr() {
        // Dasselbe mit einer Uhr-App bis 0.15.0: ihr Anker zaehlt die Ortsmitternacht.
        val gestern = heute.minusDays(1)
        verarbeite(meldung(gestern, tagBis015(gestern), null, 0b1))
        assertEquals(0L, SupCycle.heute(context)!!.faellig)
    }
}
