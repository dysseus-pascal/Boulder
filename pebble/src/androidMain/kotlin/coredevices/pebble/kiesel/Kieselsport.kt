package coredevices.pebble.kiesel

import android.content.Context
import android.util.Log
import androidx.health.connect.client.records.ExerciseLap
import androidx.health.connect.client.records.ExerciseRoute
import androidx.health.connect.client.records.ExerciseSegment
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Length
import coredevices.pebble.R
import kotlinx.serialization.Serializable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.uuid.Uuid

/** Eine Nachricht von Kieselsport, nach Art der Werte getrennt. */
@Serializable
internal data class KieselNachricht(
    val zahlen: Map<Int, Long>,
    val texte: Map<Int, String> = emptyMap(),
    val bytes: Map<Int, ByteArray> = emptyMap(),
)

/**
 * Kieselsport - was die Trainings-App der Uhr dem Telefon sagt.
 *
 * UEBERNOMMEN AUS KIESEL-HELPER (Aufgaben.kt), wo es bisher ueber PebbleKit
 * ankam. Jetzt steckt es in Boulder selbst: Kieselsport ist eine System-App der
 * Boulder-Firmware, und Boulder ist das Telefon, mit dem die Uhr spricht - ein
 * Umweg ueber Rundmeldungen an eine zweite App braucht es nicht mehr.
 *
 * DIE NACHT KOMMT NICHT MEHR HIERHER: die rechnet die Firmware (Tag 88).
 */
internal object Kieselsport {

    private const val TAG = "Kieselsport"

    val UUID: Uuid = Uuid.parse("6c386250-a6d7-4a19-a485-52541b5d7c0d")

    // Die Schluessel der Nachrichten: in der Reihenfolge der messageKeys der
    // Store-App, ab 10000 - so kennt sie auch die Firmware (schluessel.h).
    const val SP_ART = 10000
    const val SP_BEGINN = 10001
    const val SP_DAUER = 10002
    const val SP_METER = 10004
    const val SP_KCAL = 10005
    const val SP_PULS_MITTEL = 10006
    const val SP_PULS_MAX = 10007
    const val SP_MAXPULS = 10008
    const val SP_ZUSTAND = 10009
    const val SP_BECKEN = 10010
    const val SP_PAUSENZIEL = 10011
    const val SP_EMPFIND = 10012
    const val SP_SAETZE = 10013
    const val SP_REPS = 10014
    const val SP_BAHNEN = 10015
    const val SP_ABSCHNITTE = 10016
    const val SP_HRV = 10017
    const val SP_PIN_ART = 10018
    const val SP_PIN_ZEIT = 10019
    const val SP_KURVE_AB = 10020
    const val SP_KURVE_ANZAHL = 10021
    const val SP_KURVE = 10022

    private const val KURVE_TAKT_S = 10

    /**
     * Die Sportarten in der Reihenfolge, in der Kieselsport sie zaehlt.
     *
     * "MTB" STEHT IN JEDER SPRACHE IM TITEL: die Akte kennt nur ein Radfahren,
     * und eine Trainingsansicht erkennt das Mountainbike nur am Titel.
     */
    fun artAlsSatzart(context: Context, art: Long): Pair<Int, String> = when (art.toInt()) {
        0 -> ExerciseSessionRecord.EXERCISE_TYPE_RUNNING to context.getString(R.string.ks_a_titel_laufen)
        1 -> ExerciseSessionRecord.EXERCISE_TYPE_BIKING to context.getString(R.string.ks_a_titel_strasse)
        2 -> ExerciseSessionRecord.EXERCISE_TYPE_HIKING to context.getString(R.string.ks_a_titel_wandern)
        3 -> ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING to context.getString(R.string.ks_a_titel_kraft)
        4 -> ExerciseSessionRecord.EXERCISE_TYPE_BIKING to context.getString(R.string.ks_a_titel_mtb)
        5 -> ExerciseSessionRecord.EXERCISE_TYPE_YOGA to context.getString(R.string.ks_a_titel_yoga)
        6 -> ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL to context.getString(R.string.ks_a_titel_schwimmen)
        else -> ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT to context.getString(R.string.ks_training)
    }

