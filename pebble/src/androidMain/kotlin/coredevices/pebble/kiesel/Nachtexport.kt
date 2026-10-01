package coredevices.pebble.kiesel

import android.content.Context
import android.content.Intent
import io.rebble.libpebblecommon.connection.HealthDataApi
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
    suspend fun csv(api: HealthDataApi): Pair<String, String>? = withContext(Dispatchers.IO) {
        val jetzt = Instant.now().epochSecond
        val e = api.getNaechte(jetzt - 3 * 86_400L, jetzt).maxByOrNull { it.beginnUtc } ?: return@withContext null
        val bis = e.beginnUtc + (e.aufwachen + 60) * 60L
        val minuten = api.getHealthDataForRange(e.beginnUtc, bis).associateBy { it.timestamp }
        val abschnitte = api.getNachtAbschnitte(e.beginnUtc)
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
        }
        val name = "nacht-" + DateTimeFormatter.ofPattern("yyyy-MM-dd").format(Instant.ofEpochSecond(e.beginnUtc).atZone(zone))
        name to text
    }

    fun teile(context: Context, name: String, text: String) {
        val senden = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, name).putExtra(Intent.EXTRA_TEXT, text)
        context.startActivity(Intent.createChooser(senden, name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
