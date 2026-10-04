package coredevices.pebble.kiesel

import android.content.Context
import co.touchlab.kermit.Logger
import org.json.JSONArray
import org.json.JSONObject

/**
 * Was Kieselsport auf dem Telefon ausgeloest hat - die letzten 100 Zeilen.
 *
 * OHNE DIESE ZEILEN SAEHE MAN DEM EMPFANG NIE AN, OB ER ETWAS TUT. Ein
 * Training, das nicht in der Akte steht, weil eine Erlaubnis fehlt, soll
 * einen Satz hinterlassen, den man nachlesen kann. Die Trainingsansicht
 * zeigt ihn spaeter an.
 */
internal object KieselVerlauf {
    private const val DATEI = "kiesel-verlauf"
    private const val SCHLUESSEL = "zeilen"
    private const val HOECHSTENS = 100
    private val logger = Logger.withTag("Kieselsport")

    @Synchronized
    fun merke(context: Context, zeile: String) {
        logger.i { zeile }
        val p = context.getSharedPreferences(DATEI, Context.MODE_PRIVATE)
        val alt = try { JSONArray(p.getString(SCHLUESSEL, "[]")) } catch (e: Exception) {
            logger.w { "Protokoll unlesbar, beginnt neu: " + e.message }
            JSONArray()
        }
        val neu = JSONArray()
        neu.put(JSONObject().put("t", System.currentTimeMillis()).put("z", zeile))
        for (i in 0 until minOf(alt.length(), HOECHSTENS - 1)) neu.put(alt.get(i))
        p.edit().putString(SCHLUESSEL, neu.toString()).apply()
    }

    /** Juengste zuerst: Zeitpunkt in Millisekunden und Text. */
    fun alle(context: Context): List<Pair<Long, String>> {
        val p = context.getSharedPreferences(DATEI, Context.MODE_PRIVATE)
        val a = try { JSONArray(p.getString(SCHLUESSEL, "[]")) } catch (e: Exception) {
            logger.w { "Protokoll unlesbar: " + e.message }
            return emptyList()
        }
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            o.optLong("t") to o.optString("z")
        }
    }
}