    /** Ein Abschnitt eines Trainings: ein Satz beim Kraft, eine Bahn beim Schwimmen. */
    data class Abschnitt(val ab: Long, val anzahl: Int, val dauer: Long)

    /**
     * Die Abschnittsliste der Uhr zerlegen: "beginn:anzahl:dauer;...".
     *
     * STRENG: was nicht aus drei Zahlen besteht, faellt weg. Eine halb
     * angekommene Zeile waere sonst ein erfundener Satz.
     */
    fun abschnitteAus(text: String?): List<Abschnitt> {
        if (text.isNullOrBlank()) return emptyList()
        return text.split(";").mapNotNull { stueck ->
            if (stueck.isBlank()) return@mapNotNull null
            val teile = stueck.split(":")
            if (teile.size != 3) return@mapNotNull null
            val ab = teile[0].toLongOrNull() ?: return@mapNotNull null
            val anzahl = teile[1].toIntOrNull() ?: return@mapNotNull null
            val dauer = teile[2].toLongOrNull() ?: return@mapNotNull null
            if (ab < 0 || anzahl < 0 || dauer < 0) return@mapNotNull null
            Abschnitt(ab, anzahl, dauer)
        }
    }

    /**
     * Aus den Abschnitten Saetze fuer die Akte machen - samt Pausen.
     *
     * ALLES ODER NICHTS: die Akte weist einen Satz ausserhalb der Sitzung oder
     * eine Ueberschneidung ab, und zwar den GANZEN Eintrag. Im Zweifel leer.
     */
    fun alsSegmente(abschnitte: List<Abschnitt>, anfang: Instant, ende: Instant): List<ExerciseSegment> {
        val aus = mutableListOf<ExerciseSegment>()
        var vorheriges: Instant? = null
        abschnitte.forEach { a ->
            val von = anfang.plusSeconds(a.ab)
            val bis = von.plusSeconds(a.dauer)
            if (von.isBefore(anfang) || bis.isAfter(ende) || !bis.isAfter(von)) return emptyList()
            vorheriges?.let { letztes ->
                if (von.isBefore(letztes)) return emptyList()
                if (von.isAfter(letztes)) {
                    aus += ExerciseSegment(
                        startTime = letztes,
                        endTime = von,
                        segmentType = ExerciseSegment.EXERCISE_SEGMENT_TYPE_REST,
                    )
                }
            }
            // WELCHE UEBUNG ES WAR, WEISS DIE UHR NICHT - sie sieht eine
            // Bewegung, keine Hantelbank.
            aus += ExerciseSegment(
                startTime = von,
                endTime = bis,
                segmentType = ExerciseSegment.EXERCISE_SEGMENT_TYPE_OTHER_WORKOUT,
                repetitions = a.anzahl,
            )
            vorheriges = bis
        }
        return aus
    }

    /** Aus den Abschnitten Bahnen machen - jede mit ihrer Laenge. */
    fun alsBahnen(abschnitte: List<Abschnitt>, anfang: Instant, ende: Instant, beckenMeter: Double): List<ExerciseLap> {
        val aus = mutableListOf<ExerciseLap>()
        var vorheriges: Instant? = null
        abschnitte.forEach { a ->
            val von = anfang.plusSeconds(a.ab)
            val bis = von.plusSeconds(a.dauer)
            if (von.isBefore(anfang) || bis.isAfter(ende) || !bis.isAfter(von)) return emptyList()
            vorheriges?.let { if (von.isBefore(it)) return emptyList() }
            aus += ExerciseLap(
                startTime = von,
                endTime = bis,
                length = if (beckenMeter > 0) Length.meters(beckenMeter) else null,
            )
            vorheriges = bis
        }
        return aus
    }

    /** Der Schnitt der Luecken zwischen den Saetzen, in Sekunden. */
    fun pausenSchnitt(abschnitte: List<Abschnitt>): Long {
        if (abschnitte.size < 2) return 0
        var summe = 0L
        var zahl = 0
        for (i in 1 until abschnitte.size) {
            val ende = abschnitte[i - 1].ab + abschnitte[i - 1].dauer
            val luecke = abschnitte[i].ab - ende
            if (luecke in 1..1800) {
                summe += luecke
                zahl++
            }
        }
        return if (zahl > 0) summe / zahl else 0
    }

