package coredevices.pebble.kiesel

import android.content.Context
import android.content.Intent
import io.rebble.libpebblecommon.connection.HealthDataApi
import io.rebble.libpebblecommon.database.entity.NachtHrvEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * VORUEBERGEHEND: die Minuten der letzten Nacht als CSV teilen, damit die
 * Schlafregeln der Firmware an echten Naechten neben Fitbit nachgestellt
 * werden koennen. Seit dem Release-Build kommt die Datenbank nicht mehr per
 * run-as heraus. Faellt weg, sobald die Ergebnisse passen.
 */
internal object Nachtexport {
    /**
     * Die juengste Nacht der letzten drei Tage als (Name, CSV), oder null. Auch
     * null, wenn das Lesen scheitert (etwa solange der Umzug nach boulder.db
     * scheitert) - mit Grund im Log: der Aufruf laeuft im Scope der Seite, ein
     * Wurf dort beendete die App.
     */
    suspend fun csv(api: HealthDataApi): Pair<String, String>? = withContext(Dispatchers.IO) {
        versuche("Nachtexport", "Nacht nicht lesbar") { lies(api) }
    }

    private suspend fun lies(api: HealthDataApi): Pair<String, String>? {
        val jetzt = Instant.now().epochSecond
        val e = api.getNaechte(jetzt - 3 * 86_400L, jetzt).maxByOrNull { it.beginnUtc } ?: return null
        val bis = e.beginnUtc + (e.aufwachen + 60) * 60L
        val minuten = api.getHealthDataForRange(e.beginnUtc, bis).associateBy { it.timestamp }
        val abschnitte = api.getNachtAbschnitte(e.beginnUtc)
        val hrv = api.getNachtHrv(e.beginnUtc)
        val zone = ZoneId.systemDefault()
        val uhr = DateTimeFormatter.ofPattern("HH:mm")
        val text = buildString {
            append("# nacht beginn=${e.beginnUtc} einschlafen=${e.einschlafen} aufwachen=${e.aufwachen} ")
            append("schlaf=${e.schlafMinuten} ruhepuls=${e.ruhepuls} hrv=${e.hrv} messstufe=${e.messstufe}\n")
            append("minute,uhrzeit,schritte,vmc,puls,lage,phase\n")
            for (m in 0 until ((bis - e.beginnUtc) / 60).toInt()) {
                val t = e.beginnUtc + m * 60L
                val d = minuten[t] ?: continue
                val phase = abschnitte.firstOrNull { m >= it.von && m < it.bis }?.phase ?: -1
                append("$m,${uhr.format(Instant.ofEpochSecond(t).atZone(zone))},${d.steps},${d.intensity},${d.heartRate},${d.orientation},$phase\n")
            }
            append(hrvTeil(e.beginnUtc, hrv, zone))
        }
        val name = "nacht-" + DateTimeFormatter.ofPattern("yyyy-MM-dd").format(Instant.ofEpochSecond(e.beginnUtc).atZone(zone))
        return name to text
    }

    /**
     * Die HRV-Fenster hinter den Minuten, im Format der Testnaechte der Firmware
     * (nacht-2026-09-26.csv): eine Leerzeile, dann hrv_minute,uhrzeit,rmssd,puls.
     * Ohne Fenster (aeltere Firmware, Messstufe 1) faellt der Teil weg.
     */
    internal fun hrvTeil(beginnUtc: Long, fenster: List<NachtHrvEntity>, zone: ZoneId): String {
        if (fenster.isEmpty()) return ""
        val uhr = DateTimeFormatter.ofPattern("HH:mm")
        return buildString {
            append("\nhrv_minute,uhrzeit,rmssd,puls\n")
            for (f in fenster.sortedBy { it.minute }) {
                val zeit = uhr.format(Instant.ofEpochSecond(beginnUtc + f.minute * 60L).atZone(zone))
                append("${f.minute},$zeit,${f.rmssd},${f.puls}\n")
            }
        }
    }

    fun teile(context: Context, name: String, text: String) {
        val senden = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, name).putExtra(Intent.EXTRA_TEXT, text)
        context.startActivity(Intent.createChooser(senden, name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
