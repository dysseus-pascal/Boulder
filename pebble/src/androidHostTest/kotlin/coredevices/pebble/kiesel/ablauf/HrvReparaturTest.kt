package coredevices.pebble.kiesel.ablauf

import android.content.Context
import android.os.RemoteException
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import coredevices.pebble.health.nachtHrvKennung
import coredevices.pebble.health.schreibeNaechte
import coredevices.pebble.kiesel.HrvReparatur
import io.rebble.libpebblecommon.database.boulder.BoulderDatenbank
import io.rebble.libpebblecommon.database.dao.NachtDao
import io.rebble.libpebblecommon.database.entity.NachtAbschnittEntity
import io.rebble.libpebblecommon.database.entity.NachtEntity
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
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * DIE EINMALIGE HRV-REPARATUR (0.52) an Boulders echter Nachtdatenbank im
 * Speicher und einer Akte im Speicher ([FakeAkte]). Die Saetze in der Akte
 * schreibt der echte Sync ([schreibeNaechte]) - so, wie sie 0.51 auf dem
 * Telefon schrieb.
 *
 * Geprueft: eingetragen wird nur in eine Nacht ohne HRV, nur aus Boulders
 * eigenem Satz mit der Kennung genau dieser Nacht, nur 1..200 ms, nur in den
 * letzten 60 Tagen, nur einmal; ohne Recht, ohne Akte oder bei einem Fehler
 * nichts, und die Marke bleibt offen. In der Akte wird nie etwas geschrieben
 * oder geloescht.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HrvReparaturTest {
    private lateinit var context: Context
    private lateinit var akte: FakeAkte
    private lateinit var db: BoulderDatenbank
    private lateinit var dao: NachtDao

    private val zone = ZoneId.of("Europe/Zurich")
    private val jetzt: Instant = LocalDate.of(2026, 10, 6).atTime(21, 0).atZone(zone).toInstant()

    /** Was die Reparatur ins App-Log schreibt (Kermit, wie der FileLogWriter es bekommt). */
    private class Mitschrift : LogWriter() {
        val zeilen: MutableList<Pair<Severity, String>> = java.util.Collections.synchronizedList(mutableListOf())
        override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
            if (tag == "HrvReparatur") zeilen += severity to message
        }
    }

    private val log = Mitschrift()

    @Before
    fun auf() {
        Logger.addLogWriter(log)
        context = RuntimeEnvironment.getApplication()
        akte = FakeAkte(context.packageName).apply { erteilt = setOf(HrvReparatur.RECHT) }
        db = naechteImSpeicher(context)
        dao = db.nachtDao()
    }

    @After
    fun zu() {
        db.close()
        Logger.setLogWriters(Logger.config.logWriterList - log)
    }

    /** Die Nacht zum Morgen [tag]: ab 22:00 am Vorabend, aufgewacht 06:30. */
    private fun nacht(tag: LocalDate, hrv: Int): NachtEntity {
        val beginn = tag.minusDays(1).atTime(22, 0).atZone(zone).toEpochSecond()
        return NachtEntity(beginn, 7200, einschlafen = 30, aufwachen = 510, schlafMinuten = 450, ruhepuls = 52, hrv = hrv)
    }

    private fun phasen(n: NachtEntity) = listOf(NachtAbschnittEntity(n.beginnUtc, 30, 300, 1), NachtAbschnittEntity(n.beginnUtc, 300, 510, 2))

    /** Was die Uhr schickte und der Sync daraus in die Akte schrieb - mit dieser HRV. */
    private fun empfangenUndGeschrieben(n: NachtEntity, hrvInDerAkte: Int = n.hrv) = runBlocking {
        dao.speichereNachtTeil(n, ersterTeil = true, phasen(n))
        assertTrue(schreibeNaechte(context, akte, listOf(n.copy(hrv = hrvInDerAkte) to phasen(n))))
    }

    /** Wie bis 0.51: dieselbe Nacht kam ohne HRV noch einmal, und das Ersetzen nahm hrv = 0 mit. */
    private fun ohneHrvErsetzt(n: NachtEntity) = runBlocking { dao.speichereNacht(n.copy(hrv = 0)) }

    private fun hrvSatz(zeit: Instant, ms: Double, kennung: String) =
        HeartRateVariabilityRmssdRecord(zeit, ZoneOffset.ofHours(2), ms, Metadata.autoRecorded(Device(type = Device.TYPE_WATCH), kennung))

    private fun aufwachen(n: NachtEntity): Instant = Instant.ofEpochSecond(n.beginnUtc + n.aufwachen * 60L)

    /** Ein Wert, den die Akte im Konstruktor abweist - wie ihn eine fremde Quelle doch liefern koennte. */
    private fun setzeMillis(r: HeartRateVariabilityRmssdRecord, ms: Double) {
        r.javaClass.getDeclaredField("heartRateVariabilityMillis").apply { isAccessible = true }.setDouble(r, ms)
    }

    /** Alles in der Akte, wie es dasteht: Kennung, Herkunft, Satz-Id, Wert. */
    private fun akteJetzt() = akte.alle().map { e ->
        listOf(e.satz.metadata.id, e.satz.metadata.clientRecordId, e.herkunft, e.satz::class.simpleName, (e.satz as? HeartRateVariabilityRmssdRecord)?.heartRateVariabilityMillis)
    }

    private fun hrv(n: NachtEntity): Int? = runBlocking { dao.nacht(n.beginnUtc)?.hrv }

    private fun lauf(akteDa: Boolean = true) = runBlocking {
        HrvReparatur.einmal(context, naechteApi(dao), if (akteDa) akte else null, jetzt)
    }

    private fun hrvLesungen() = akte.lesungen.count { it.recordType == HeartRateVariabilityRmssdRecord::class }

    // Die Woche wie auf dem Telefon am 6.10.2026.
    private val donnerstag = nacht(LocalDate.of(2026, 10, 1), 53)
    private val freitag = nacht(LocalDate.of(2026, 10, 2), 55)
    private val samstag = nacht(LocalDate.of(2026, 10, 3), 61)
    private val sonntag = nacht(LocalDate.of(2026, 10, 4), 0)
    private val montag = nacht(LocalDate.of(2026, 10, 5), 0)

    /** Do und Sa wie auf dem Telefon, dazu Naechte, die die Reparatur nicht anfassen darf. */
    private fun telefonwoche(): List<NachtEntity> {
        // Do und Sa: mit HRV empfangen und in die Akte geschrieben, dann ohne HRV ersetzt (bis 0.51).
        empfangenUndGeschrieben(donnerstag); ohneHrvErsetzt(donnerstag)
        empfangenUndGeschrieben(samstag); ohneHrvErsetzt(samstag)
        // Fr hat ihre HRV - auch wenn in der Akte ein anderer Wert stuende, bleibt sie.
        empfangenUndGeschrieben(freitag, hrvInDerAkte = 70)
        // So: unter b24 nie eine HRV, also auch keine in der Akte.
        empfangenUndGeschrieben(sonntag)
        // Mo: keine eigene HRV; in der Akte nur eine fremde mit derselben Kennung und
        // eine eigene mit einer anderen (etwa eine Einzelmessung von Kieselsport).
        empfangenUndGeschrieben(montag)
        akte.legeAb(hrvSatz(aufwachen(montag), 48.0, nachtHrvKennung(montag.beginnUtc)), "com.andere.app")
        runBlocking { akte.insertRecords(listOf(hrvSatz(aufwachen(montag), 49.0, "kieselsport-hrv-${aufwachen(montag).epochSecond}"))) }
        // Vor 70 Tagen: eigener Satz passt, liegt aber vor den 60 Tagen.
        val alt = nacht(LocalDate.of(2026, 7, 28), 45)
        empfangenUndGeschrieben(alt); ohneHrvErsetzt(alt)
        // Zwei Saetze ausserhalb 1..200 ms, mit der richtigen Kennung.
        val zuHoch = nacht(LocalDate.of(2026, 9, 28), 0)
        val zuTief = nacht(LocalDate.of(2026, 9, 27), 0)
        empfangenUndGeschrieben(zuHoch); empfangenUndGeschrieben(zuTief)
        runBlocking {
            akte.insertRecords(listOf(
                hrvSatz(aufwachen(zuHoch), 150.0, nachtHrvKennung(zuHoch.beginnUtc)).also { setzeMillis(it, 250.0) },
                hrvSatz(aufwachen(zuTief), 1.0, nachtHrvKennung(zuTief.beginnUtc)).also { setzeMillis(it, 0.4) },
            ))
        }
        return listOf(alt, zuHoch, zuTief)
    }

    @Test
    fun die_kennung_ist_die_die_der_sync_bis_051_schrieb() {
        // Die Saetze in der Akte stammen von 0.51: aendert sich die Kennung, findet die Reparatur sie nicht.
        assertEquals("boulder-nacht-hrv-1790798400", nachtHrvKennung(donnerstag.beginnUtc))
    }

    @Test
    fun do_und_sa_kommen_zurueck_sonst_aendert_sich_nichts() {
        val (alt, zuHoch, zuTief) = telefonwoche()
        val vorher = runBlocking { dao.naechte(0, Long.MAX_VALUE) }
        val akteVorher = akteJetzt()
        val schreibversuche = akte.schreibversuche

        val ausgang = lauf()

        // Sechs Naechte ohne HRV in den 60 Tagen (Do, Sa, So, Mo, zu hoch, zu tief), zwei repariert.
        assertEquals(HrvReparatur.Ausgang.Gelaufen(repariert = 2, ohneHrv = 6), ausgang)
        assertEquals(53, hrv(donnerstag))
        assertEquals(61, hrv(samstag))
        // Fr behaelt ihre 55 (nicht die 70 der Akte), So bleibt leer.
        assertEquals(55, hrv(freitag))
        assertEquals(0, hrv(sonntag))
        // Mo: weder die fremde HRV mit Boulders Kennung noch die eigene mit anderer Kennung.
        assertEquals(0, hrv(montag))
        // Vor den 60 Tagen und ausserhalb 1..200 ms bleibt es bei keiner.
        assertEquals(listOf(0, 0, 0), listOf(hrv(alt), hrv(zuHoch), hrv(zuTief)))
        // Nur die HRV von Do und Sa ist anders: alle anderen Felder und Naechte bleiben, keine dazu, keine weg.
        val erwartet = vorher.map {
            when (it.beginnUtc) {
                donnerstag.beginnUtc -> it.copy(hrv = 53)
                samstag.beginnUtc -> it.copy(hrv = 61)
                else -> it
            }
        }
        assertEquals(erwartet, runBlocking { dao.naechte(0, Long.MAX_VALUE) })

        // Im App-Log (ab Info, wie im Release): wie viele Naechte repariert sind.
        assertTrue(log.zeilen.any { (stufe, text) -> stufe >= Severity.Info && "2 of 6 nights" in text }, "${log.zeilen}")

        // DIE AKTE NUR GELESEN: kein Schreiben, nichts geloescht oder geaendert.
        assertEquals(schreibversuche, akte.schreibversuche)
        assertEquals(akteVorher, akteJetzt())
        // Gelesen nur Boulders Eigenes.
        val hrvAnfragen = akte.lesungen.filter { it.recordType == HeartRateVariabilityRmssdRecord::class }
        assertTrue(hrvAnfragen.isNotEmpty())
        assertTrue(hrvAnfragen.all { a -> a.dataOriginFilter.map { it.packageName }.toSet() == setOf(context.packageName) }, "${hrvAnfragen.map { it.dataOriginFilter }}")
    }

    @Test
    fun nur_einmal() {
        telefonwoche()
        assertEquals(HrvReparatur.Ausgang.Gelaufen(2, 6), lauf())
        // Danach kaeme Do noch einmal ohne HRV (wie bis 0.51) - die Reparatur laeuft nicht wieder.
        ohneHrvErsetzt(donnerstag)
        val gelesen = akte.lesungen.size
        assertEquals(HrvReparatur.Ausgang.SchonGelaufen, lauf())
        assertEquals(0, hrv(donnerstag))
        assertEquals(gelesen, akte.lesungen.size, "die Akte wurde wieder gelesen")
    }

    @Test
    fun ohne_recht_nichts_und_die_marke_bleibt_offen() {
        telefonwoche()
        // Nur Schreibrechte - Lesen der HRV fehlt.
        akte.erteilt = setOf(HealthPermission.getWritePermission(HeartRateVariabilityRmssdRecord::class))
        val akteVorher = akteJetzt()
        assertEquals(HrvReparatur.Ausgang.OhneRecht, lauf())
        assertTrue(log.zeilen.any { (stufe, text) -> stufe >= Severity.Warn && "no permission" in text }, "${log.zeilen}")
        assertEquals(listOf(0, 0), listOf(hrv(donnerstag), hrv(samstag)))
        assertEquals(0, hrvLesungen())
        assertEquals(akteVorher, akteJetzt())
        // Der naechste Start, jetzt mit Recht: die Marke war offen.
        akte.erteilt = setOf(HrvReparatur.RECHT)
        assertEquals(HrvReparatur.Ausgang.Gelaufen(2, 6), lauf())
        assertEquals(listOf(53, 61), listOf(hrv(donnerstag), hrv(samstag)))
    }

    @Test
    fun ohne_akte_oder_bei_einem_fehler_nichts_und_die_marke_bleibt_offen() {
        telefonwoche()
        assertEquals(HrvReparatur.Ausgang.OhneAkte, lauf(akteDa = false))
        // Die Akte antwortet nicht.
        akte.rechteWerfen = RemoteException("Health Connect is updating")
        assertEquals(HrvReparatur.Ausgang.Fehler, lauf())
        akte.rechteWerfen = null
        // Die Naechte sind nicht lesbar (Umzug nach boulder.db gescheitert).
        val kaputt = object : io.rebble.libpebblecommon.connection.HealthDataApi by naechteApi(dao) {
            override suspend fun getNaechte(von: Long, bis: Long): List<NachtEntity> = throw IllegalStateException("Umzug gescheitert")
        }
        assertEquals(HrvReparatur.Ausgang.Fehler, runBlocking { HrvReparatur.einmal(context, kaputt, akte, jetzt) })
        assertEquals(2, log.zeilen.count { (stufe, text) -> stufe == Severity.Error && "failed" in text }, "${log.zeilen}")
        assertEquals(listOf(0, 0), listOf(hrv(donnerstag), hrv(samstag)))
        assertEquals(0, hrvLesungen())
        // Beim naechsten Start klappt es.
        assertEquals(HrvReparatur.Ausgang.Gelaufen(2, 6), lauf())
    }

    @Test
    fun werte_nur_eigene_mit_passender_kennung_im_bereich() {
        val paket = context.packageName
        val b = donnerstag.beginnUtc
        fun gelesen(r: Record, herkunft: String): HeartRateVariabilityRmssdRecord {
            akte.legeAb(r, herkunft)
            return akte.alle().last().satz as HeartRateVariabilityRmssdRecord
        }
        val zeit = aufwachen(donnerstag)
        val fremd = gelesen(hrvSatz(zeit, 48.0, nachtHrvKennung(b)), "com.andere.app")
        val eigen = gelesen(hrvSatz(zeit, 53.0, nachtHrvKennung(b)), paket)
        val andereKennung = gelesen(hrvSatz(zeit, 47.0, nachtHrvKennung(b + 60)), paket)
        val zuHoch = gelesen(hrvSatz(zeit, 150.0, nachtHrvKennung(b)), paket).also { setzeMillis(it, 200.4) }
        val grenze = gelesen(hrvSatz(zeit, 200.0, nachtHrvKennung(b)), paket)

        assertEquals(emptyMap(), HrvReparatur.werte(listOf(fremd), paket, listOf(b)))
        assertEquals(mapOf(b to 53), HrvReparatur.werte(listOf(fremd, eigen), paket, listOf(b)))
        assertEquals(emptyMap(), HrvReparatur.werte(listOf(andereKennung), paket, listOf(b)))
        assertEquals(emptyMap(), HrvReparatur.werte(listOf(zuHoch), paket, listOf(b)))
        assertEquals(mapOf(b to 200), HrvReparatur.werte(listOf(grenze), paket, listOf(b)))
        // Eine Nacht, nach der nicht gefragt ist, kommt nicht hinein.
        assertEquals(emptyMap(), HrvReparatur.werte(listOf(eigen), paket, listOf(b + 86_400)))
    }
}