    /** "12/10/8/8" liest jeder - in der Akte stehen die Saetze einzeln, aber kein Schirm zeigt sie so. */
    private fun abschnittsnotiz(context: Context, felder: Map<Int, Long>, abschnitte: List<Abschnitt>): String? {
        val saetze = felder[SP_SAETZE] ?: 0
        val bahnen = felder[SP_BAHNEN] ?: 0
        return when {
            bahnen > 0 -> {
                val meter = felder[SP_METER] ?: 0
                val je = meter / bahnen
                context.getString(R.string.ks_a_bahnen, bahnen.toInt()) +
                    (if (je > 0) " " + context.getString(R.string.ks_a_a_m, je.toInt()) else "")
            }
            saetze > 0 -> {
                val reps = felder[SP_REPS] ?: 0
                val liste = abschnitte.filter { it.anzahl > 0 }.joinToString("/") { it.anzahl.toString() }
                val pausen = pausenSchnitt(abschnitte)
                buildString {
                    append(context.getString(R.string.ks_a_saetze, saetze.toInt()))
                    if (liste.isNotBlank()) append(" ($liste)")
                    if (reps > 0) append(", " + context.getString(R.string.ks_a_wdh, reps.toInt()))
                    if (pausen > 0) append(", " + context.getString(R.string.ks_a_pause, pausen.toInt()))
                }
            }
            else -> null
        }
    }

    private fun km(meter: Double): String = String.format(Locale.getDefault(), "%.1f", meter / 1000)

    /**
     * Eine Nachricht verarbeiten. Rueckgabe: ein Satz fuers Protokoll, oder
     * null, wenn es nichts zu sagen gibt.
     *
     * DIE ART DER NACHRICHT ERGIBT SICH AUS IHREN FELDERN, in dieser
     * Reihenfolge: Kurvenstueck, Einstellungen, Zustand (ohne Dauer), sonst
     * die Zusammenfassung.
     */
    suspend fun verarbeite(context: Context, n: KieselNachricht): String? {
        val felder = n.zahlen

        n.bytes[SP_KURVE]?.let { werte ->
            val beginn = felder[SP_BEGINN] ?: return null
            val ab = (felder[SP_KURVE_AB] ?: 0).toInt()
            val anzahl = (felder[SP_KURVE_ANZAHL] ?: 0).toInt()
            werte.forEachIndexed { i, b ->
                Pulskurve.anhaengen(context, beginn, (ab + i) * KURVE_TAKT_S, b.toInt() and 0xFF)
            }
            return if (ab + werte.size >= anzahl && Pulskurve.vorhanden(context, beginn)) {
                // Eine Kurve ohne einen einzigen Pulswert ist kein Fehler, sondern
                // ein Training ohne Sensorkontakt - dazu gibt es nichts zu sagen.
                if (Pulskurve.eintragen(context, beginn)) null
                else context.getString(R.string.ks_a_pulskurve_nicht)
            } else {
                null
            }
        }

        // DIE EINSTELLUNGEN DER UHR - beim Start der App und nach jeder Aenderung.
        if (felder[SP_ART] == null && KieselEinstellungen.vonDerUhr(context, felder, n.texte)) return null

        val zustand = felder[SP_ZUSTAND]
        val dauer = felder[SP_DAUER]
        if (dauer == null) {
            val beginn = felder[SP_BEGINN] ?: return null
            val artNummer = felder[SP_ART] ?: -1
            val (_, name) = artAlsSatzart(context, artNummer)
            // NUR WO ES EINE STRECKE GIBT, laeuft das GPS: Laufen, Bike,
            // Wandern, MTB. In der Halle zeichnete es nur Rauschen auf.
            val mitStrecke = artNummer in setOf(0L, 1L, 2L, 4L)
            return when (zustand) {
                1L -> {
                    if (mitStrecke) SpurDienst.starte(context, beginn, name)
                    context.getString(R.string.ks_a_begonnen, name)
                }
                2L -> { SpurDienst.stoppe(context); context.getString(R.string.ks_a_pausiert, name) }
                3L -> {
                    if (mitStrecke) SpurDienst.starte(context, beginn, name)
                    context.getString(R.string.ks_a_fortgesetzt, name)
                }
                0L -> { SpurDienst.stoppe(context); null }
                else -> null
            }
        }

        SpurDienst.stoppe(context)
        return trageEin(context, felder, n.texte, dauer)
    }

