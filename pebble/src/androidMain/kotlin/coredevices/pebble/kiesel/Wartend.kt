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

    private fun verzeichnis(context: Context, name: String): File = File(context.filesDir, name).apply { mkdirs() }

    /**
     * Die Nachricht ablegen; die Datei, oder null, wenn das nicht ging. Jede
     * Uhr-App hat ihren eigenen [ordner]. [ersetzt]: welche aelteren die
     * Nachricht ersetzt, weil sie ein ganzer Stand ist - einen aelteren
     * spaeter noch zu verarbeiten, drehte den Stand zurueck. Was sie nicht
     * ersetzt (etwa der Stand eines anderen Tages), bleibt liegen.
     */
    fun lege(context: Context, n: KieselNachricht, ordner: String = ORDNER, ersetzt: ((KieselNachricht) -> Boolean)? = null): File? = try {
        // Millisekunden und Zaehler, damit zwei Nachrichten derselben Sekunde nicht kollidieren.
        val f = File(verzeichnis(context, ordner), "${System.currentTimeMillis()}-${zaehler++}.json")
        f.writeText(json.encodeToString(KieselNachricht.serializer(), n))
        if (ersetzt != null) alle(context, ordner).filter { it != f && lies(it)?.let(ersetzt) == true }.forEach { it.delete() }
        f
    } catch (e: Exception) {
        Log.w(TAG, "Nachricht nicht gesichert: " + e.message)
        null
    }

    /**
     * Die Datei mit dem Rest einer Nachricht ueberschreiben - nur was noch
     * fehlt, kommt beim naechsten Start wieder dran.
     */
    fun ersetze(f: File, rest: KieselNachricht) {
        try {
            f.writeText(json.encodeToString(KieselNachricht.serializer(), rest))
        } catch (e: Exception) {
            Log.w(TAG, "Rest nicht gesichert: " + e.message)
        }
    }

    private var zaehler = 0

    fun lies(f: File): KieselNachricht? = try {
        json.decodeFromString(KieselNachricht.serializer(), f.readText())
    } catch (e: Exception) {
        Log.w(TAG, "Nachricht nicht lesbar, weg damit: " + e.message)
        f.delete()
        null
    }

    /**
     * Alle liegengebliebenen, aelteste zuerst. [hoechstensTage]: was laenger
     * liegt, ist verfallen und geht weg - ohne Grenze wuchse der Ordner, wenn
     * die Akte ein Recht dauerhaft verweigert, und jeder Start spielte alles ab.
     */
    fun alle(context: Context, ordner: String = ORDNER, hoechstensTage: Long? = null): List<File> {
        val dateien = verzeichnis(context, ordner).listFiles { f -> f.name.endsWith(".json") }.orEmpty().sortedBy { it.name }
        if (hoechstensTage == null) return dateien
        val (alt, frisch) = dateien.partition { verfallen(it.name, System.currentTimeMillis(), hoechstensTage) }
        alt.forEach { Log.w(TAG, "Verfallen, nicht eingetragen: ${it.name}"); it.delete() }
        return frisch
    }

    /** Wann die Datei [name] abgelegt wurde, in Epochensekunden - also wann die Nachricht ankam; null bei einer fremden. */
    fun abgelegt(name: String): Long? = name.substringBefore('-').toLongOrNull()?.div(1000)

    /** Ob die Datei [name] (Ablagezeit in Millisekunden vorn) aelter als [tage] ist; eine fremde nie. */
    fun verfallen(name: String, jetztMs: Long, tage: Long): Boolean =
        name.substringBefore('-').toLongOrNull()?.let { jetztMs - it > tage * 86_400_000L } ?: false
}
