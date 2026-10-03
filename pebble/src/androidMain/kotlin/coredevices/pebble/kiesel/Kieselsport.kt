package coredevices.pebble.kiesel

import android.content.Context
import android.util.Log
import androidx.health.connect.client.records.ExerciseLap
import androidx.health.connect.client.records.ExerciseRoute
import androidx.health.connect.client.records.ExerciseSegment
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Length
import coredevices.pebble.R
import coredevices.pebble.kiesel.ansicht.einheitenImperialHolen
import kotlinx.serialization.Serializable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
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
    // Die Kalorien der Uhr zaehlen nur, bis die aus dem Puls da sind
    // (Trainingswerte) - die Uhr rechnet sie aus Schritten, auf dem Rad fast null.
    const val SP_KCAL = 10005
    // Mittel- und Hoechstpuls liest Boulder bewusst nicht: roh von der Uhr,
    // nicht entstoert - der Puls kommt aus der Pulskurve. Die Schluessel
    // bleiben, weil die Uhr sie weiter schickt (telefon.c).
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
    // Neu in der Boulder-Firmware, hinter den Schluesseln der Store-App (bis
    // 10036). Fehlen sie, rechnet Boulder wie frueher.
    /** Wann eine Zustandsmeldung geschah, UTC - nicht wann sie ankam. */
    const val SP_ZEIT = 10037
    /** Die Pausen in der Zusammenfassung (Trainingspausen.ausUhr). */
    const val SP_PAUSEN = 10038
    /** Wann die aktive Zeit endete, UTC: der Beginn der letzten Pause. */
    const val SP_ENDE = 10039
    /** Ob der Maximalpuls eingestellt ist (1) oder die Uhr nach Alter rechnet (0). */
    const val SP_MAXPULS_GESETZT = 10040

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

    /** Die aktive Sekunde der Uhr als Uhrzeit - hinter jeder Pause um ihre Laenge spaeter. */
    private fun inWandzeit(anfang: Instant, aktiv: Long, pausen: List<Trainingspausen.Pause>): Instant =
        Instant.ofEpochSecond(Trainingspausen.wand(pausen, anfang.epochSecond, aktiv))

    /** [von, bis) ohne die Spannen [weg] - was uebrig bleibt, in Stuecken. */
    private fun abzueglich(von: Instant, bis: Instant, weg: List<Pair<Instant, Instant>>): List<Pair<Instant, Instant>> {
        var stuecke = listOf(von to bis)
        for ((pv, pb) in weg) {
            stuecke = stuecke.flatMap { (a, b) ->
                if (!pb.isAfter(a) || !pv.isBefore(b)) listOf(a to b)
                else listOfNotNull((a to pv).takeIf { pv.isAfter(a) }, (pb to b).takeIf { b.isAfter(pb) })
            }
        }
        return stuecke.filter { (a, b) -> b.isAfter(a) }
    }

    private fun alsZeiten(pausen: List<Trainingspausen.Pause>) =
        pausen.map { Instant.ofEpochSecond(it.von) to Instant.ofEpochSecond(it.bis) }

    /**
     * Aus den Abschnitten Saetze fuer die Akte machen - samt Ruhe dazwischen.
     *
     * ALLES ODER NICHTS: die Akte weist einen Satz ausserhalb der Sitzung oder
     * eine Ueberschneidung ab, und zwar den GANZEN Eintrag. Im Zweifel leer.
     *
     * DIE UHR ZAEHLT AKTIVE SEKUNDEN: hinter jeder [pausen] beginnt ein Satz
     * um ihre Laenge spaeter. Die Pause selbst ist keine Ruhe zwischen zwei
     * Saetzen - sie steht als eigenes Stueck in der Sitzung ([pausenSegmente]).
     */
    fun alsSegmente(
        abschnitte: List<Abschnitt>, anfang: Instant, ende: Instant,
        pausen: List<Trainingspausen.Pause> = emptyList(),
    ): List<ExerciseSegment> {
        val aus = mutableListOf<ExerciseSegment>()
        var vorheriges: Instant? = null
        val pausenzeiten = alsZeiten(pausen)
        abschnitte.forEach { a ->
            val von = inWandzeit(anfang, a.ab, pausen)
            val bis = von.plusSeconds(a.dauer)
            if (von.isBefore(anfang) || bis.isAfter(ende) || !bis.isAfter(von)) return emptyList()
            vorheriges?.let { letztes ->
                if (von.isBefore(letztes)) return emptyList()
                abzueglich(letztes, von, pausenzeiten).forEach { (rv, rb) ->
                    aus += ExerciseSegment(
                        startTime = rv,
                        endTime = rb,
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

    /** Aus den Abschnitten Bahnen machen - jede mit ihrer Laenge, hinter Pausen verschoben wie die Saetze. */
    fun alsBahnen(
        abschnitte: List<Abschnitt>, anfang: Instant, ende: Instant, beckenMeter: Double,
        pausen: List<Trainingspausen.Pause> = emptyList(),
    ): List<ExerciseLap> {
        val aus = mutableListOf<ExerciseLap>()
        var vorheriges: Instant? = null
        abschnitte.forEach { a ->
            val von = inWandzeit(anfang, a.ab, pausen)
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

    /**
     * Die Pausen der Uhr als eigene Stuecke der Sitzung, damit die Akte (und
     * jede App, die sie liest) sie nicht als Trainingszeit zaehlt. Was sich mit
     * einem Satz ueberschneidet (die Meldung kam ein paar Sekunden spaet),
     * gehoert dem Satz.
     */
    fun pausenSegmente(
        pausen: List<Trainingspausen.Pause>, anfang: Instant, ende: Instant, saetze: List<ExerciseSegment>,
    ): List<ExerciseSegment> {
        val belegt = saetze.map { it.startTime to it.endTime }
        return alsZeiten(pausen).flatMap { (von, bis) ->
            val drin = maxOf(von, anfang) to minOf(bis, ende)
            if (!drin.second.isAfter(drin.first)) emptyList() else abzueglich(drin.first, drin.second, belegt)
        }.map { (von, bis) -> ExerciseSegment(von, bis, ExerciseSegment.EXERCISE_SEGMENT_TYPE_PAUSE) }
    }

    /** Der Schnitt der Luecken zwischen den Saetzen, in Sekunden. */
    fun pausenSchnitt(abschnitte: List<Abschnitt>): Long =
        ruheSchnitt((1 until abschnitte.size).map { i -> abschnitte[i].ab - (abschnitte[i - 1].ab + abschnitte[i - 1].dauer) })

    /**
     * Der Schnitt der Ruhe zwischen Saetzen - fuer die Notiz und das Detail
     * dieselbe Rechnung. NUR 1 S BIS 30 MIN: zwanzig Minuten Geraetewechsel
     * sind keine Satzpause und zoegen den Schnitt auf das Zehnfache.
     */
    fun ruheSchnitt(luecken: List<Long>): Long {
        val gute = luecken.filter { it in 1..1800 }
        return if (gute.isEmpty()) 0 else gute.sum() / gute.size
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

    /**
     * Die Strecke fuer Protokoll und Meldung in Gravels Einheiten, wie die
     * Trainingsliste daneben: dort stand "5.0 mi", hier "8.1 km". Schwimmen
     * bleibt metrisch ([Sport.immerMetrisch]). Ohne Koin metrisch - Gravels
     * Vorgabe; der Eintrag steht dann schon in der Akte und darf nicht scheitern.
     */
    private suspend fun streckeText(meter: Double, satzart: Int, name: String): String {
        val imperial = !Sport.von(satzart, name).immerMetrisch &&
            try { einheitenImperialHolen() } catch (e: IllegalStateException) { false }
        return Formate.distanz(meter, imperial).text
    }

    /**
     * Eine Nachricht verarbeiten. Rueckgabe: ein Satz fuers Protokoll, oder
     * null, wenn es nichts zu sagen gibt.
     *
     * DIE ART DER NACHRICHT ERGIBT SICH AUS IHREN FELDERN, in dieser
     * Reihenfolge: Kurvenstueck, Einstellungen, Zustand (ohne Dauer), sonst
     * die Zusammenfassung.
     */
    suspend fun verarbeite(context: Context, n: KieselNachricht, empfangen: Long = Instant.now().epochSecond): String? {
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
                // Wartet sie auf die Pausen, traegt die Zusammenfassung sie ein.
                if (Pulskurve.eintragen(context, beginn) || Trainingspausen.wartet(context, beginn, Instant.now().epochSecond)) null
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
            // Pause und Weiter mit ihrer Uhrzeit: die Uhr zaehlt aktive Sekunden,
            // die Akte braucht die Uhrzeit (Trainingspausen).
            val zeit = zeitDerMeldung(felder[SP_ZEIT], beginn, empfangen)
            return when (zustand) {
                1L -> {
                    if (mitStrecke) SpurDienst.starte(context, beginn, name)
                    context.getString(R.string.ks_a_begonnen, name)
                }
                2L -> {
                    Trainingspausen.merke(context, beginn, pause = true, zeit = zeit)
                    SpurDienst.stoppe(context, beginn)
                    context.getString(R.string.ks_a_pausiert, name)
                }
                3L -> {
                    Trainingspausen.merke(context, beginn, pause = false, zeit = zeit)
                    if (mitStrecke) SpurDienst.starte(context, beginn, name)
                    context.getString(R.string.ks_a_fortgesetzt, name)
                }
                // Ohne Dauer heisst Stopp: verworfen - seine Pausen braucht niemand.
                0L -> { SpurDienst.stoppe(context, beginn); Trainingspausen.vergiss(context, beginn); null }
                else -> null
            }
        }

        // NUR DIE SPUR DIESES TRAININGS: kommt die Zusammenfassung eines
        // frueheren erst, wenn das naechste schon laeuft, liefe dessen GPS weiter.
        felder[SP_BEGINN]?.let { SpurDienst.stoppe(context, it) }
        return trageEin(context, n, dauer, empfangen)
    }

    /**
     * Die Uhrzeit einer Zustandsmeldung: die der Uhr, wenn sie mitkam und
     * passt, sonst der Empfang - nicht erst das Abarbeiten der Warteschlange,
     * das hinter einem langen Eintrag Sekunden spaeter kommt. Eine Zeit vor
     * dem Beginn oder nach dem Empfang ist eine falsch gestellte Uhr.
     */
    fun zeitDerMeldung(vonDerUhr: Long?, beginn: Long, empfangen: Long): Long =
        vonDerUhr?.takeIf { it >= beginn && it <= empfangen + Trainingspausen.TOLERANZ_S } ?: empfangen

    private suspend fun trageEin(context: Context, n: KieselNachricht, dauer: Long, empfangen: Long): String? {
        val felder = n.zahlen
        val texte = n.texte
        val beginn = felder[SP_BEGINN] ?: return null
        if (beginn <= 0 || dauer < 60) return null
        val jetzt = Instant.ofEpochSecond(empfangen)
        // VOR DEM RIEGEL: auch eine wiederholte Zusammenfassung legt die Pausen
        // ab - die Kurve, die nach ihr kommt, rechnet mit dem Ergebnis. DIE
        // PAUSEN DER UHR GEHEN VOR: sie sind sekundengenau und vollstaendig;
        // nur eine aeltere Firmware schickt keine, dann die Ankunftszeiten.
        val plan = Trainingspausen.ausUhr(n.bytes[SP_PAUSEN], beginn, dauer, felder[SP_ENDE])
            ?.also { Trainingspausen.uebernimm(context, beginn, it) }
            ?: Trainingspausen.pruefe(context, beginn, dauer, jetzt.epochSecond)
        if (!Riegel.neu(context, "kieselsport", beginn.toString())) return null

        if (Akte(context).bereit() == null) {
            Riegel.loese(context, "kieselsport")
            return context.getString(R.string.ks_g_akte_fehlt)
        }
        val anfang = Instant.ofEpochSecond(beginn)
        // DIE STRECKE KOMMT VOM TELEFON, nicht von der Uhr - als Route an der Sitzung.
        val alle = withContext(Dispatchers.IO) { Spur.lies(context, beginn) }
        val ende = sitzungsEnde(anfang, dauer, alle, jetzt, plan)
        // Strecke, Route, Hoehe und Tempo aus denselben Punkten - denen der Sitzung.
        val punkte = Spur.inSitzung(alle, anfang, ende)

        val (satzart, name) = artAlsSatzart(context, felder[SP_ART] ?: -1)
        val kcal = felder[SP_KCAL] ?: 0

        val strecke = if (punkte.size >= 2) Spur.laenge(punkte) else 0.0
        val route = routeAus(punkte, anfang, ende)

        val abschnitte = abschnitteAus(texte[SP_ABSCHNITTE])
        val bahnen = felder[SP_BAHNEN] ?: 0
        val becken = if (bahnen > 0) (felder[SP_METER] ?: 0).toDouble() / bahnen else 0.0
        val saetze = if (bahnen > 0) emptyList() else alsSegmente(abschnitte, anfang, ende, plan.pausen)
        val runden = if (bahnen > 0) alsBahnen(abschnitte, anfang, ende, becken, plan.pausen) else emptyList()
        val pausen = pausenSegmente(plan.pausen, anfang, ende, saetze)

        // NUR SAETZE UND BAHNEN IN DIE NOTIZ. Puls, Strecke und Kalorien der
        // Uhr standen frueher auch darin - roh, nicht entstoert, die kcal aus
        // Schritten - und widersprachen den Zahlen, die die Akte daneben fuehrt.
        val notiz = abschnittsnotiz(context, felder, abschnitte)

        // ERST MIT ALLEM, DANN NUR MIT DEN PAUSEN: die Akte prueft Saetze und
        // Bahnen streng und weist im Zweifel den GANZEN Eintrag ab.
        fun sitzung(segmente: List<ExerciseSegment>, laps: List<ExerciseLap>): ExerciseSessionRecord? = try {
            ExerciseSessionRecord(
                startTime = anfang, startZoneOffset = versatz(anfang), endTime = ende, endZoneOffset = versatz(ende),
                exerciseType = satzart, title = name, notes = notiz,
                metadata = vonDerUhr(kennung(beginn)),
                segments = segmente.sortedBy { it.startTime }, laps = laps, exerciseRoute = route,
            )
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Abschnitte abgewiesen: " + e.message)
            null
        }
        val satz = sitzung(saetze + pausen, runden)
        val ohne = sitzung(pausen, emptyList()) ?: sitzung(emptyList(), emptyList()) ?: return context.getString(R.string.ks_a_nicht_eingetragen)
        val hatAbschnitte = satz != null && (saetze.isNotEmpty() || runden.isNotEmpty())
        val dazu = buildString {
            if (strecke > 0) append(", " + streckeText(strecke, satzart, name))
            if (bahnen > 0) append(", " + context.getString(R.string.ks_a_bahnen, bahnen.toInt()))
            if (saetze.isNotEmpty()) append(", " + context.getString(R.string.ks_a_saetze, (felder[SP_SAETZE] ?: 0).toInt()))
        }
        val kurz = context.getString(R.string.ks_a_eingetragen, name, (dauer / 60).toInt(), dazu)
        var meldung = schreibe(context, satz ?: ohne, kurz, riegel = "kieselsport")
        if (hatAbschnitte && meldung == context.getString(R.string.ks_a_nicht_eingetragen)) {
            Log.w(TAG, "Eintrag mit Abschnitten abgewiesen, zweiter Anlauf ohne")
            meldung = schreibe(context, ohne, context.getString(R.string.ks_a_ohne_abschnitte, kurz), riegel = "kieselsport")
        }

        // Kalorien und Strecke als eigene Saetze - auch wenn die Sitzung
        // abgewiesen wurde: der naechste Versuch der Uhr ersetzt sie nur.
        val meter = if (strecke > 0) strecke else (felder[SP_METER] ?: 0).toDouble()
        Trainingswerte.schreibeStrecke(context, beginn, anfang, ende, meter, punkte)
        Trainingswerte.schreibeKalorienDerUhr(context, beginn, anfang, ende, kcal)

        // YOGA BRINGT EINE HRV MIT - als eigener Satz zum Ende des Trainings.
        felder[SP_HRV]?.takeIf { it in 5..300 }?.let { hrv ->
            if (Riegel.neu(context, "kieselsport-hrv", beginn.toString())) {
                schreibe(
                    context,
                    HeartRateVariabilityRmssdRecord(
                        time = ende, zoneOffset = versatz(ende),
                        heartRateVariabilityMillis = hrv.toDouble(),
                        metadata = vonDerUhr("kieselsport-hrv-$beginn"),
                    ),
                    context.getString(R.string.ks_a_hrv_eingetragen, hrv.toInt()),
                    riegel = "kieselsport-hrv",
                )
                meldung += ", " + context.getString(R.string.ks_a_hrv, hrv.toInt())
            }
        }

        // Die Pulskurve, falls die Uhr sie schon geliefert hat - samt den Kalorien aus ihr.
        if (Pulskurve.eintragen(context, beginn)) meldung += ", " + context.getString(R.string.ks_a_pulskurve)
        // Kam die Kurve vor der Sitzung, fehlte ihr das Fenster fuer die Kalorien.
        else Trainingswerte.schreibeKalorienAusPuls(context, beginn)
        return meldung
    }

    /**
     * Ein Training nachtragen, von dem nur die Spur blieb - die Art weiss nur,
     * wer dabei war, sie kommt deshalb vom Aufrufer.
     *
     * DIESELBE KENNUNG WIE BEIM NORMALEN WEG: kommt die Zusammenfassung doch
     * noch, ersetzt sie diesen Eintrag. Schritte fehlen - die zaehlte die Uhr.
     * Distanz, Hoehe und Tempo kommen aus der Spur, die Kalorien aus dem Puls,
     * wenn seine Kurve schon in der Akte steht. Die Pausen, die ankamen,
     * gelten ohne Abgleich - ohne Zusammenfassung gibt es keine Dauer dafuer;
     * nur eine sichtbar lueckenhafte Folge verwerfen sie.
     */
    suspend fun nachtragen(context: Context, beginn: Long, art: Long): String {
        val alle = withContext(Dispatchers.IO) { Spur.lies(context, beginn) }
        if (alle.size < 2) return context.getString(R.string.ks_a_nicht_eingetragen)
        if (Akte(context).bereit() == null) return context.getString(R.string.ks_g_akte_fehlt)
        val anfang = Instant.ofEpochSecond(beginn)
        val plan = Trainingspausen.ohneBruch(Trainingspausen.ungeprueft(context, beginn))
        // Ohne Dauer der Uhr keine Grenze ueber ihr - nur die letzte Pause, falls sie ankam.
        val ende = sitzungsEnde(anfang, 60, alle, Instant.now(), plan, ohnePause = null)
        // Wie beim normalen Weg: Strecke, Route, Hoehe und Tempo nur aus der Sitzung.
        val punkte = Spur.inSitzung(alle, anfang, ende)
        val (satzart, name) = artAlsSatzart(context, art)
        val strecke = Spur.laenge(punkte)
        val pausen = pausenSegmente(plan.pausen, anfang, ende, emptyList())
        val satz = try {
            ExerciseSessionRecord(
                startTime = anfang, startZoneOffset = versatz(anfang), endTime = ende, endZoneOffset = versatz(ende),
                exerciseType = satzart, title = name,
                metadata = vonDerUhr(kennung(beginn)),
                segments = pausen,
                exerciseRoute = routeAus(punkte, anfang, ende),
            )
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Nachtragen abgewiesen: " + e.message)
            return context.getString(R.string.ks_a_nicht_eingetragen)
        }
        val pausenSek = pausen.sumOf { Duration.between(it.startTime, it.endTime).seconds }
        val minuten = ((Duration.between(anfang, ende).seconds - pausenSek) / 60).toInt()
        val kurz = context.getString(R.string.ks_a_nachgetragen, name, minuten, ", " + streckeText(strecke, satzart, name))
        val meldung = schreibe(context, satz, kurz)
        Trainingswerte.schreibeStrecke(context, beginn, anfang, ende, strecke, punkte)
        // Erst jetzt gibt es das Fenster, in dem die schon gelieferte Kurve zaehlt.
        Trainingswerte.schreibeKalorienAusPuls(context, beginn)
        return meldung.also { KieselVerlauf.merke(context, it) }
    }

    /** Nach der letzten Pause noch so viel Spur: das GPS haelt erst an, wenn ihre Meldung da ist. */
    const val PUFFER_S = 60L

    /**
     * Ohne bekannte letzte Pause hoechstens so viel ueber die aktive Zeit
     * hinaus. Verlor die Uhr eine Pausenmeldung, lief die Spur in der Pause
     * weiter - zwanzig Minuten Kaffee bleiben drin. Eine Zusammenfassung, die
     * erst nach der Heimfahrt ankommt, bringt aber keine Autobahn mehr mit.
     */
    const val PUFFER_OHNE_PAUSE_S = 20 * 60L

    /**
     * Das Ende der Sitzung. Die Uhr schickt die AKTIVE Dauer, ohne Pausen; die
     * Akte verlangt aber, dass jeder Punkt der Route in der Sitzung liegt.
     * Also Beginn + Dauer + die bekannten Pausen, und die Spur bis zu ihrem
     * letzten Punkt - hoechstens jetzt, und nie ueber die letzte Pause (an
     * ihr wurde gespeichert) oder [ohnePause] hinaus.
     */
    fun sitzungsEnde(
        anfang: Instant, dauer: Long, punkte: List<Spur.Punkt>, jetzt: Instant,
        plan: Trainingspausen.Plan = Trainingspausen.Plan.LEER, ohnePause: Long? = PUFFER_OHNE_PAUSE_S,
    ): Instant {
        val aktiv = Instant.ofEpochSecond(Trainingspausen.wand(plan.pausen, anfang.epochSecond, dauer))
        val grenze = plan.offen?.let { Instant.ofEpochSecond(it + PUFFER_S) } ?: ohnePause?.let { aktiv.plusSeconds(it) }
        // Eine Sekunde hinter dem letzten Punkt: der Punkt selbst liegt dann sicher darin.
        val letzter = punkte.lastOrNull { grenze == null || it.zeit < grenze.epochSecond }
            ?.let { Instant.ofEpochSecond(it.zeit + 1) } ?: return aktiv
        return minOf(maxOf(aktiv, letzter), maxOf(aktiv, jetzt))
    }

    /** Die Route, beschnitten auf die Sitzung - nur Punkte darin nimmt die Akte an. Null unter zwei Punkten. */
    fun routeAus(punkte: List<Spur.Punkt>, von: Instant, bis: Instant): ExerciseRoute? =
        Spur.inSitzung(punkte, von, bis).takeIf { it.size >= 2 }?.let(::routeAus)

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

    /** Die Kennung der Sitzung eines Trainings - an ihr finden sich Nachtrag und Kalorien wieder. */
    fun kennung(beginn: Long): String = "kieselsport-$beginn"

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