    private suspend fun trageEin(context: Context, felder: Map<Int, Long>, texte: Map<Int, String>, dauer: Long): String? {
        val beginn = felder[SP_BEGINN] ?: return null
        if (beginn <= 0 || dauer < 60) return null
        if (!Riegel.neu(context, "kieselsport", beginn.toString())) return null

        if (Akte(context).bereit() == null) {
            Riegel.loese(context, "kieselsport")
            return context.getString(R.string.ks_g_akte_fehlt)
        }
        val anfang = Instant.ofEpochSecond(beginn)
        // DIE STRECKE KOMMT VOM TELEFON, nicht von der Uhr - als Route an der Sitzung.
        val punkte = withContext(Dispatchers.IO) { Spur.lies(context, beginn) }
        val ende = sitzungsEnde(anfang, dauer, punkte, Instant.now())

        val (satzart, name) = artAlsSatzart(context, felder[SP_ART] ?: -1)
        val puls = felder[SP_PULS_MITTEL] ?: 0
        val kcal = felder[SP_KCAL] ?: 0

        val strecke = if (punkte.size >= 2) Spur.laenge(punkte) else 0.0
        val route = routeAus(punkte, anfang, ende)

        val abschnitte = abschnitteAus(texte[SP_ABSCHNITTE])
        val bahnen = felder[SP_BAHNEN] ?: 0
        val becken = if (bahnen > 0) (felder[SP_METER] ?: 0).toDouble() / bahnen else 0.0
        val segmente = if (bahnen > 0) emptyList() else alsSegmente(abschnitte, anfang, ende)
        val runden = if (bahnen > 0) alsBahnen(abschnitte, anfang, ende, becken) else emptyList()

        // Als Liste, damit kein Komma vorn steht, wenn etwas fehlt - ohne
        // Puls begann die Notiz in Kiesel-Helper mit ", 3.2 km".
        val teile = buildList {
            if (puls > 0) add(context.getString(R.string.ks_a_puls_schnitt, puls.toInt()))
            felder[SP_PULS_MAX]?.takeIf { it > 0 }?.let { add(context.getString(R.string.ks_a_max, it.toInt())) }
            if (strecke > 0) {
                add(km(strecke) + " km (GPS)")
            } else {
                felder[SP_METER]?.takeIf { it > 0 }?.let { add(km(it.toDouble()) + " km") }
            }
            if (kcal > 0) add("$kcal kcal")
        }
        val notiz = listOfNotNull(
            teile.joinToString(", ").ifBlank { null },
            abschnittsnotiz(context, felder, abschnitte),
        ).joinToString(" — ").ifBlank { null }

        // ERST MIT ALLEM, DANN OHNE DIE ABSCHNITTE: die Akte prueft Saetze und
        // Bahnen streng und weist im Zweifel den GANZEN Eintrag ab.
        val satz = try {
            ExerciseSessionRecord(
                startTime = anfang, startZoneOffset = null, endTime = ende, endZoneOffset = null,
                exerciseType = satzart, title = name, notes = notiz,
                metadata = vonDerUhr("kieselsport-$beginn"),
                segments = segmente, laps = runden, exerciseRoute = route,
            )
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Abschnitte abgewiesen, Training geht ohne sie: " + e.message)
            null
        }
        val ohne = ExerciseSessionRecord(
            startTime = anfang, startZoneOffset = null, endTime = ende, endZoneOffset = null,
            exerciseType = satzart, title = name, notes = notiz,
            metadata = vonDerUhr("kieselsport-$beginn"),
            exerciseRoute = route,
        )
        val hatAbschnitte = satz != null && (segmente.isNotEmpty() || runden.isNotEmpty())
        val dazu = buildString {
            if (strecke > 0) append(", " + km(strecke) + " km")
            if (bahnen > 0) append(", " + context.getString(R.string.ks_a_bahnen, bahnen.toInt()))
            if (segmente.isNotEmpty()) append(", " + context.getString(R.string.ks_a_saetze, (felder[SP_SAETZE] ?: 0).toInt()))
        }
        val kurz = context.getString(R.string.ks_a_eingetragen, name, (dauer / 60).toInt(), dazu)
        var meldung = schreibe(context, satz ?: ohne, kurz, riegel = "kieselsport")
        if (hatAbschnitte && meldung == context.getString(R.string.ks_a_nicht_eingetragen)) {
            Log.w(TAG, "Eintrag mit Abschnitten abgewiesen, zweiter Anlauf ohne")
            meldung = schreibe(context, ohne, context.getString(R.string.ks_a_ohne_abschnitte, kurz), riegel = "kieselsport")
        }

        // YOGA BRINGT EINE HRV MIT - als eigener Satz zum Ende des Trainings.
        felder[SP_HRV]?.takeIf { it in 5..300 }?.let { hrv ->
            if (Riegel.neu(context, "kieselsport-hrv", beginn.toString())) {
                schreibe(
                    context,
                    HeartRateVariabilityRmssdRecord(
                        time = ende, zoneOffset = null,
                        heartRateVariabilityMillis = hrv.toDouble(),
                        metadata = vonDerUhr("kieselsport-hrv-$beginn"),
                    ),
                    context.getString(R.string.ks_a_hrv_eingetragen, hrv.toInt()),
                    riegel = "kieselsport-hrv",
                )
                meldung += ", " + context.getString(R.string.ks_a_hrv, hrv.toInt())
            }
        }

        // Die Pulskurve, falls die Uhr sie schon geliefert hat.
        if (Pulskurve.eintragen(context, beginn)) meldung += ", " + context.getString(R.string.ks_a_pulskurve)
        return meldung
    }

