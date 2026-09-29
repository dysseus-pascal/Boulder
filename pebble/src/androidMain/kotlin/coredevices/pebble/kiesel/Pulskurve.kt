package coredevices.pebble.kiesel

import coredevices.pebble.R

import android.content.Context
import android.util.Log
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
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

    private const val ORDNER = "puls"
    /** Hoechstens so viele Punkte je Eintrag - die Akte mag keine Riesen. */
    private const val JE_EINTRAG = 900

    private fun ordner(context: Context): File =
        File(context.filesDir, ORDNER).apply { mkdirs() }

    private fun datei(context: Context, beginn: Long) = File(ordner(context), "$beginn.csv")

    @Synchronized
    fun anhaengen(context: Context, beginn: Long, sekunde: Int, puls: Int) {
        // Kein Puls ist kein Punkt: eine Null in der Kurve saehe aus wie ein
        // Herzstillstand.
        if (puls <= 0) return
        try {
            datei(context, beginn).appendText("$sekunde,$puls\n")
        } catch (e: Exception) {
            Log.w(TAG, "Pulskurve: " + e.message)
        }
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
    private fun lies(context: Context, beginn: Long): List<Pair<Int, Int>> {
        val f = datei(context, beginn)
        if (!f.exists()) return emptyList()
        return f.readLines().mapNotNull { zeile ->
            val t = zeile.split(',')
            if (t.size != 2) return@mapNotNull null
            val s = t[0].toIntOrNull() ?: return@mapNotNull null
            val p = t[1].toIntOrNull() ?: return@mapNotNull null
            if (p in 30..250) s to p else null
        }.sortedBy { it.first }.distinctBy { it.first }
    }

    suspend fun eintragen(context: Context, beginn: Long): Boolean {
        val punkte = lies(context, beginn)
        if (punkte.size < 2) return false
        val klient = Akte(context).bereit() ?: return false
        val anfang = Instant.ofEpochSecond(beginn)
        val saetze = punkte.chunked(JE_EINTRAG).map { stueck ->
            HeartRateRecord(
                startTime = anfang.plusSeconds(stueck.first().first.toLong()),
                startZoneOffset = versatz(anfang),
                endTime = anfang.plusSeconds(stueck.last().first.toLong() + 1),
                endZoneOffset = versatz(anfang),
                samples = stueck.map { (s, p) ->
                    HeartRateRecord.Sample(anfang.plusSeconds(s.toLong()), p.toLong())
                },
                metadata = Metadata.autoRecorded(
                    Device(type = Device.TYPE_WATCH), "kieselsport-puls-$beginn-${stueck.first().first}"
                ),
            )
        }
        return try {
            klient.insertRecords(saetze)
            datei(context, beginn).delete()
            KieselVerlauf.merke(
                context,
                context.resources.getQuantityString(R.plurals.ks_v_pulskurve, punkte.size, punkte.size),
            )
            true
        } catch (e: Exception) {
            Log.w(TAG, "Pulskurve nicht eingetragen: " + e.message)
            false
        }
    }
}
