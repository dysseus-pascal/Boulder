package coredevices.pebble.kiesel.ablauf

import CoreAppVersion
import android.content.Context
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Mass
import boulder.austausch.Lieferung
import boulder.austausch.Quittung
import boulder.austausch.austauschJson
import coredevices.pebble.R
import coredevices.pebble.kiesel.Akte
import coredevices.pebble.kiesel.Aktenspiegel
import coredevices.pebble.kiesel.Spiegel
import coredevices.pebble.kiesel.Spiegelpost
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.rebble.libpebblecommon.connection.FakeLibPebble
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneOffset
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Boulder: der Spiegel der ganzen Akte ([Aktenspiegel]) - erst alles, dann nur Aenderungen samt
 * Loeschungen; gestueckelt und fortsetzbar; nichts verloren, wenn ein Server die Akte noch nicht kennt,
 * eine Marke verfaellt oder das Recht auf die Vergangenheit spaeter kommt.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AktenspiegelAblaufTest {
    private lateinit var context: Context
    private lateinit var akte: FakeAkte
    private val jetzt = Instant.now().epochSecond
    private val ziel = Spiegel.Ziel("https://boulder.example.ch", "s".repeat(40))
    private val lieferungen: MutableList<Lieferung> = Collections.synchronizedList(mutableListOf())

    @Volatile
    private var antwort = HttpStatusCode.OK

    /** Ein Server vor 0.57.0: er quittiert, kennt die Akte aber nicht. */
    @Volatile
    private var alterServer = false

    /** Laeuft in jedem POST, bevor der Server antwortet. */
    @Volatile
    private var waehrendDesSendens: (Lieferung) -> Unit = {}

    private val post = Spiegelpost(
        HttpClient(MockEngine { r ->
            val l = austauschJson.decodeFromString(Lieferung.serializer(), (r.body as TextContent).text)
            waehrendDesSendens(l)
            if (antwort != HttpStatusCode.OK) return@MockEngine respond("", antwort)
            lieferungen += l
            val q = Quittung(l.tage.size, l.naechte.size, l.trainings.size, l.minuten.size, l.trainingsEntfernt.size)
            respond(
                austauschJson.encodeToString(Quittung.serializer(), if (alterServer) q else q.copy(akte = l.akte.size, akteEntfernt = l.akteEntfernt.size)),
                HttpStatusCode.OK,
            )
        }) { expectSuccess = false },
    )

    private val withings = "com.withings.wiscale2"
    private val fitbit = "com.fitbit.FitbitMobile"
    private val hintergrund = HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND
    private val vergangenheit = HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY

    @Before
    fun auf() {
        context = RuntimeEnvironment.getApplication()
        akte = FakeAkte(context.packageName)
        Akte.ersatz = akte
        Spiegel.speichere(context, ziel)
        // Nur die Akte: Tage, Naechte und Trainings haben ihre eigenen Tests.
        for (art in listOf(Spiegel.Art.TAGE, Spiegel.Art.NAECHTE, Spiegel.Art.TRAININGS)) Spiegel.merke(context, art, false)
        akte.erteilt = Aktenspiegel.LESERECHTE + hintergrund + vergangenheit
    }

    @After
    fun ab() {
        Akte.ersatz = null
        Spiegel.entferne(context)
    }

    private fun sende(alles: Boolean = false, vonHand: Boolean = false) =
        runBlocking { Spiegel.sende(context, FakeLibPebble(), FakeLibPebble(), CoreAppVersion("0.57.0"), alles, post, gruendlich = vonHand) }

    /** Was die Akte-Lieferungen seit [ab] brachten: Kennungen der Saetze und der Loeschungen. */
    private fun gesendet(ab: Int = 0) = lieferungen.drop(ab).flatMap { it.akte }.map { it.id }

    private fun entfernt(ab: Int = 0) = lieferungen.drop(ab).flatMap { it.akteEntfernt }

    private fun gewicht(kg: Double, t: Long = jetzt - 3600, paket: String = withings): String {
        akte.legeAb(WeightRecord(Instant.ofEpochSecond(t), ZoneOffset.UTC, Mass.kilograms(kg), Metadata.manualEntry()), paket)
        return akte.alle().last().satz.metadata.id
    }

    private fun schritte(n: Int, t: Long, paket: String = fitbit): String {
        akte.legeAb(
            StepsRecord(Instant.ofEpochSecond(t), ZoneOffset.UTC, Instant.ofEpochSecond(t + 60), ZoneOffset.UTC, n.toLong(), Metadata.autoRecorded(Device(type = Device.TYPE_PHONE))),
            paket,
        )
        return akte.alle().last().satz.metadata.id
    }

    @Test
    fun ersteLesungDanachNurAenderungenUndLoeschungen() {
        val waage = gewicht(72.4)
        akte.legeAb(
            HeartRateRecord(Instant.ofEpochSecond(jetzt - 600), ZoneOffset.UTC, Instant.ofEpochSecond(jetzt - 540), ZoneOffset.UTC,
                listOf(HeartRateRecord.Sample(Instant.ofEpochSecond(jetzt - 600), 61)), Metadata.autoRecorded(Device(type = Device.TYPE_WATCH))),
            context.packageName,
        )
        val puls = akte.alle().last().satz.metadata.id
        akte.legeAb(
            SleepSessionRecord(Instant.ofEpochSecond(jetzt - 30_000), ZoneOffset.UTC, Instant.ofEpochSecond(jetzt - 3_000), ZoneOffset.UTC,
                Metadata.autoRecorded(Device(type = Device.TYPE_WATCH)), stages = listOf(
                    SleepSessionRecord.Stage(Instant.ofEpochSecond(jetzt - 30_000), Instant.ofEpochSecond(jetzt - 29_000), SleepSessionRecord.STAGE_TYPE_DEEP))),
            fitbit,
        )
        val schlaf = akte.alle().last().satz.metadata.id
        val erstes = assertIs<Spiegel.Ausgang.Fertig>(sende())
        assertEquals(setOf(waage, puls, schlaf), gesendet().toSet())
        assertEquals(3, erstes.quittung.akte)
        val satz = lieferungen.flatMap { it.akte }.single { it.id == schlaf }
        assertEquals(fitbit, satz.quelle)
        assertEquals("deep", satz.abschnitte.single().art)

        // Nichts Neues: keine Akte-Lieferung, nur der Stand.
        val vorher = lieferungen.size
        assertEquals(0, assertIs<Spiegel.Ausgang.Fertig>(sende()).quittung.akte)
        assertEquals(emptyList(), gesendet(vorher))

        // Ein neues Gewicht und eine geloeschte Nacht.
        val neu = gewicht(72.1)
        akte.loesche(schlaf)
        val dritter = lieferungen.size
        val q = assertIs<Spiegel.Ausgang.Fertig>(sende()).quittung
        assertEquals(listOf(neu), gesendet(dritter))
        assertEquals(listOf(schlaf), entfernt(dritter))
        assertEquals(1, q.akte)
        assertEquals(1, q.akteEntfernt)
    }

    @Test
    fun ohneRechtImHintergrundNurVonHand() {
        akte.erteilt = akte.erteilt - hintergrund
        val waage = gewicht(70.0)
        assertIs<Spiegel.Ausgang.Fertig>(sende())
        // Im Hintergrund weist die Akte fremde Saetze ab oder laesst sie still weg: keine Erstlesung dort.
        assertEquals(emptyList(), gesendet())
        assertIs<Spiegel.Ausgang.Fertig>(sende(vonHand = true))
        assertEquals(listOf(waage), gesendet())
    }

    @Test
    fun nurArtenMitLeserecht() {
        akte.erteilt = setOf(HealthPermission.getReadPermission(WeightRecord::class), hintergrund, vergangenheit)
        val waage = gewicht(70.0)
        val gang = schritte(500, jetzt - 7200)
        sende()
        assertEquals(listOf(waage), gesendet())
        // Kommt das Recht spaeter, liest der Spiegel diese Art dann von vorn.
        akte.erteilt = akte.erteilt + HealthPermission.getReadPermission(StepsRecord::class)
        val vorher = lieferungen.size
        sende()
        assertEquals(listOf(gang), gesendet(vorher))
    }

    @Test
    fun gestueckeltUndNachAbbruchFortgesetzt() {
        val alle = (0 until 4_500).map { schritte(10 + it, jetzt - 100_000 + it * 10L) }
        var posts = 0
        waehrendDesSendens = { l -> if (l.akte.isNotEmpty() && ++posts == 2) antwort = HttpStatusCode.ServiceUnavailable }
        // Die zweite Lieferung scheitert: die erste ist quittiert, der Stand steht dahinter.
        assertIs<Spiegel.Ausgang.Fehler>(sende())
        val erste = gesendet()
        assertEquals(Aktenspiegel.HOECHSTENS_SAETZE, erste.size)
        antwort = HttpStatusCode.OK
        waehrendDesSendens = {}
        val vorher = lieferungen.size
        assertIs<Spiegel.Ausgang.Fertig>(sende())
        val zweite = gesendet(vorher)
        // Fortgesetzt statt von vorn, und am Ende fehlt keiner.
        assertTrue(zweite.size < alle.size, "von vorn gelesen: ${zweite.size}")
        assertEquals(alle.toSet(), (erste + zweite).toSet())
        assertTrue(lieferungen.drop(vorher).all { it.akte.size <= Aktenspiegel.HOECHSTENS_SAETZE })
    }

    @Test
    fun eineAenderungKommtNachEinemFehlerNochEinmal() {
        gewicht(70.0)
        sende()
        val neu = gewicht(70.4)
        // Erst die Lieferung mit dem Satz scheitert, nicht die davor: so liest der Lauf die Aenderung schon.
        waehrendDesSendens = { l -> if (l.akte.isNotEmpty()) antwort = HttpStatusCode.ServiceUnavailable }
        assertIs<Spiegel.Ausgang.Fehler>(sende())
        waehrendDesSendens = {}
        antwort = HttpStatusCode.OK
        val vorher = lieferungen.size
        sende()
        // Die neue Marke gilt erst nach der Quittung: sonst ginge die Aenderung mit dem Fehler verloren.
        assertEquals(listOf(neu), gesendet(vorher))
    }

    @Test
    fun einAlterServerVerliertNichts() {
        val waage = gewicht(71.0)
        alterServer = true
        val aus = assertIs<Spiegel.Ausgang.Fehler>(sende())
        assertEquals(context.getString(R.string.ks_sv_fehler_server_alt), aus.text)
        alterServer = false
        val vorher = lieferungen.size
        assertIs<Spiegel.Ausgang.Fertig>(sende())
        // Der alte Server uebergingt den Satz: er kommt noch einmal.
        assertEquals(listOf(waage), gesendet(vorher))
    }

    @Test
    fun eineVerfalleneMarkeLiestAllesNeu() {
        val waage = gewicht(70.5)
        sende()
        akte.verfalleneMarken += WeightRecord::class
        sende()
        val vorher = lieferungen.size
        sende()
        assertEquals(listOf(waage), gesendet(vorher))
    }

    @Test
    fun dasRechtAufDieVergangenheitSpaeterLiestNeu() {
        akte.erteilt = akte.erteilt - vergangenheit
        val waage = gewicht(69.9)
        sende()
        assertEquals(listOf(waage), gesendet())
        // Ohne das Recht las die Akte nur 30 Tage; mit ihm kommt alles noch einmal.
        akte.erteilt = akte.erteilt + vergangenheit
        val vorher = lieferungen.size
        sende()
        assertEquals(listOf(waage), gesendet(vorher))
        val danach = lieferungen.size
        sende()
        assertEquals(emptyList(), gesendet(danach))
    }

    @Test
    fun allesUndEinNeuerServerBekommenDieGanzeAkte() {
        val waage = gewicht(70.2)
        sende()
        val vorher = lieferungen.size
        sende(alles = true)
        assertEquals(listOf(waage), gesendet(vorher))
        Spiegel.speichere(context, Spiegel.Ziel("https://anderer.example.ch", "t".repeat(40)))
        val danach = lieferungen.size
        sende()
        assertEquals(listOf(waage), gesendet(danach))
    }
}