    /**
     * Ein Training nachtragen, von dem nur die Spur blieb - die Art weiss nur,
     * wer dabei war, sie kommt deshalb vom Aufrufer.
     *
     * DIESELBE KENNUNG WIE BEIM NORMALEN WEG: kommt die Zusammenfassung doch
     * noch, ersetzt sie diesen Eintrag. Kalorien und Schritte fehlen - die
     * zaehlte die Uhr.
     */
    suspend fun nachtragen(context: Context, beginn: Long, art: Long): String {
        val punkte = withContext(Dispatchers.IO) { Spur.lies(context, beginn) }
        if (punkte.size < 2) return context.getString(R.string.ks_a_nicht_eingetragen)
        val klient = Akte(context).bereit() ?: return context.getString(R.string.ks_g_akte_fehlt)
        val anfang = Instant.ofEpochSecond(beginn)
        val ende = sitzungsEnde(anfang, 60, punkte, Instant.now())
        val (satzart, name) = artAlsSatzart(context, art)
        val strecke = Spur.laenge(punkte)
        // Der Puls aus der Akte - nur der eigene, den Kieselsport geschickt hat.
        val pulse = try {
            klient.readRecords(
                ReadRecordsRequest(
                    androidx.health.connect.client.records.HeartRateRecord::class,
                    TimeRangeFilter.between(anfang, ende),
                )
            ).records.filter { it.metadata.dataOrigin.packageName == context.packageName }
                .flatMap { it.samples }.map { it.beatsPerMinute }
        } catch (e: Exception) {
            emptyList()
        }
        val notiz = buildString {
            if (pulse.isNotEmpty()) {
                append(context.getString(R.string.ks_a_puls_schnitt, pulse.average().toInt()))
                append(", " + context.getString(R.string.ks_a_max, pulse.max().toInt()))
                append(", ")
            }
            append(km(strecke) + " km (GPS)")
        }
        val satz = try {
            ExerciseSessionRecord(
                startTime = anfang, startZoneOffset = null, endTime = ende, endZoneOffset = null,
                exerciseType = satzart, title = name, notes = notiz,
                metadata = vonDerUhr("kieselsport-$beginn"),
                exerciseRoute = routeAus(punkte, anfang, ende),
            )
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Nachtragen abgewiesen: " + e.message)
            return context.getString(R.string.ks_a_nicht_eingetragen)
        }
        val minuten = Duration.between(anfang, ende).toMinutes().toInt()
        val kurz = context.getString(R.string.ks_a_nachgetragen, name, minuten, ", " + km(strecke) + " km")
        return schreibe(context, satz, kurz).also { KieselVerlauf.merke(context, it) }
    }

