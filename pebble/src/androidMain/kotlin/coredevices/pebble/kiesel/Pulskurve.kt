package coredevices.pebble.kiesel

import coredevices.pebble.R

import android.content.Context
import co.touchlab.kermit.Logger
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.time.Instant

/**
 * Die Pulskurve eines Trainings: von der Uhr Satz fuer Satz gesammelt, am
 * Ende als EIN Herzfrequenz-Eintrag in die Gesundheitsakte geschrieben.
 *
 * ERST SAMMELN, DANN SCHREIBEN. Die Saetze kommen einzeln und in
 * beliebiger Reihenfolge; jeder einzeln in die Akte geschrieben gaebe
 * tausend Eintraege je Stunde. Sie liegen deshalb als Zeilen in einer Datei
 * je Training (puls/<beginn>.csv) und gehen gesammelt hinueber - sobald das
 * Log der Uhr abgeschlossen ist oder die Zusammenfassung des Trainings
 * ankommt, je nachdem, was zuerst geschieht.
 *
 * ANZEIGE GRATIS: eine Trainingsansicht liest die Kurve aus der Akte
 * (HeartRateRecord ueber die Zeit der Sitzung). Was hier geschrieben wird,
 * steht damit von selbst unter dem Training.
 */
internal object Pulskurve {
    private const val TAG = "Kieselsport"
    private val logger = Logger.withTag(TAG)

    private const val ORDNER = "puls"
    /** Hoechstens so viele Punkte je Eintrag - die Akte mag keine Riesen. */
    private const val JE_EINTRAG = 900

    private fun ordner(context: Context): File =
        File(context.filesDir, ORDNER).apply { mkdirs() }

    private fun datei(context: Context, beginn: Long) = File(ordner(context), "$beginn.csv")

    /**
     * Die Zone beim Empfang der Kurve (KieselNachricht.zone) - neben der Kurve,
     * nicht in ihr: [vergiss] schneidet die Kurve vorne ab. Kurven aelterer
     * Fassungen haben keine und nehmen die Zone von jetzt.
     */
    private fun zonendatei(context: Context, beginn: Long) = File(ordner(context), "$beginn.zone")

    /** [zone]: die beim Empfang; die erste gemerkte gilt fuer die ganze Kurve, wie die Zone der Sitzung. */
    @Synchronized
    fun anhaengen(context: Context, beginn: Long, sekunde: Int, puls: Int, zone: String? = null) {
        // Kein Puls ist kein Punkt: eine Null in der Kurve saehe aus wie ein
        // Herzstillstand.
        if (puls <= 0) return
        try {
            zone?.let { z -> zonendatei(context, beginn).takeIf { !it.exists() }?.writeText(z) }
            datei(context, beginn).appendText("$sekunde,$puls\n")
        } catch (e: Exception) {
            logger.w { "Pulskurve: " + e.message }
        }
    }

    @Synchronized
    private fun zone(context: Context, beginn: Long): String? = try {
        zonendatei(context, beginn).takeIf { it.exists() }?.readText()?.trim()?.ifEmpty { null }
    } catch (e: Exception) {
        logger.w { "Zone der Pulskurve $beginn nicht lesbar, nehme die von jetzt: " + e.message }
        null
    }

    /** Gibt es ueberhaupt Pulswerte? Ohne Sensorkontakt kommen nur Nullen, und die werden nie gesammelt. */
    fun vorhanden(context: Context, beginn: Long): Boolean = datei(context, beginn).exists()

    /** Alle gesammelten Kurven in die Akte - was gelingt, wird geloescht. */
    suspend fun alleEintragen(context: Context) {
        val dateien = ordner(context).listFiles { f -> f.name.endsWith(".csv") } ?: return
        for (f in dateien) {
            val beginn = f.nameWithoutExtension.toLongOrNull() ?: continue
            eintragen(context, beginn)
        }
    }

