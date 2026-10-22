package coredevices.pebble.kiesel.ablauf

import CoreAppVersion
import android.content.Context
import boulder.austausch.Lieferung
import boulder.austausch.Quittung
import boulder.austausch.austauschJson
import coredevices.pebble.R
import coredevices.pebble.kiesel.Spiegel
import coredevices.pebble.kiesel.Spiegelpost
import coredevices.pebble.kiesel.Tagesspeicher
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.rebble.libpebblecommon.connection.FakeLibPebble
import io.rebble.libpebblecommon.connection.HealthDataApi
import io.rebble.libpebblecommon.database.entity.Spo2ReadingEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Boulder: was der Spiegel seit 0.57.0 ausser der Akte schickt - die Vorgeschichte (Kiesel-Helper,
 * aeltere Sicherungen) mit den Tagen und die SpO2-Messungen der Uhr; beides geht einem Server, der es
 * noch nicht kennt, spaeter noch einmal.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VorgeschichteSauerstoffAblaufTest {
    private lateinit var context: Context
    private val jetzt = Instant.now().epochSecond
    private val ziel = Spiegel.Ziel("https://boulder.example.ch", "s".repeat(40))
    private val lieferungen: MutableList<Lieferung> = Collections.synchronizedList(mutableListOf())

    /** Ein Server vor 0.57.0: er quittiert, kennt Vorgeschichte und SpO2 aber nicht. */
    @Volatile
    private var alterServer = false

    private val post = Spiegelpost(
        HttpClient(MockEngine { r ->
            val l = austauschJson.decodeFromString(Lieferung.serializer(), (r.body as TextContent).text)
            lieferungen += l
            val q = Quittung(l.tage.size, l.naechte.size, l.trainings.size, l.minuten.size, l.trainingsEntfernt.size)
            respond(
                austauschJson.encodeToString(Quittung.serializer(), if (alterServer) q else q.copy(vorgeschichte = l.vorgeschichte.size, sauerstoff = l.sauerstoff.size)),
                HttpStatusCode.OK,
            )
        }) { expectSuccess = false },
    )

    private val spo2 = Collections.synchronizedList(mutableListOf<Spo2ReadingEntity>())
    private val api = object : HealthDataApi by FakeLibPebble() {
        override suspend fun getSpo2ReadingsAfter(afterTimestamp: Long) = spo2.filter { it.timestamp > afterTimestamp }.sortedBy { it.timestamp }
    }

    @Before
    fun auf() {
        context = RuntimeEnvironment.getApplication()
        Spiegel.speichere(context, ziel)
        for (art in listOf(Spiegel.Art.NAECHTE, Spiegel.Art.TRAININGS, Spiegel.Art.AKTE)) Spiegel.merke(context, art, false)
    }

    @After
    fun ab() {
        Spiegel.entferne(context)
    }

    // Von Hand: so rechnet jeder Lauf die Tage, nicht nur einmal je Stunde.
    private fun sende() = runBlocking { Spiegel.sende(context, api, FakeLibPebble(), CoreAppVersion("0.57.0"), post = post, gruendlich = true) }

    private fun vortage(ab: Int = 0) = lieferungen.drop(ab).flatMap { it.vorgeschichte }.map { it.datum }

    private fun messzeiten(ab: Int = 0) = lieferungen.drop(ab).flatMap { it.sauerstoff }.map { it.zeit }

    @Test
    fun vorgeschichteGehtEinmalUndNachEinerSicherungWieder() {
        Tagesspeicher.ergaenze(context, LocalDate.parse("2024-05-01"), mapOf("schritte" to 9000.0, "rem" to 80.0))
        Tagesspeicher.ergaenze(context, LocalDate.parse("2024-05-02"), mapOf("schritte" to 7000.0))
        assertIs<Spiegel.Ausgang.Fertig>(sende())
        assertEquals(listOf("2024-05-01", "2024-05-02"), vortage())
        assertEquals(mapOf("schritte" to 9000.0, "rem" to 80.0), lieferungen.flatMap { it.vorgeschichte }.first().werte)
        val zweiter = lieferungen.size
        sende()
        assertEquals(emptyList(), vortage(zweiter))
        // Eine zurueckgeholte Sicherung bringt einen Tag dazu: nur der geht.
        Tagesspeicher.ergaenze(context, LocalDate.parse("2024-04-30"), mapOf("schritte" to 5000.0))
        val dritter = lieferungen.size
        sende()
        assertEquals(listOf("2024-04-30"), vortage(dritter))
    }

    @Test
    fun einAlterServerBekommtDieVorgeschichteSpaeterNochEinmal() {
        Tagesspeicher.ergaenze(context, LocalDate.parse("2024-05-01"), mapOf("schritte" to 9000.0))
        alterServer = true
        sende()
        assertEquals(listOf("2024-05-01"), vortage())
        alterServer = false
        val vorher = lieferungen.size
        sende()
        assertEquals(listOf("2024-05-01"), vortage(vorher))
    }

    @Test
    fun sauerstoffFortlaufendMitRueckblick() {
        spo2 += Spo2ReadingEntity(jetzt - 3600, 96, 5)
        spo2 += Spo2ReadingEntity(jetzt - 1200, 95, 4)
        assertEquals(2, assertIs<Spiegel.Ausgang.Fertig>(sende()).quittung.sauerstoff)
        assertEquals(listOf(jetzt - 3600, jetzt - 1200), messzeiten())
        // Nichts Neues: keine Lieferung mit SpO2.
        val zweiter = lieferungen.size
        sende()
        assertEquals(emptyList(), messzeiten(zweiter))
        // Eine spaet nachgereichte Messung vor der letzten gesendeten und eine neue: beide kommen an.
        spo2 += Spo2ReadingEntity(jetzt - 1500, 94, 3)
        spo2 += Spo2ReadingEntity(jetzt - 60, 97, 6)
        val dritter = lieferungen.size
        sende()
        val neu = messzeiten(dritter)
        assertEquals(true, (jetzt - 1500) in neu && (jetzt - 60) in neu, neu.toString())
        // Eine Messung mit falscher Uhrzeit aus der Zukunft geht nicht und verschiebt den Stand nicht.
        spo2 += Spo2ReadingEntity(jetzt + 86_400, 99, 7)
        spo2 += Spo2ReadingEntity(jetzt - 30, 98, 6)
        val vierter = lieferungen.size
        sende()
        assertEquals(true, (jetzt - 30) in messzeiten(vierter) && (jetzt + 86_400) !in messzeiten(vierter))
    }

    @Test
    fun einAlterServerVerliertKeinenSauerstoff() {
        spo2 += Spo2ReadingEntity(jetzt - 600, 96, 5)
        alterServer = true
        val aus = assertIs<Spiegel.Ausgang.Fehler>(sende())
        assertEquals(context.getString(R.string.ks_sv_fehler_server_alt), aus.text)
        alterServer = false
        val vorher = lieferungen.size
        sende()
        assertEquals(listOf(jetzt - 600), messzeiten(vorher))
    }
}
