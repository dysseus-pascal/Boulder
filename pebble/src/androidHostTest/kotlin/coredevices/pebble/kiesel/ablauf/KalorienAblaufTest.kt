package coredevices.pebble.kiesel.ablauf

import android.content.Context
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SpeedRecord
import coredevices.pebble.kiesel.Akte
import coredevices.pebble.kiesel.KieselNachricht
import coredevices.pebble.kiesel.Kieselsport
import coredevices.pebble.kiesel.KieselsportBegleiter
import coredevices.pebble.kiesel.Spur
import coredevices.pebble.kiesel.Trainingswerte
import io.rebble.libpebblecommon.connection.HealthDataApi
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.database.entity.HealthGender
import io.rebble.libpebblecommon.database.entity.NachtEntity
import io.rebble.libpebblecommon.health.HealthSettings
import io.rebble.libpebblecommon.services.appmessage.AppMessageData
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.lang.reflect.Proxy
import java.time.Instant
import java.time.ZoneId
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * DIE KALORIEN EINES TRAININGS, BEVOR UND NACHDEM DIE NAECHTE DA SIND.
 *
 * Kommt ein Training aufs Telefon, bevor die Uhr die Naechte davor
 * synchronisiert hat, rechnet Boulder mit dem Ersatz-Ruhepuls 60. Die
 * Rechnung mit den Naechten muss darum eine hoehere Fassung tragen: eine
 * gleiche schriebe Boulder nicht neu (Trainingswerte.lohntSich). Hier der
 * ganze Weg: Training mit Kurve hinein, Kalorien in der Akte, dann kommen
 * die Naechte, und der naechste Start von Kieselsport rechnet neu - ein
 * zweiter nicht mehr.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class KalorienAblaufTest {
    private lateinit var context: Context
    private lateinit var akte: FakeAkte
    private val b = Instant.now().epochSecond / 60 * 60 - 7200

    /** Was die Uhr an Naechten gesynct hat - erst keine. */
    @Volatile
    private var naechte: List<NachtEntity> = emptyList()

    /** Wie oft nach den Naechten gefragt wurde. */
    @Volatile
    private var naechteGefragt = 0

    /** Was die Frage nach den Naechten wirft - wie der Zugriff, dessen Umzug nach boulder.db scheiterte. */
    @Volatile
    private var naechteWerfen: Exception? = null

    /** Nur was die Kalorien fragen; alles andere wirft - ein neuer Weg muss erst hierher. */
    private inline fun <reified T> nur(vararg antworten: Pair<String, () -> Any?>): T {
        val m = antworten.toMap()
        return Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, methode, _ ->
            m[methode.name]?.invoke() ?: throw UnsupportedOperationException(methode.name)
        } as T
    }

    /** 75 kg, 40 Jahre, Mann - die Einstellungen der Uhr, nur die Felder, die die Rechnung liest. */
    private fun einstellungen(): HealthSettings {
        val unsafe = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null) as sun.misc.Unsafe
        val e = unsafe.allocateInstance(HealthSettings::class.java) as HealthSettings
        fun setze(feld: String, wert: Any) = HealthSettings::class.java.getDeclaredField(feld).apply { isAccessible = true }.set(e, wert)
        setze("weightDag", 7500.toShort())
        setze("ageYears", 40)
        setze("gender", HealthGender.Male)
        return e
    }

    @Before
    fun auf() {
        context = RuntimeEnvironment.getApplication()
        akte = FakeAkte(context.packageName)
        Akte.ersatz = akte
        val e = einstellungen()
        val lib = nur<LibPebble>("getHealthSettings" to { flowOf(e) })
        val api = nur<HealthDataApi>("getNaechte" to { naechteGefragt++; naechteWerfen?.let { throw it }; naechte })
        startKoin { modules(module { single { lib }; single { api } }) }
    }

    @After
    fun ab() {
        Akte.ersatz = null
        stopKoin()
    }

    private fun verarbeite(n: KieselNachricht, empfangen: Long) = runBlocking { Kieselsport.verarbeite(context, n, empfangen) }

    private fun kalorien() = akte.saetze<ActiveCaloriesBurnedRecord>().single { it.metadata.clientRecordId == "kieselsport-kcal-$b" }

    /** Ein Lauf, 20 min ohne Pause; die Kurve: 120 Werte bei 150 bpm. */
    private fun laufMitKurve() {
        verarbeite(
            KieselNachricht(mapOf(Kieselsport.SP_ART to 0L, Kieselsport.SP_BEGINN to b, Kieselsport.SP_DAUER to 1200L, Kieselsport.SP_ENDE to b + 1200)),
            b + 1210,
        )
        verarbeite(
            KieselNachricht(
                zahlen = mapOf(Kieselsport.SP_BEGINN to b, Kieselsport.SP_KURVE_AB to 0L, Kieselsport.SP_KURVE_ANZAHL to 120L),
                bytes = mapOf(Kieselsport.SP_KURVE to ByteArray(120) { 150.toByte() }),
            ),
            b + 1220,
        )
    }

    /** Sieben Naechte vor dem Lauf mit diesem Ruhepuls. */
    private fun naechteMit(ruhepuls: Int) = (1..7).map { tag -> NachtEntity(b - tag * 86_400L, 3600, 30, 480, 440, ruhepuls, 60) }

    @Test
    fun mit_den_naechten_wird_neu_gerechnet_und_danach_nicht_mehr() {
        laufMitKurve()
        val ersatz = kalorien()
        // Ohne Naechte: Ruhepuls ersetzt - die Fassung sagt es (120 Pulswerte, keine Waage).
        assertEquals(Trainingswerte.fassungAusPuls(120, waage = false, ruheAusNaechten = false), ersatz.metadata.clientRecordVersion)

        // Jetzt synct die Uhr die Woche davor: Ruhepuls 48.
        naechte = naechteMit(48)
        runBlocking { Trainingswerte.holeKalorienNach(context) }
        val echt = kalorien()
        assertEquals(Trainingswerte.fassungAusPuls(120, waage = false, ruheAusNaechten = true), echt.metadata.clientRecordVersion)
        // Mit dem tieferen Ruhepuls liegt mehr Pulsreserve ueber ihm: mehr aktive Kalorien.
        assertNotEquals(ersatz.energy.inKilocalories, echt.energy.inKilocalories)
        assertTrue(echt.energy.inKilocalories > ersatz.energy.inKilocalories, "${echt.energy.inKilocalories} > ${ersatz.energy.inKilocalories}")

        // Der naechste Start schreibt nichts mehr: vollstaendiger wird es nicht. Und er fragt die
        // Naechte gar nicht erst - die Akte haelt schon die Fassung, die mit ihnen herauskaeme.
        val versuche = akte.schreibversuche
        naechteGefragt = 0
        runBlocking { Trainingswerte.holeKalorienNach(context) }
        assertEquals(versuche, akte.schreibversuche)
        assertEquals(0, naechteGefragt)
    }

    @Test
    fun naechte_mit_unplausiblem_ruhepuls_aendern_nichts() {
        laufMitKurve()
        val ersatz = kalorien()
        // Ein Ruhepuls von 25 (Sensor daneben) verwirft die Rechnung und nimmt den Ersatz: dieselben
        // kcal - also auch dieselbe Fassung, und kein neuer Eintrag bei jedem Start.
        naechte = naechteMit(25)
        val versuche = akte.schreibversuche
        naechteGefragt = 0
        runBlocking { Trainingswerte.holeKalorienNach(context) }
        assertEquals(versuche, akte.schreibversuche)
        // Gefragt wird aber: mit plausiblen Naechten waere es besser geworden.
        assertEquals(1, naechteGefragt)
        assertEquals(ersatz.metadata.clientRecordVersion, kalorien().metadata.clientRecordVersion)
        assertEquals(ersatz.energy.inKilocalories, kalorien().energy.inKilocalories, 1e-9)
    }

    private fun kalorienOderNichts() = akte.saetze<ActiveCaloriesBurnedRecord>().singleOrNull { it.metadata.clientRecordId == "kieselsport-kcal-$b" }

    private fun warte(was: String, bedingung: () -> Boolean) = runBlocking {
        withTimeout(10_000) { while (!bedingung()) delay(20) }
        assertTrue(bedingung(), was)
    }

    /** Eine Sitzung des Begleiters, wie wenn die Uhr Kieselsport oeffnet. */
    private fun sitzung(eingang: Channel<AppMessageData>? = null) = runBlocking {
        val uhr = FakeUhr(File(context.filesDir, "kieselsport-wartend"))
        KieselsportBegleiter(context, geraet(uhr)).start(eingang?.consumeAsFlow() ?: emptyFlow())
    }

    @Test
    fun der_naechste_start_von_kieselsport_rechnet_mit_den_naechten_neu() {
        // Training und Kurve kommen von der Uhr, die Naechte davor noch nicht.
        val eingang = Channel<AppMessageData>(Channel.UNLIMITED)
        sitzung(eingang)
        eingang.trySend(
            AppMessageData(
                1u, Kieselsport.UUID,
                mapOf(Kieselsport.SP_ART to 0, Kieselsport.SP_BEGINN to b.toInt(), Kieselsport.SP_DAUER to 1200, Kieselsport.SP_ENDE to (b + 1200).toInt()),
            ),
        )
        eingang.trySend(
            AppMessageData(
                2u, Kieselsport.UUID,
                mapOf(
                    Kieselsport.SP_BEGINN to b.toInt(), Kieselsport.SP_KURVE_AB to 0, Kieselsport.SP_KURVE_ANZAHL to 120,
                    Kieselsport.SP_KURVE to ByteArray(120) { 150.toByte() },
                ),
            ),
        )
        val ohneNaechte = Trainingswerte.fassungAusPuls(120, waage = false, ruheAusNaechten = false)
        warte("kcal mit dem Ersatz") { kalorienOderNichts()?.metadata?.clientRecordVersion == ohneNaechte }
        val ersatz = kalorien().energy.inKilocalories

        // Die Uhr synct die Woche davor, und Kieselsport geht auf der Uhr wieder auf.
        naechte = naechteMit(48)
        sitzung()
        val mitNaechten = Trainingswerte.fassungAusPuls(120, waage = false, ruheAusNaechten = true)
        warte("kcal mit den Naechten") { kalorienOderNichts()?.metadata?.clientRecordVersion == mitNaechten }
        assertTrue(kalorien().energy.inKilocalories > ersatz, "${kalorien().energy.inKilocalories} > $ersatz")
    }

    @Test
    fun alle_saetze_eines_trainings_tragen_den_versatz_vom_empfang() {
        // Ein Lauf kommt in New York an und wird erst in Zuerich eingetragen (die Datei wartete).
        val vorher = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/Zurich"))
            val newYork = ZoneId.of("America/New_York")
            val dort = newYork.rules.getOffset(Instant.ofEpochSecond(b))
            val zeilen = (b..b + 1190 step 10).joinToString("") { t -> "{\"t\":$t,\"lat\":${47.0 + (t - b) * 1e-5},\"lon\":8.0,\"g\":5.0}\n" }
            assertTrue(Spur.schreibeRoh(context, b, zeilen))
            verarbeite(
                KieselNachricht(
                    mapOf(
                        Kieselsport.SP_ART to 0L, Kieselsport.SP_BEGINN to b, Kieselsport.SP_DAUER to 1200L,
                        Kieselsport.SP_ENDE to b + 1200, Kieselsport.SP_KCAL to 150L,
                    ),
                    zone = newYork.id,
                ),
                b + 1210,
            )
            assertEquals(dort, akte.saetze<ExerciseSessionRecord>().single().startZoneOffset)
            assertEquals(dort, akte.saetze<DistanceRecord>().single().startZoneOffset)
            assertTrue(akte.saetze<SpeedRecord>().isNotEmpty())
            assertEquals(setOf(dort), akte.saetze<SpeedRecord>().map { it.startZoneOffset }.toSet())
            // Die Kalorien der Uhr, bis die Kurve da ist.
            assertEquals(Trainingswerte.FASSUNG_UHR, kalorien().metadata.clientRecordVersion)
            assertEquals(dort, kalorien().startZoneOffset)

            // Die Kurve kam ebenfalls in New York an.
            verarbeite(
                KieselNachricht(
                    zahlen = mapOf(Kieselsport.SP_BEGINN to b, Kieselsport.SP_KURVE_AB to 0L, Kieselsport.SP_KURVE_ANZAHL to 120L),
                    bytes = mapOf(Kieselsport.SP_KURVE to ByteArray(120) { 150.toByte() }),
                    zone = newYork.id,
                ),
                b + 1220,
            )
            assertEquals(setOf(dort), akte.saetze<HeartRateRecord>().map { it.startZoneOffset }.toSet())
            // Und die Kalorien aus ihr, die die der Uhr ersetzen.
            assertTrue(kalorien().metadata.clientRecordVersion > Trainingswerte.FASSUNG_UHR)
            assertEquals(dort, kalorien().startZoneOffset)
        } finally {
            TimeZone.setDefault(vorher)
        }
    }

    @Test
    fun scheitert_der_zugriff_auf_die_naechte_kommt_das_training_trotzdem_mit_kalorien_in_die_akte() {
        // Der Zugriff auf die Naechte wartet auf ihren Umzug nach boulder.db und wirft, wenn er scheiterte.
        naechteWerfen = IllegalStateException("Night move failed recently")
        laufMitKurve()
        assertTrue(naechteGefragt > 0, "Naechte nicht gefragt")
        assertEquals(1, akte.saetze<ExerciseSessionRecord>().size)
        assertEquals(1, akte.saetze<HeartRateRecord>().size)
        // Die Kalorien aus dem Puls kommen trotzdem - mit dem Ersatz-Ruhepuls, wie ohne Naechte.
        assertEquals(Trainingswerte.fassungAusPuls(120, waage = false, ruheAusNaechten = false), kalorien().metadata.clientRecordVersion)
    }
}
