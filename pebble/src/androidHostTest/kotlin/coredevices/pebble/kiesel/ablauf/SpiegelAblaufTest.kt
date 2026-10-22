package coredevices.pebble.kiesel.ablauf

import android.content.Context
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import boulder.austausch.Lieferung
import boulder.austausch.Quittung
import boulder.austausch.austauschJson
import coredevices.pebble.R
import coredevices.pebble.kiesel.Akte
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
import java.time.ZoneOffset
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

    /** Laeuft in jedem POST, bevor der Server antwortet: was waehrenddessen auf dem Telefon geschieht. */
    @Volatile
    private var waehrendDesSendens: () -> Unit = {}
    private val post = Spiegelpost(
        HttpClient(MockEngine { r ->
            val l = austauschJson.decodeFromString(Lieferung.serializer(), (r.body as TextContent).text)
            waehrendDesSendens()
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
        override suspend fun getLetzteMinuteBis(bis: Long): Long? = minuten.filter { it.timestamp <= bis }.maxOfOrNull { it.timestamp }
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

    /** Ein Lauf in der Akte, unter [paket] - Boulder selbst oder eine fremde App. */
    private fun lauf(akte: FakeAkte, beginn: Long, minuten: Long, paket: String = context.packageName): String {
        akte.legeAb(
            ExerciseSessionRecord(
                startTime = Instant.ofEpochSecond(beginn), startZoneOffset = null,
                endTime = Instant.ofEpochSecond(beginn + minuten * 60), endZoneOffset = null,
                metadata = Metadata.autoRecorded(Device(type = Device.TYPE_WATCH), "lauf-$beginn-$paket"),
                exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_RUNNING, title = null,
            ),
            paket,
        )
        return akte.alle().last().satz.metadata.id
    }

    /** Boulders Puls ab [beginn], ein Wert je zehn Sekunden. */
    private fun puls(akte: FakeAkte, beginn: Long, vararg bpm: Long) = akte.legeAb(
        HeartRateRecord(
            Instant.ofEpochSecond(beginn), ZoneOffset.UTC, Instant.ofEpochSecond(beginn + bpm.size * 10L), ZoneOffset.UTC,
            bpm.mapIndexed { i, b -> HeartRateRecord.Sample(Instant.ofEpochSecond(beginn + i * 10L), b) },
            Metadata.autoRecorded(Device(type = Device.TYPE_WATCH), "puls-$beginn"),
        ),
        context.packageName,
    )

    private fun mitAkte(test: (FakeAkte) -> Unit) {
        val akte = FakeAkte(context.packageName)
        Akte.ersatz = akte
        try {
            Spiegel.speichere(context, ziel)
            test(akte)
        } finally {
            Akte.ersatz = null
        }
    }

    @Test
    fun einTrainingGehtNochEinmalWennSeinPulsNachkommt() = mitAkte { akte ->
        val beginn = jetzt - 86_400L
        val id = lauf(akte, beginn, 30)
        sende()
        val erstes = lieferungen.last().trainings.single()
        assertEquals(id, erstes.kennung)
        assertEquals(null, erstes.pulsMittel)
        sende()
        assertEquals(emptyList(), lieferungen.last().trainings, "unveraendert geht es nicht noch einmal")
        // Kieselsport traegt die Kurve nach. Der Schnitt wird abgeschnitten wie im Detail: 125.5 -> 125.
        puls(akte, beginn + 60, 120, 131)
        sende()
        val zweites = lieferungen.last().trainings.single()
        assertEquals(125 to 131, zweites.pulsMittel to zweites.pulsMax)
    }

    @Test
    fun geloeschteTrainingeVerschwindenAuchAufDemServer() = mitAkte { akte ->
        val beginn = jetzt - 86_400L
        val eigenes = lauf(akte, beginn, 30)
        val fremd = lauf(akte, beginn - 7200, 40, "com.example.laufapp")
        val bleibt = lauf(akte, beginn - 14_400, 50, "com.example.laufapp")
        sende()
        assertEquals(setOf(eigenes, fremd, bleibt), lieferungen.last().trainings.map { it.kennung }.toSet())
        // Im Hintergrund ohne Leserecht sieht der Lauf nur Boulders eigene: nur deren Fehlen zaehlt.
        akte.nurEigeneLesbar = true
        akte.loesche(eigenes)
        akte.loesche(fremd)
        sende()
        assertEquals(listOf(eigenes), lieferungen.last().trainingsEntfernt)
        akte.nurEigeneLesbar = false
        sende()
        assertEquals(listOf(fremd), lieferungen.last().trainingsEntfernt)
        sende()
        assertEquals(emptyList(), lieferungen.last().trainingsEntfernt)
    }

    @Test
    fun einDoppelErsetztDasGesendeteTraining() = mitAkte { akte ->
        val beginn = jetzt - 86_400L
        val samsung = lauf(akte, beginn, 30, "com.sec.android.app.shealth")
        sende()
        // Strava schreibt denselben Lauf zwei Minuten laenger: die App behaelt nur ihn.
        val strava = lauf(akte, beginn, 32, "com.strava")
        sende()
        assertEquals(listOf(strava), lieferungen.last().trainings.map { it.kennung })
        assertEquals(listOf(samsung), lieferungen.last().trainingsEntfernt)
    }

    @Test
    fun gleichLangeDoppelWieInDerListe() = mitAkte { akte ->
        // Zwei Apps, gleich lang, zehn Minuten versetzt: die Liste behaelt das spaetere (neueste zuerst).
        val beginn = jetzt - 86_400L
        lauf(akte, beginn, 30, "com.example.eins")
        val spaeter = lauf(akte, beginn + 600, 30, "com.example.zwei")
        sende()
        assertEquals(listOf(spaeter), lieferungen.last().trainings.map { it.kennung })
    }

    @Test
    fun aeltereTrainingsPrueftDerGruendlicheLauf() = mitAkte { akte ->
        val alt = lauf(akte, jetzt - 20 * 86_400L, 45)
        sende()
        assertEquals(listOf(alt), lieferungen.last().trainings.map { it.kennung })
        akte.loesche(alt)
        // Ein gewoehnlicher Lauf liest nur die letzten Tage.
        sende(uhr = jetzt + 3600)
        assertEquals(emptyList(), lieferungen.last().trainingsEntfernt)
        // Einen Tag spaeter vergleicht einer alles.
        sende(uhr = jetzt + Spiegelrechnung.GRUENDLICH_S + 1)
        assertEquals(listOf(alt), lieferungen.last().trainingsEntfernt)
    }

    @Test
    fun allesSendetTrainingsNeuUndEntferntDochNoch() = mitAkte { akte ->
        val beginn = jetzt - 86_400L
        val a = lauf(akte, beginn, 30)
        val b = lauf(akte, beginn - 7200, 30)
        sende()
        akte.loesche(b)
        sende(alles = true)
        assertEquals(listOf(a), lieferungen.last().trainings.map { it.kennung })
        assertEquals(listOf(b), lieferungen.last().trainingsEntfernt)
    }

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
        // Die alte vergleicht erst der gruendliche Lauf einen Tag spaeter.
        sende(uhr = jetzt + Spiegelrechnung.GRUENDLICH_S + 1)
        assertEquals(listOf(alt), lieferungen.last().naechte.map { it.beginn })
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

    @Test
    fun tageGehenAuchOhneGanzeAkte() {
        // Eine Akte, deren Abfragen je Tag scheitern - wie ohne das freiwillige Recht "Ernaehrung lesen".
        Akte.ersatz = FakeAkte(context.packageName)
        try {
            Spiegel.speichere(context, ziel)
            sende()
            val tage = lieferungen.single().tage
            assertTrue(tage.isNotEmpty() && tage.all { "schlaf" in it.werte && it.entfernt.isEmpty() }, tage.toString())
        } finally {
            Akte.ersatz = null
        }
    }

    @Test
    fun einNeuerServerWaehrendDesSendensBekommtAlles() {
        Spiegel.speichere(context, ziel)
        Spiegel.merke(context, Spiegel.Art.MINUTEN, true)
        minuten += (1..10).map { minute((jetzt - 3600) / 60 * 60 + it * 60L) }
        val anderer = Spiegel.Ziel("https://anderer.example.ch", "n".repeat(40))
        // Waehrend der alte Server die erste Lieferung annimmt, speichert der Nutzer einen neuen.
        waehrendDesSendens = {
            waehrendDesSendens = {}
            Spiegel.speichere(context, anderer)
        }
        assertEquals(Spiegel.Ausgang.Aus, sende())
        val bisher = lieferungen.size
        assertIs<Spiegel.Ausgang.Fertig>(sende())
        val neu = lieferungen.drop(bisher)
        assertEquals(2, neu.first().naechte.size, "der neue Server bekommt jede Nacht")
        assertEquals(10, neu.sumOf { it.minuten.size }, "und jede Minute")
    }

    @Test
    fun einSchubWaehrendDesSendensBleibtNichtLiegen() {
        Spiegel.speichere(context, ziel)
        // Ein Schub vor dem Senden: das Senden liest ihn mit.
        Spiegel.stosseAn(context)
        Thread.sleep(2)
        sende()
        assertTrue(!Spiegel.nachgestossen(context))
        // Ein Schub, waehrend das Senden schon gelesen hat und auf den Server wartet.
        waehrendDesSendens = {
            Thread.sleep(2)
            Spiegel.stosseAn(context)
        }
        sende()
        assertTrue(Spiegel.nachgestossen(context), "ein Anstoss muss folgen")
    }

    @Test
    fun spaetGelieferteMinutenGehenNichtVerloren() {
        Spiegel.speichere(context, ziel)
        Spiegel.merke(context, Spiegel.Art.MINUTEN, true)
        // Die Minuten auf dem Telefon enden vor 40 Minuten; die Uhr hat den Rest noch nicht geschickt.
        val ende = (jetzt - 40 * 60) / 60 * 60
        minuten += (0 until 30).map { minute(ende - it * 60L) }
        // Dazu eine Minute mit falscher Uhrzeit aus der Zukunft.
        minuten += minute(jetzt + 400 * 86_400L)
        sende()
        // Zehn Minuten spaeter kommt der Rest: 39 Minuten, alle aelter als der Rueckblick ab jetzt.
        val spaet = (1 until 40).map { minute(ende + it * 60L) }
        minuten += spaet
        sende(uhr = jetzt + 600)
        val gesendet = lieferungen.flatMap { it.minuten }.map { it.zeit }.toSet()
        assertEquals(emptyList(), spaet.map { it.timestamp }.filter { it !in gesendet })
    }
}
