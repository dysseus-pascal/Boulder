package coredevices.pebble.kiesel.ablauf

import android.content.Context
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.NutritionRecord
import coredevices.pebble.kiesel.Akte
import coredevices.pebble.kiesel.Drinktervall
import coredevices.pebble.kiesel.KieselNachricht
import coredevices.pebble.kiesel.SupCycle
import coredevices.pebble.kiesel.Verarbeitet
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Der echte Weg von SupCycle und Drinktervall: Meldung der Uhr hinein, durch
 * verarbeite in die Akte (FakeAkte), und was die Seiten danach lesen. Bis
 * 0.48 gab es fuer verarbeite, wasser, kaffee und verwirf keinen einzigen
 * Test - ein im Strahl verworfenes Praeparat kam mit dem naechsten Haken
 * wieder in die Akte, und ein Ziel von gestern galt als heutiges, ohne dass
 * es auffiel.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UhrAppsAblaufTest {
    private lateinit var context: Context
    private lateinit var akte: FakeAkte
    private val zone = ZoneId.systemDefault()
    private val heute = LocalDate.now(zone)

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

    private fun ymd(tag: LocalDate) = (tag.year * 10000 + tag.monthValue * 100 + tag.dayOfMonth).toLong()

    // --- SupCycle ---

    /**
     * Wann Platz 0 und 1 heute abgehakt wurden: vor zehn und neun Minuten,
     * kurz nach Mitternacht um Mitternacht - NIE IN DER ZUKUNFT. Bis hierher
     * standen sie fest auf 7:10; SupCycle legt einen Haken aber nie nach jetzt
     * (die Akte lehnt Zukunft ab), und zwischen 0 und 7:10 Uhr lief der Test rot.
     */
    private val hakenHeute = listOf(0, 1).map { i ->
        maxOf(heute.atStartOfDay(zone).toEpochSecond(), Instant.now().epochSecond / 60 * 60 - 600 + 60L * i)
    }

    /** Zwei Praeparate faellig; [genommen] als Maske. Die Hakenzeiten: heute [hakenHeute], an einem frueheren Tag 7:10 und 7:11. */
    private fun stand(genommen: Long, tag: LocalDate = heute) = KieselNachricht(
        zahlen = mapOf(SupCycle.SC_TODAY to ymd(tag), SupCycle.SC_DUE to 0b11L, SupCycle.SC_TAKEN to genommen),
        texte = mapOf(SupCycle.SC_NAMES to "Zinc\nD3"),
        bytes = mapOf(
            SupCycle.SC_TAKEN_AT to SupCycle.hakenzeitenBytes(
                listOf(0, 1).map { i ->
                    if ((genommen shr i) and 1L == 0L) 0L
                    else if (tag == heute) hakenHeute[i]
                    else tag.atTime(7, 10 + i).atZone(zone).toEpochSecond()
                },
            ),
        ),
    )

    private fun supcycle(n: KieselNachricht): Verarbeitet? = runBlocking { SupCycle.verarbeite(context, n) }

    private fun praeparate() = akte.saetze<NutritionRecord>().map { it.name }.sortedBy { it }

    @Test
    fun ein_haken_wird_ein_eintrag_auf_seine_zeit() {
        val v = assertNotNull(supcycle(stand(0b01)))
        assertFalse(v.gescheitert)
        val r = akte.saetze<NutritionRecord>().single()
        assertEquals("Zinc", r.name)
        assertEquals(SupCycle.kennung(heute, 0, "Zinc"), r.metadata.clientRecordId)
        // Die Zeit des Hakens, nicht die der Ankunft.
        assertEquals(Instant.ofEpochSecond(hakenHeute[0]), r.startTime)
        // Dieselbe Meldung noch einmal (die Uhr sah die Bestaetigung nicht): kein zweiter Eintrag.
        supcycle(stand(0b01))
        assertEquals(1, akte.saetze<NutritionRecord>().size)
    }

    @Test
    fun ein_im_strahl_verworfenes_praeparat_kommt_mit_dem_naechsten_haken_nicht_wieder() {
        supcycle(stand(0b01))
        val zinc = akte.saetze<NutritionRecord>().single()
        // Der Tagesstrahl loescht den Eintrag und meldet ihn SupCycle als verworfen.
        akte.loesche(zinc.metadata.id)
        SupCycle.verwirf(context, listOf(zinc.metadata.clientRecordId))
        // Auf der Uhr haengt der Haken noch (die Ruecknahme ist unterwegs), und D3 kommt dazu.
        supcycle(stand(0b11))
        assertEquals(listOf("D3"), praeparate())
        // Auch die Seite zaehlt Zinc nicht als genommen.
        val liste = SupCycle.heute(context)!!.heute { "Slot $it" }
        assertEquals(listOf("Zinc" to false, "D3" to true), liste.map { it.name to it.genommen })
    }

    @Test
    fun ein_liegengebliebener_stand_von_gestern_traegt_nur_nach() {
        val gestern = heute.minusDays(1)
        // Gestern hakte die Uhr D3 ab, aber die Akte nahm es nicht (Recht fehlte): die Meldung bleibt liegen.
        akte.weistAb = { SecurityException("WRITE_NUTRITION fehlt") }
        assertTrue(assertNotNull(supcycle(stand(0b10, tag = gestern))).gescheitert)
        akte.weistAb = { null }
        // Heute der erste Stand, danach kommt die liegengebliebene von gestern noch einmal an die Reihe.
        supcycle(stand(0b01))
        supcycle(stand(0b10, tag = gestern))
        // Sie kommt auf gestern, ...
        val d3 = akte.saetze<NutritionRecord>().single { it.name == "D3" }
        assertEquals(SupCycle.kennung(gestern, 1, "D3"), d3.metadata.clientRecordId)
        assertEquals(gestern, d3.startTime.atZone(zone).toLocalDate())
        // ... und der Stand von heute bleibt der von heute.
        val liste = SupCycle.heute(context)!!.heute { "Slot $it" }
        assertEquals(listOf("Zinc" to true, "D3" to false), liste.map { it.name to it.genommen })
    }

    @Test
    fun weist_die_akte_ab_kommt_derselbe_stand_wieder_durch() {
        akte.weistAb = { SecurityException("WRITE_NUTRITION fehlt") }
        val v = assertNotNull(supcycle(stand(0b01)))
        assertTrue(v.gescheitert)
        assertEquals(emptyList(), praeparate())
        // Recht erteilt, dieselbe Meldung beim naechsten Start: jetzt steht es drin.
        akte.weistAb = { null }
        assertFalse(assertNotNull(supcycle(stand(0b01))).gescheitert)
        assertEquals(listOf("Zinc"), praeparate())
    }

    // --- Drinktervall ---

    private fun drinktervall(zahlen: Map<Int, Long>, nachgeholt: Boolean = false): Verarbeitet? =
        runBlocking { Drinktervall.verarbeite(context, KieselNachricht(zahlen), nachgeholt) }

    @Test
    fun ein_glas_wird_ein_eintrag_einmal() {
        val wann = Instant.now().epochSecond - 900
        val v = assertNotNull(drinktervall(mapOf(Drinktervall.DT_DRANK_AT to wann, Drinktervall.DT_GLASS_ML to 330, Drinktervall.DT_GLASSES to 8)))
        assertFalse(v.gescheitert)
        val r = akte.saetze<HydrationRecord>().single()
        assertEquals(330.0, r.volume.inMilliliters, 0.001)
        assertEquals(wann, r.startTime.epochSecond)
        // Die Uhr schickt ihren Stand bei jeder Gelegenheit mit: dasselbe Glas zaehlt einmal.
        drinktervall(mapOf(Drinktervall.DT_DRANK_AT to wann, Drinktervall.DT_GLASS_ML to 330))
        assertEquals(1, akte.saetze<HydrationRecord>().size)
        // Mit einem Glas steht in GLASS_ML dessen Menge - nicht die Glasgroesse fuer das Ziel:
        // das Ziel nimmt die 8 Glaeser, die Glasgroesse bleibt die Vorgabe 300 ml.
        assertEquals(Drinktervall.Ziel(8, 300), Drinktervall.ziel(context))
    }

    @Test
    fun ein_kaffee_bringt_koffein_in_die_akte() {
        val wann = Instant.now().epochSecond - 1200
        // Kaffee (1) mit Milch (0x10).
        drinktervall(mapOf(Drinktervall.DT_COFFEE_AT to wann, Drinktervall.DT_COFFEE_KIND to 0x11L))
        val r = akte.saetze<NutritionRecord>().single()
        assertEquals(95.0, r.caffeine!!.inMilligrams, 0.001)
        assertEquals(wann, r.startTime.epochSecond)
    }

    @Test
    fun das_erhoehte_ziel_gilt_fuer_den_tag_der_uhr_nicht_den_der_ankunft() {
        // Gestern 23:59 auf 10 Glaeser erhoeht, angekommen heute: heute gilt wieder das Soll 8.
        drinktervall(mapOf(Drinktervall.DT_GLASSES to 10, Drinktervall.DT_TARGET to 8, Drinktervall.DT_GLASS_ML to 250, Drinktervall.DT_ZIEL_TAG to ymd(heute.minusDays(1))))
        assertEquals(Drinktervall.Ziel(8, 250), Drinktervall.ziel(context))
        // Fuer heute erhoeht: es gilt.
        drinktervall(mapOf(Drinktervall.DT_GLASSES to 10, Drinktervall.DT_TARGET to 8, Drinktervall.DT_GLASS_ML to 250, Drinktervall.DT_ZIEL_TAG to ymd(heute)))
        assertEquals(Drinktervall.Ziel(10, 250), Drinktervall.ziel(context))
    }

    @Test
    fun eine_nachgeholte_meldung_aendert_das_ziel_nicht() {
        drinktervall(mapOf(Drinktervall.DT_GLASSES to 9, Drinktervall.DT_TARGET to 9, Drinktervall.DT_GLASS_ML to 250, Drinktervall.DT_ZIEL_TAG to ymd(heute)))
        // Die Meldung von vor drei Tagen lag auf der Platte: ihr Glas zaehlt, ihr Ziel nicht.
        val wann = Instant.now().epochSecond - 3 * 86_400
        drinktervall(mapOf(Drinktervall.DT_DRANK_AT to wann, Drinktervall.DT_GLASS_ML to 250, Drinktervall.DT_GLASSES to 6, Drinktervall.DT_TARGET to 6), nachgeholt = true)
        assertEquals(Drinktervall.Ziel(9, 250), Drinktervall.ziel(context))
        assertEquals(wann, akte.saetze<HydrationRecord>().single().startTime.epochSecond)
    }

    @Test
    fun nimmt_die_akte_nur_den_kaffee_bleibt_nur_das_glas_liegen() {
        akte.weistAb = { r -> if (r is HydrationRecord) SecurityException("WRITE_HYDRATION fehlt") else null }
        val wann = Instant.now().epochSecond - 600
        val v = assertNotNull(drinktervall(mapOf(
            Drinktervall.DT_DRANK_AT to wann, Drinktervall.DT_GLASS_ML to 250,
            Drinktervall.DT_COFFEE_AT to wann - 30, Drinktervall.DT_COFFEE_KIND to 0L,
        )))
        assertTrue(v.gescheitert)
        val rest = assertNotNull(v.rest)
        // Der Kaffee steht schon in der Akte und kaeme sonst bei jedem Start wieder.
        assertEquals(setOf(Drinktervall.DT_DRANK_AT, Drinktervall.DT_GLASS_ML), rest.zahlen.keys)
        assertEquals(1, akte.saetze<NutritionRecord>().size)
    }
}
