package coredevices.pebble.kiesel.ablauf

import android.content.Context
import androidx.health.connect.client.records.ExerciseRouteResult
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.Record
import coredevices.pebble.kiesel.Akte
import coredevices.pebble.kiesel.Drinktervall
import coredevices.pebble.kiesel.KieselNachricht
import coredevices.pebble.kiesel.Kieselsport
import coredevices.pebble.kiesel.Spur
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * DER RIEGEL ERST NACH DEM EINTRAG: bricht der Prozess zwischen Vormerken und
 * Akte ab (oder wirft der Bau des Satzes), muss der naechste Start die
 * liegengebliebene Nachricht noch eintragen. Bis 0.49 stand der Riegel schon
 * vorher - der naechste Versuch lieferte null, und die Datei wurde geloescht.
 *
 * Das Ende des Prozesses ist hier ein Error mitten im Schreiben der Akte: ihn
 * faengt kein catch (Exception), und nichts danach laeuft - wie bei einem Kill.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RiegelAblaufTest {
    private lateinit var context: Context
    private lateinit var akte: FakeAkte

    private class ProzessEnde : Error("Prozess beendet")

    private val b = Instant.now().epochSecond / 60 * 60 - 7200
    private val dauer = 1200L

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

    /** Beim ersten Schreiben eines Satzes der Art [art] endet der Prozess. */
    private fun stirbBeimErstenMal(art: KClass<out Record>) {
        var gestorben = false
        akte.waehrendDesSchreibens = { saetze ->
            if (!gestorben && saetze.any { art.isInstance(it) }) {
                gestorben = true
                throw ProzessEnde()
            }
        }
    }

    private fun zusammenfassung(art: Long = 0, hrv: Long? = null) = KieselNachricht(
        zahlen = buildMap {
            put(Kieselsport.SP_ART, art)
            put(Kieselsport.SP_BEGINN, b)
            put(Kieselsport.SP_DAUER, dauer)
            put(Kieselsport.SP_ENDE, b + dauer)
            hrv?.let { put(Kieselsport.SP_HRV, it) }
        },
    )

    private fun verarbeite(n: KieselNachricht) = runBlocking { Kieselsport.verarbeite(context, n, b + dauer + 5) }

    @Test
    fun ein_training_nach_abbruch_beim_eintragen_kommt_beim_naechsten_start_in_die_akte() {
        stirbBeimErstenMal(ExerciseSessionRecord::class)
        assertFailsWith<ProzessEnde> { verarbeite(zusammenfassung()) }
        assertTrue(akte.saetze<ExerciseSessionRecord>().isEmpty())

        // Der naechste Start liest die Datei wieder (Wartend) - sie muss eingetragen werden.
        val meldung = verarbeite(zusammenfassung())
        assertNotNull(meldung, "Zweiter Versuch hielt das Training fuer erledigt")
        assertEquals(b, akte.saetze<ExerciseSessionRecord>().single().startTime.epochSecond)
        // Und danach haelt der Riegel eine wiederholte Zusammenfassung auf.
        assertEquals(null, verarbeite(zusammenfassung()))
    }

    @Test
    fun eine_spur_mit_zwei_punkten_derselben_sekunde_wird_eingetragen() {
        // Laufen mit Spur; zwei Fixe in derselben Sekunde mitten im Lauf.
        val zeilen = (b until b + dauer step 10).joinToString("") { t ->
            val zeile = "{\"t\":$t,\"lat\":${47.0 + (t - b) * 1e-5},\"lon\":8.0,\"g\":5.0}\n"
            if (t == b + 300) zeile + "{\"t\":$t,\"lat\":${47.0 + (t - b) * 1e-5 + 1e-6},\"lon\":8.0,\"g\":5.0}\n" else zeile
        }
        assertTrue(Spur.schreibeRoh(context, b, zeilen))

        val meldung = verarbeite(zusammenfassung(art = 0))
        assertNotNull(meldung)
        val ergebnis = akte.saetze<ExerciseSessionRecord>().single().exerciseRouteResult
        val route = assertNotNull((ergebnis as? ExerciseRouteResult.Data)?.exerciseRoute)
        assertEquals(dauer / 10, route.route.size.toLong())
    }

    @Test
    fun weist_die_akte_die_route_schon_im_bau_ab_bleibt_das_training_liegen() {
        // Ein Punkt jenseits des Pols (eine kaputte Zeile, etwa aus einer alten Sicherung): die Akte wirft im Konstruktor.
        val zeilen = (b until b + dauer step 10).joinToString("") { t ->
            "{\"t\":$t,\"lat\":${if (t == b + 300) 95.0 else 47.0},\"lon\":8.0,\"g\":5.0}\n"
        }
        assertTrue(Spur.schreibeRoh(context, b, zeilen))
        val meldung = verarbeite(zusammenfassung(art = 0))
        // Als gescheitert gemeldet: der Begleiter laesst die Datei liegen, und der Riegel ist offen.
        assertTrue(Kieselsport.gescheitert(context, meldung), "Meldung $meldung")
        assertTrue(akte.saetze<ExerciseSessionRecord>().isEmpty())
    }

    @Test
    fun eine_hrv_ueber_200_ms_nimmt_das_training_nicht_mit() {
        // Yoga mit 250 ms: mehr nimmt die Akte nicht. Der Rest des Trainings bleibt.
        val meldung = verarbeite(zusammenfassung(art = 5, hrv = 250))
        assertNotNull(meldung)
        assertEquals(1, akte.saetze<ExerciseSessionRecord>().size)
        assertTrue(akte.saetze<HeartRateVariabilityRmssdRecord>().isEmpty())
    }

    @Test
    fun eine_hrv_bis_200_ms_wird_eingetragen() {
        verarbeite(zusammenfassung(art = 5, hrv = 200))
        assertEquals(200.0, akte.saetze<HeartRateVariabilityRmssdRecord>().single().heartRateVariabilityMillis, 0.0)
    }

    private val glasZeit = Instant.now().epochSecond - 600

    private fun drinktervall(zahlen: Map<Int, Long>) = runBlocking { Drinktervall.verarbeite(context, KieselNachricht(zahlen)) }

    @Test
    fun ein_glas_nach_abbruch_beim_eintragen_kommt_beim_naechsten_start_in_die_akte() {
        val glas = mapOf(Drinktervall.DT_DRANK_AT to glasZeit, Drinktervall.DT_GLASS_ML to 250L, Drinktervall.DT_GLASSES to 8L)
        stirbBeimErstenMal(HydrationRecord::class)
        assertFailsWith<ProzessEnde> { drinktervall(glas) }

        val v = assertNotNull(drinktervall(glas), "Zweiter Versuch hielt das Glas fuer erledigt")
        assertTrue(!v.gescheitert)
        assertEquals(glasZeit, akte.saetze<HydrationRecord>().single().startTime.epochSecond)
    }

    @Test
    fun ein_kaffee_nach_abbruch_beim_eintragen_kommt_beim_naechsten_start_in_die_akte() {
        val kaffee = mapOf(Drinktervall.DT_COFFEE_AT to glasZeit, Drinktervall.DT_COFFEE_KIND to 0x11L)
        stirbBeimErstenMal(NutritionRecord::class)
        assertFailsWith<ProzessEnde> { drinktervall(kaffee) }

        assertNotNull(drinktervall(kaffee), "Zweiter Versuch hielt den Kaffee fuer erledigt")
        assertEquals(glasZeit, akte.saetze<NutritionRecord>().single().startTime.epochSecond)
    }
}
