package coredevices.pebble.kiesel

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import androidx.health.connect.client.units.Velocity
import io.rebble.libpebblecommon.connection.HealthDataApi
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.database.entity.HealthGender
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.mp.KoinPlatform
import java.time.Duration
import java.time.Instant

/**
 * Was ein Training neben der Sitzung in die Akte bringt: aktive Kalorien,
 * Distanz, Hoehenmeter und Tempo.
 *
 * EIGENE SAETZE, NICHT FELDER DER SITZUNG: die Sitzung der Akte kennt weder
 * Kalorien noch Strecke. Tagessummen und andere Apps lesen sie nur aus diesen
 * Satzarten.
 *
 * JEDER SATZ EINZELN: fehlt das Recht fuer einen, kommen die anderen trotzdem
 * an. Feste Kennungen je Training ersetzen beim zweiten Durchlauf, statt zu
 * verdoppeln.
 */
internal object Trainingswerte {
    private const val TAG = "Kieselsport"

    // Die Akte behaelt bei gleicher Kennung die hoehere Fassung: die Kalorien
    // aus dem Puls schlagen die der Uhr, egal was zuerst ankommt. Die Uhr
    // zaehlt nur Schritte und kennt auf dem Rad fast nichts.
    private const val FASSUNG_UHR = 1L
    private const val FASSUNG_PULS = 2L

    /** Hoechstens eine Tempoprobe so oft - jede Sekunde waeren tausende je Stunde. */
    const val TEMPO_TAKT_S = 10L
    private const val TEMPO_JE_EINTRAG = 900

    /**
     * Die Tempoproben fuer die Akte: Zeitpunkt (Epochensekunde) und m/s.
     *
     * DAS GEGLAETTETE TEMPO DER TRAININGSANSICHT, damit die Akte dasselbe
     * zeigt. Nur innerhalb der Sitzung - die Akte weist sonst ab.
     */
    fun tempoProben(punkte: List<Spur.Punkt>, von: Long, bis: Long): List<Pair<Long, Double>> {
        if (punkte.size < 2) return emptyList()
        val start = punkte.first().zeit
        val aus = mutableListOf<Pair<Long, Double>>()
        // Null statt Long.MIN_VALUE: t - Long.MIN_VALUE laeuft ueber, wird
        // negativ und verwarf so jede Probe.
        var letzte: Long? = null
        for (p in Trainingsanalyse.strecke(punkte)) {
            val t = start + p.sekunde
            if (t < von || t >= bis || (letzte != null && t - letzte < TEMPO_TAKT_S)) continue
            aus += t to p.tempo
            letzte = t
        }
        return aus
    }

    /** Distanz, Hoehenmeter und Tempo eines Trainings. */
    suspend fun schreibeStrecke(context: Context, beginn: Long, anfang: Instant, ende: Instant, meter: Double, punkte: List<Spur.Punkt>) {
        val klient = Akte(context).bereit() ?: return
        if (meter > 0) {
            schreibe(klient, DistanceRecord(
                startTime = anfang, startZoneOffset = versatz(anfang), endTime = ende, endZoneOffset = versatz(ende),
                distance = Length.meters(meter),
                metadata = Kieselsport.vonDerUhr("kieselsport-distanz-$beginn"),
            ))
        }
        val auf = if (punkte.size >= 2) Spur.hoehenmeter(punkte) else 0.0
        if (auf > 0) {
            schreibe(klient, ElevationGainedRecord(
                startTime = anfang, startZoneOffset = versatz(anfang), endTime = ende, endZoneOffset = versatz(ende),
                elevation = Length.meters(auf),
                metadata = Kieselsport.vonDerUhr("kieselsport-hoehe-$beginn"),
            ))
        }
        tempoProben(punkte, anfang.epochSecond, ende.epochSecond).chunked(TEMPO_JE_EINTRAG).forEach { stueck ->
            val von = Instant.ofEpochSecond(stueck.first().first)
            val bis = Instant.ofEpochSecond(stueck.last().first + 1)
            schreibe(klient, SpeedRecord(
                startTime = von, startZoneOffset = versatz(von), endTime = bis, endZoneOffset = versatz(bis),
                samples = stueck.map { (t, v) -> SpeedRecord.Sample(Instant.ofEpochSecond(t), Velocity.metersPerSecond(v)) },
                // Nach der Sekunde des ersten Werts: so ersetzt ein zweiter Durchlauf jedes Stueck einzeln.
                metadata = Kieselsport.vonDerUhr("kieselsport-tempo-$beginn-${stueck.first().first - beginn}"),
            ))
        }
    }

