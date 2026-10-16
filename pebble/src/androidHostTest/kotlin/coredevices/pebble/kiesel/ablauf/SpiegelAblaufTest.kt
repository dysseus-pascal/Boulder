package coredevices.pebble.kiesel.ablauf

import android.content.Context
import boulder.austausch.Lieferung
import boulder.austausch.Quittung
import boulder.austausch.austauschJson
import coredevices.pebble.R
import coredevices.pebble.kiesel.KieselVerlauf
import coredevices.pebble.kiesel.Spiegel
import coredevices.pebble.kiesel.Spiegelpost
import coredevices.pebble.kiesel.Spiegelrechnung
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.rebble.libpebblecommon.connection.FakeLibPebble
import io.rebble.libpebblecommon.connection.HealthDataApi
import io.rebble.libpebblecommon.database.entity.HealthDataEntity
import io.rebble.libpebblecommon.database.entity.NachtAbschnittEntity
import io.rebble.libpebblecommon.database.entity.NachtEntity
import io.rebble.libpebblecommon.database.entity.NachtHrvEntity
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Boulder: der Spiegel auf dem eigenen Server ([Spiegel]) gegen eine nachgestellte Gegenstelle -
 * was beim ersten Mal geht, was danach nur noch, wenn es neu ist, und was nach einem Fehler.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SpiegelAblaufTest {
    private lateinit var context: Context
    private val jetzt = Instant.now().epochSecond
    private val ziel = Spiegel.Ziel("https://boulder.example.ch", "s".repeat(40))
    private val lieferungen: MutableList<Lieferung> = Collections.synchronizedList(mutableListOf())

    @Volatile
    private var antwort = HttpStatusCode.OK
    private val post = Spiegelpost(
        HttpClient(MockEngine { r ->
            val l = austauschJson.decodeFromString(Lieferung.serializer(), (r.body as TextContent).text)
            lieferungen += l
            if (antwort != HttpStatusCode.OK) respond("", antwort)
            else respond(austauschJson.encodeToString(Quittung.serializer(), Quittung(l.tage.size, l.naechte.size, l.trainings.size, l.minuten.size)), HttpStatusCode.OK)
        }) { expectSuccess = false },
    )

    private val naechte = mutableListOf<NachtEntity>()
    private val abschnitte = mutableListOf<NachtAbschnittEntity>()
    private val hrv = mutableListOf<NachtHrvEntity>()
    private val minuten = mutableListOf<HealthDataEntity>()
    private val api = object : HealthDataApi by FakeLibPebble() {
        override suspend fun getNaechte(von: Long, bis: Long) = naechte.filter { it.beginnUtc >= von && it.beginnUtc < bis }
        override suspend fun getNachtAbschnitte(von: Long, bis: Long) = abschnitte.filter { it.beginnUtc >= von && it.beginnUtc < bis }
        override suspend fun getNachtHrv(beginnUtc: Long) = hrv.filter { it.beginnUtc == beginnUtc }
        override suspend fun getLatestTimestamp(): Long? = minuten.maxOfOrNull { it.timestamp }
        override suspend fun getHealthDataForRange(start: Long, end: Long) = minuten.filter { it.timestamp >= start && it.timestamp < end }
    }

    private val frisch = jetzt - 86_400L
    private val alt = jetzt - 20 * 86_400L

    @Before
    fun auf() {
        context = RuntimeEnvironment.getApplication()
        for (b in listOf(alt, frisch)) {
            naechte += NachtEntity(b, 7200, 95, 560, 431, 52, 48)
            abschnitte += NachtAbschnittEntity(b, 95, 300, 1)
            abschnitte += NachtAbschnittEntity(b, 300, 560, 3)
            hrv += NachtHrvEntity(b, 120, 50, 55)
        }
    }

    private fun sende(alles: Boolean = false, uhr: Long = jetzt) = runBlocking { Spiegel.sende(context, api, FakeLibPebble(), alles, post) { uhr } }

    private fun minute(t: Long) = HealthDataEntity(
        timestamp = t, steps = 10, orientation = 55, intensity = 800, lightIntensity = 0,
        activeMinutes = 0, restingGramCalories = 0, activeGramCalories = 0, distanceCm = 0, heartRate = 90,
    )

    @Test
    fun ohneEinrichtungGehtNichtsHinaus() {
        assertEquals(Spiegel.Ausgang.Aus, sende())
        assertEquals(emptyList(), lieferungen.toList())
    }

    @Test
    fun zuerstAllesDanachNurNeues() {
        Spiegel.speichere(context, ziel)
        minuten += (1..10).map { minute((jetzt - 3600) / 60 * 60 + it * 60L) }
        assertIs<Spiegel.Ausgang.Fertig>(sende())
        val erste = lieferungen.single()
        assertEquals(setOf(alt, frisch), erste.naechte.map { it.beginn }.toSet())
        assertEquals(listOf(95, 300), erste.naechte.first().abschnitte.map { it.von })
        assertEquals(1, erste.naechte.first().hrvFenster.size)
        // Die Tage aus den Naechten: Schlaf, Ruhepuls, HRV.
        assertTrue(erste.tage.isNotEmpty() && erste.tage.all { "schlaf" in it.werte }, erste.tage.toString())
        assertEquals(mapOf("schritte" to 10_000, "schlafMinuten" to 480), erste.stand?.ziele)
        assertNotNull(erste.stand?.maxPuls)
        // Rohminuten nur auf Wunsch, auch wenn welche da sind.
        assertTrue(lieferungen.all { it.minuten.isEmpty() })

        assertIs<Spiegel.Ausgang.Fertig>(sende())
        val zweite = lieferungen.last()
        assertEquals(emptyList(), zweite.naechte)
        assertEquals(emptyList(), zweite.tage)
        assertNotNull(zweite.stand, "auch ohne Neues: der Server sieht, wann Boulder zuletzt kam")

        // Die Uhr reicht zur frischen Nacht ein HRV-Fenster nach; die alte ist abgeschlossen.
        hrv += NachtHrvEntity(frisch, 125, 47, 54)
        hrv += NachtHrvEntity(alt, 125, 47, 54)
        sende()
        assertEquals(listOf(frisch), lieferungen.last().naechte.map { it.beginn })
        assertEquals(2, lieferungen.last().naechte.single().hrvFenster.size)
    }

    @Test
    fun nachEinemFehlerKommtAllesWieder() {
        Spiegel.speichere(context, ziel)
        antwort = HttpStatusCode.BadGateway
        val f = sende()
        assertIs<Spiegel.Ausgang.Fehler>(f)
        assertTrue(f.voruebergehend)
        assertTrue(Spiegel.status(context).fehler)
        sende()
        // Derselbe Fehler steht einmal im Protokoll, nicht bei jedem Versuch.
        assertEquals(1, KieselVerlauf.alle(context).count { it.second.contains(f.text) })

        antwort = HttpStatusCode.OK
        assertIs<Spiegel.Ausgang.Fertig>(sende())
        assertEquals(2, lieferungen.last().naechte.size)
        val wieder = context.getString(R.string.ks_sv_titel) + ": " + context.getString(R.string.ks_sv_wieder)
        assertEquals(wieder, KieselVerlauf.alle(context).first().second)
        assertTrue(!Spiegel.status(context).fehler)
    }

    @Test
    fun einAbgelehntesTokenWiederholtSichNicht() {
        Spiegel.speichere(context, ziel)
        antwort = HttpStatusCode.Unauthorized
        val f = sende()
        assertIs<Spiegel.Ausgang.Fehler>(f)
        assertEquals(context.getString(R.string.ks_sv_fehler_token), f.text)
        assertTrue(!f.voruebergehend)
    }

    @Test
    fun einAndererServerBekommtAllesNeu() {
        Spiegel.speichere(context, ziel)
        sende()
        // Nur ein neues Token fuer denselben Server: er hat schon alles.
        Spiegel.speichere(context, ziel.copy(token = "n".repeat(40)))
        sende()
        assertEquals(emptyList(), lieferungen.last().naechte)
        Spiegel.speichere(context, Spiegel.Ziel("https://anderer.example.ch", "n".repeat(40)))
        sende()
        assertEquals(2, lieferungen.last().naechte.size)
        sende(alles = true)
        assertEquals(2, lieferungen.last().naechte.size)
    }

    @Test
    fun minutenInTagesteilenUndDanachNurDieNeuen() {
        Spiegel.speichere(context, ziel)
        Spiegel.merke(context, Spiegel.Art.MINUTEN, true)
        val beginn = (jetzt - 2 * 86_400L - 43_200L) / 60 * 60
        minuten += (0 until 3600).map { minute(beginn + it * 60L) }
        assertIs<Spiegel.Ausgang.Fertig>(sende())
        val teile = lieferungen.filter { it.minuten.isNotEmpty() }
        assertEquals(3600, teile.sumOf { it.minuten.size })
        assertTrue(teile.all { it.minuten.size <= Spiegelrechnung.MINUTEN_JE_LIEFERUNG && it.tage.isEmpty() && it.naechte.isEmpty() && it.stand == null })

        val bisher = lieferungen.size
        sende()
        assertEquals(bisher + 1, lieferungen.size, "nichts Neues: nur die Hauptlieferung")

        // Zehn Minuten spaeter: die Uhr hat sie inzwischen geschickt.
        val neu = (3600 until 3610).map { minute(beginn + it * 60L) }
        minuten += neu
        sende(uhr = jetzt + 900)
        val zuletzt = lieferungen.last().minuten.map { it.zeit }
        assertTrue(zuletzt.containsAll(neu.map { it.timestamp }))
        assertTrue(zuletzt.size <= 10 + Spiegelrechnung.MINUTEN_RUECKBLICK_S / 60 + 1, "${zuletzt.size}")
    }

    @Test
    fun eineMinuteMitFalscherUhrzeitHaeltNichtsAuf() {
        Spiegel.speichere(context, ziel)
        Spiegel.merke(context, Spiegel.Art.MINUTEN, true)
        val beginn = (jetzt - 3600) / 60 * 60
        minuten += (0 until 10).map { minute(beginn + it * 60L) }
        // Die Uhr lief einmal falsch und schrieb eine Minute ein Jahr in die Zukunft.
        minuten += minute(jetzt + 400 * 86_400L)
        sende()
        assertTrue(lieferungen.flatMap { it.minuten }.none { it.zeit > jetzt })
        val neu = minute(jetzt + 600)
        minuten += neu
        sende(uhr = jetzt + 900)
        assertTrue(neu.timestamp in lieferungen.last().minuten.map { it.zeit })
    }
}
