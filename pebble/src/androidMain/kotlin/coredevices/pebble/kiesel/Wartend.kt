package coredevices.pebble.kiesel

import android.content.Context
import android.util.Log
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Nachrichten der Uhr, die noch nicht verarbeitet sind - auf der Platte.
 *
 * ERST SICHERN, DANN BESTAETIGEN. Die Uhr loescht ihre Kopie eines Trainings,
 * sobald die Bestaetigung ankommt, und die kommt sofort - das Eintragen in die
 * Akte dauert. Scheiterte es (keine Erlaubnis, Akte nicht da, App beendet),
 * war das Training weg; uebrig blieb die Spur unter "Not logged". Jetzt liegt
 * jede Nachricht als Datei hier, bis sie verarbeitet ist, und beim naechsten
 * Start der Sitzung kommt sie wieder an die Reihe.
 */
internal object Wartend {
    private const val TAG = "Kieselsport"
    private const val ORDNER = "kieselsport-wartend"
    private val json = Json { ignoreUnknownKeys = true }

    private fun ordner(context: Context): File = File(context.filesDir, ORDNER).apply { mkdirs() }

    /** Die Nachricht ablegen; die Datei, oder null, wenn das nicht ging. */
    fun lege(context: Context, n: KieselNachricht): File? = try {
        // Millisekunden und Zaehler, damit zwei Nachrichten derselben Sekunde nicht kollidieren.
        val f = File(ordner(context), "${System.currentTimeMillis()}-${zaehler++}.json")
        f.writeText(json.encodeToString(KieselNachricht.serializer(), n))
        f
    } catch (e: Exception) {
        Log.w(TAG, "Nachricht nicht gesichert: " + e.message)
        null
    }

    private var zaehler = 0

    fun lies(f: File): KieselNachricht? = try {
        json.decodeFromString(KieselNachricht.serializer(), f.readText())
    } catch (e: Exception) {
        Log.w(TAG, "Nachricht nicht lesbar, weg damit: " + e.message)
        f.delete()
        null
    }

    /** Alle liegengebliebenen, aelteste zuerst. */
    fun alle(context: Context): List<File> =
        ordner(context).listFiles { f -> f.name.endsWith(".json") }.orEmpty().sortedBy { it.name }
}