    /** Die aktiven Kalorien, die die Uhr gezaehlt hat - nur bis der Puls besseres liefert. */
    suspend fun schreibeKalorienDerUhr(context: Context, beginn: Long, anfang: Instant, ende: Instant, kcal: Long) {
        if (kcal <= 0) return
        val klient = Akte(context).bereit() ?: return
        schreibe(klient, kalorien(beginn, anfang, ende, kcal.toDouble(), FASSUNG_UHR))
    }

    /**
     * Die aktiven Kalorien aus der Pulskurve - dieselbe Rechnung wie in der
     * Trainingsansicht, ohne den Ruheumsatz. Ohne Gewicht oder Alter in den
     * Einstellungen der Uhr bleibt es bei den Kalorien der Uhr.
     */
    suspend fun schreibeKalorienAusPuls(context: Context, beginn: Long, puls: List<Pulspunkt>) {
        if (puls.isEmpty()) return
        val klient = Akte(context).bereit() ?: return
        val anfang = Instant.ofEpochSecond(beginn)
        val kcal = try {
            val koin = KoinPlatform.getKoin()
            val koerper = withTimeoutOrNull(5_000) { koin.get<LibPebble>().healthSettings.first() } ?: return
            // Die Naechte vor dem Training, wie in der Trainingsansicht.
            val naechte = koin.get<HealthDataApi>().getNaechte(anfang.minus(Duration.ofDays(14)).epochSecond, beginn)
            val ruhepuls = Trainingsanalyse.ruhepulsAusNaechten(naechte.sortedBy { it.beginnUtc }.map { it.ruhepuls })
            val gemessen = KieselEinstellungen.stand(context).maxpuls.takeIf { it != KieselEinstellungen.MAXPULS_VORGABE }
            Trainingsanalyse.aktiveKalorien(
                puls, koerper.weightDag / 100.0, koerper.ageYears, geschlecht(koerper.gender), ruhepuls, gemessen,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Kalorien aus dem Puls: " + e.message)
            null
        } ?: return
        if (kcal <= 0) return
        val ende = anfang.plusSeconds(puls.last().sekunde + 10)
        schreibe(klient, kalorien(beginn, anfang, ende, kcal, FASSUNG_PULS))
    }

    fun geschlecht(g: HealthGender): Trainingsanalyse.Geschlecht = when (g) {
        HealthGender.Male -> Trainingsanalyse.Geschlecht.Mann
        HealthGender.Female -> Trainingsanalyse.Geschlecht.Frau
        HealthGender.Other -> Trainingsanalyse.Geschlecht.Andere
    }

    private fun kalorien(beginn: Long, anfang: Instant, ende: Instant, kcal: Double, fassung: Long) = ActiveCaloriesBurnedRecord(
        startTime = anfang, startZoneOffset = versatz(anfang), endTime = ende, endZoneOffset = versatz(ende),
        energy = Energy.kilocalories(kcal),
        metadata = Metadata.autoRecorded(Device(type = Device.TYPE_WATCH), "kieselsport-kcal-$beginn", fassung),
    )

    private suspend fun schreibe(klient: HealthConnectClient, satz: Record) {
        try {
            klient.insertRecords(listOf(satz))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Meist fehlt das Recht fuer diese eine Satzart.
            Log.w(TAG, satz::class.simpleName + " nicht eingetragen: " + e.message)
        }
    }
}