    /**
     * Das Ende der Sitzung. Die Uhr schickt die AKTIVE Dauer, ohne Pausen; die
     * Akte verlangt aber, dass jeder Punkt der Route in der Sitzung liegt. Nach
     * einer Pause lagen die letzten Punkte hinter Beginn + Dauer, und die Akte
     * wies das ganze Training ab. Also bis zum letzten Punkt - hoechstens jetzt.
     */
    fun sitzungsEnde(anfang: Instant, dauer: Long, punkte: List<Spur.Punkt>, jetzt: Instant): Instant {
        val aktiv = anfang.plusSeconds(dauer)
        // Eine Sekunde hinter dem letzten Punkt: der Punkt selbst liegt dann sicher darin.
        val letzter = punkte.lastOrNull()?.let { Instant.ofEpochSecond(it.zeit + 1) } ?: return aktiv
        return minOf(maxOf(aktiv, letzter), maxOf(aktiv, jetzt))
    }

    /** Die Route, beschnitten auf die Sitzung - nur Punkte darin nimmt die Akte an. Null unter zwei Punkten. */
    fun routeAus(punkte: List<Spur.Punkt>, von: Instant, bis: Instant): ExerciseRoute? =
        punkte.filter { it.zeit >= von.epochSecond && it.zeit < bis.epochSecond }.takeIf { it.size >= 2 }?.let(::routeAus)

    fun routeAus(punkte: List<Spur.Punkt>): ExerciseRoute = ExerciseRoute(punkte.map { p ->
        ExerciseRoute.Location(
            time = Instant.ofEpochSecond(p.zeit),
            latitude = p.lat,
            longitude = p.lon,
            horizontalAccuracy = Length.meters(p.genauigkeit.toDouble()),
            altitude = p.hoehe?.let { Length.meters(it) },
        )
    })

    /** Sagt eine Meldung von verarbeite, dass die Akte den Eintrag nicht nahm? */
    fun gescheitert(context: Context, meldung: String?): Boolean =
        meldung == context.getString(R.string.ks_a_nicht_eingetragen) || meldung == context.getString(R.string.ks_g_akte_fehlt)

    /**
     * Von der Uhr gemessen, mit fester Kennung: kommt derselbe Satz noch
     * einmal, ersetzt die Akte ihn, statt ihn zu verdoppeln.
     */
    fun vonDerUhr(kennung: String): Metadata =
        Metadata.autoRecorded(Device(type = Device.TYPE_WATCH), kennung)

    /**
     * In die Akte schreiben - und bei einem Fehlschlag den Riegel wieder
     * oeffnen, sonst liefe der naechste Versuch der Uhr ins Leere.
     */
    suspend fun schreibe(context: Context, satz: Record, meldung: String, riegel: String? = null): String {
        val klient = Akte(context).bereit()
        if (klient == null) {
            riegel?.let { Riegel.loese(context, it) }
            return context.getString(R.string.ks_g_akte_fehlt)
        }
        return try {
            klient.insertRecords(listOf(satz))
            meldung
        } catch (e: Exception) {
            // Die haeufigste Ursache ist eine fehlende Erlaubnis.
            Log.w(TAG, "Eintragen fehlgeschlagen: " + e.message)
            riegel?.let { Riegel.loese(context, it) }
            context.getString(R.string.ks_a_nicht_eingetragen)
        }
    }
}