    /**
     * Die Kurve zu diesem Training in die Akte; true, wenn sie drin ist.
     *
     * DIE KENNUNG MACHT ES WIEDERHOLBAR: derselbe Beginn und dieselbe erste
     * Sekunde ergeben dieselbe clientRecordId, und die Akte ersetzt statt zu
     * verdoppeln. Kommt die Datei zweimal an die Reihe, steht die Kurve
     * trotzdem einmal da. NACH DER SEKUNDE, NICHT NACH DER NUMMER DES
     * STUECKS: kommt eine Kurve in zwei Uebertragungen, beginnt die zweite
     * Datei wieder bei Stueck 0 und haette den ersten Teil ersetzt.
     */
    @Synchronized
    private fun lies(context: Context, beginn: Long): Pair<List<Pair<Int, Int>>, Int> {
        val f = datei(context, beginn)
        if (!f.exists()) return emptyList<Pair<Int, Int>>() to 0
        // Dazu, wie viele Bytes die Datei dabei hatte: [vergiss] nimmt nur so viel weg.
        val bytes = f.readBytes()
        return String(bytes).lines().mapNotNull { zeile ->
            val t = zeile.split(',')
            if (t.size != 2) return@mapNotNull null
            val s = t[0].toIntOrNull() ?: return@mapNotNull null
            val p = t[1].toIntOrNull() ?: return@mapNotNull null
            if (p in 30..250) s to p else null
        }.sortedBy { it.first }.distinctBy { it.first } to bytes.size
    }

    /** Vergisst nur die [gelesen] Bytes: ein Stueck, das waehrend des Eintragens ankam, bleibt fuer den naechsten. */
    @Synchronized
    private fun vergiss(context: Context, beginn: Long, gelesen: Int) {
        val f = datei(context, beginn)
        try {
            if (!f.exists()) return
            val bytes = f.readBytes()
            if (bytes.size <= gelesen) {
                f.delete()
                // Mit der Kurve geht ihre Zone; ein spaeteres Stueck bringt seine eigene mit.
                zonendatei(context, beginn).delete()
            } else {
                f.writeBytes(bytes.copyOfRange(gelesen, bytes.size))
            }
        } catch (e: Exception) {
            logger.w { "Pulskurve $beginn nicht aufgeraeumt, kommt beim naechsten Start noch einmal (dieselbe Kennung ersetzt): " + e.message }
        }
    }

    // EINER NACH DEM ANDEREN: der Start traegt liegengebliebene Kurven ein, waehrend die Schlange neue
    // Stuecke verarbeitet - von zwei Eintraegen derselben Kennung ersetzte der spaetere den vollstaendigeren.
    private val einzeln = Mutex()

    suspend fun eintragen(context: Context, beginn: Long): Boolean = einzeln.withLock { eintragenAllein(context, beginn) }

    private suspend fun eintragenAllein(context: Context, beginn: Long): Boolean {
        val (punkte, gelesen) = lies(context, beginn)
        if (punkte.size < 2) return false
        // DIE SEKUNDEN DER UHR SIND AKTIVE ZEIT. Gab es Pausen, wartet die
        // Kurve auf die Zusammenfassung: erst sie sagt, ob die Pausen stimmen
        // (Trainingspausen). Ohne Pausen geht sie gleich.
        val plan = Trainingspausen.geprueft(context, beginn)
        if (plan == null && Trainingspausen.wartet(context, beginn, Instant.now().epochSecond)) return false
        val pausen = plan?.pausen.orEmpty()
        fun zeit(s: Int): Instant = Instant.ofEpochSecond(Trainingspausen.wand(pausen, beginn, s.toLong()))
        val klient = Akte(context).bereit() ?: return false
        val anfang = Instant.ofEpochSecond(beginn)
        // Der Versatz von dort, wo die Kurve ankam - wie Sitzung, Strecke und Kalorien desselben Trainings.
        val zone = zoneOderJetzt(zone(context, beginn))
        val saetze = punkte.chunked(JE_EINTRAG).map { stueck ->
            HeartRateRecord(
                startTime = zeit(stueck.first().first),
                startZoneOffset = versatz(anfang, zone),
                endTime = zeit(stueck.last().first).plusSeconds(1),
                endZoneOffset = versatz(anfang, zone),
                samples = stueck.map { (s, p) ->
                    HeartRateRecord.Sample(zeit(s), p.toLong())
                },
                metadata = Metadata.autoRecorded(
                    Device(type = Device.TYPE_WATCH), "kieselsport-puls-$beginn-${stueck.first().first}"
                ),
            )
        }
        return try {
            klient.insertRecords(saetze)
            // Die Kalorien aus der ganzen Kurve in der Akte, nicht nur aus diesem
            // Stueck - scheitern sie, holt sie der naechste Start nach (Trainingswerte).
            Trainingswerte.schreibeKalorienAusPuls(context, beginn)
            vergiss(context, beginn, gelesen)
            KieselVerlauf.merke(
                context,
                context.resources.getQuantityString(R.plurals.ks_v_pulskurve, punkte.size, punkte.size),
            )
            true
        } catch (e: Exception) {
            logger.w { "Pulskurve nicht eingetragen: " + e.message }
            false
        }
    }
}
