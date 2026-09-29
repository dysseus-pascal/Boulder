package coredevices.pebble.kiesel

import android.content.Context
import android.util.Log
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Volume
import coredevices.pebble.R
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlin.uuid.Uuid

/**
 * Drinktervall: getrunkenes Wasser in die Akte - wie in Kiesel-Helper
 * (Aufgaben.wasser).
 *
 * DIE UHR BESTIMMT ZIEL UND GLASGROESSE, nicht diese App: beides wird auf der
 * Uhr oder in der Konfigseite von Drinktervall eingestellt und kommt mit jeder
 * Meldung mit. Hier wird es nur gemerkt, fuer die Gesundheitsansicht.
 */
internal object Drinktervall {
    private const val TAG = "Drinktervall"
    private const val DATEI = "kiesel-wasser"

    val UUID: Uuid = Uuid.parse("5b0f7a3e-2c8d-4b61-9e4f-7d2a1c9b8e50")

    /**
     * Die Feldnummern folgen der Reihenfolge der messageKeys in der
     * package.json der Uhr-App, ab 10000. Wer dort eine Zeile dazwischen
     * schiebt, verschiebt alle folgenden.
     */
    const val DT_GLASSES = 10001   //< das heutige Ziel in Glaesern, bei jeder Meldung
    const val DT_GLASS_ML = 10008
    const val DT_DRANK_AT = 10009

    /**
     * Wie weit zwei Eintraege auseinander liegen duerfen und trotzdem dasselbe
     * Glas sind. Eng: zwei Glaeser in fuenf Minuten sind moeglich, zwei in
     * derselben Minute nicht.
     */
    private val FENSTER: Duration = Duration.ofMinutes(1)

    data class Ziel(val glaeser: Int, val glasMl: Int) {
        val ml: Int get() = glaeser * glasMl
    }

    /** Das heutige Ziel, wie Drinktervall es zuletzt meldete - null, wenn es noch nie kam. */
    fun ziel(context: Context): Ziel? {
        val p = context.getSharedPreferences(DATEI, Context.MODE_PRIVATE)
        if (!p.contains("glaeser")) return null
        return Ziel(p.getInt("glaeser", 8), p.getInt("glas_ml", 300))
    }

    suspend fun verarbeite(context: Context, n: KieselNachricht): String? {
        val felder = n.zahlen
        // DIE GLASGROESSE NUR OHNE GLAS: traegt die Meldung ein Glas, steht in
        // GLASS_ML dessen Menge - und die kann eine andere sein.
        val mitGlas = felder[DT_DRANK_AT] != null
        context.getSharedPreferences(DATEI, Context.MODE_PRIVATE).edit().apply {
            felder[DT_GLASSES]?.let { putInt("glaeser", it.toInt()); putString("tag", LocalDate.now().toString()) }
            if (!mitGlas) felder[DT_GLASS_ML]?.let { putInt("glas_ml", it.toInt()) }
            apply()
        }

        val ml = felder[DT_GLASS_ML] ?: return null
        val wann = felder[DT_DRANK_AT] ?: return null
        if (ml <= 0 || wann <= 0) return null
        // NICHT ZWEIMAL FUER DENSELBEN AUGENBLICK: die Uhr schickt ihren Stand
        // bei jeder Gelegenheit mit, nicht nur beim Trinken.
        if (!Riegel.neu(context, "drinktervall", wann.toString())) return null

        // HOECHSTENS JETZT: geht die Uhr ein paar Sekunden vor, laege das Glas
        // in der Zukunft - und die Akte lehnt einen solchen Eintrag ab.
        val beginn = minOf(Instant.ofEpochSecond(wann), Instant.now())
        val klient = Akte(context).bereit()
        if (klient == null) {
            Riegel.loese(context, "drinktervall")
            return context.getString(R.string.ks_g_akte_fehlt)
        }
        // SCHON EINGETRAGEN VON EINER ANDEREN APP? Solange Kiesel-Helper noch
        // installiert ist, kann es dasselbe Glas schon eingetragen haben - die
        // Akte fuehrt nur zusammen, was aus derselben App kommt.
        val fremd = try {
            klient.readRecords(
                ReadRecordsRequest(HydrationRecord::class, TimeRangeFilter.between(beginn.minus(FENSTER), beginn.plus(FENSTER)))
            ).records.map { it.metadata.dataOrigin.packageName }.firstOrNull { it.isNotEmpty() && it != context.packageName }
        } catch (e: Exception) {
            Log.w(TAG, "Nachsehen fehlgeschlagen: " + e.message)
            null
        }
        if (fremd != null) return context.getString(R.string.ks_w_uebersprungen, fremd)

        val satz = HydrationRecord(
            startTime = beginn,
            startZoneOffset = null,
            // Eine Minute: die Akte will eine Spanne, und ein Glas trinkt sich
            // nicht in einem Augenblick.
            endTime = beginn.plusSeconds(60),
            endZoneOffset = null,
            volume = Volume.milliliters(ml.toDouble()),
            metadata = Kieselsport.vonDerUhr("drinktervall-$wann"),
        )
        return Kieselsport.schreibe(context, satz, context.getString(R.string.ks_w_eingetragen, ml.toInt()), riegel = "drinktervall")
    }
}
