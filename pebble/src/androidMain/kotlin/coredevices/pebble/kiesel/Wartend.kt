package coredevices.pebble.kiesel

import android.content.Context
import co.touchlab.kermit.Logger
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

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
    private val logger = Logger.withTag(TAG)
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
        schreibeAtomar(f, json.encodeToString(KieselNachricht.serializer(), n))
        if (ersetzt != null) alle(context, ordner).filter { it != f && lies(it)?.let(ersetzt) == true }.forEach { it.delete() }
        f
    } catch (e: Exception) {
        logger.w { "Nachricht nicht gesichert: " + e.message }
        null
    }

    /**
     * Die Datei mit dem Rest einer Nachricht ueberschreiben - nur was noch
     * fehlt, kommt beim naechsten Start wieder dran.
     */
    fun ersetze(f: File, rest: KieselNachricht) {
        try {
            schreibeAtomar(f, json.encodeToString(KieselNachricht.serializer(), rest))
        } catch (e: Exception) {
            logger.w { "Rest nicht gesichert, der alte Stand bleibt: " + e.message }
        }
    }

    /**
     * [text] nach [ziel], ganz oder gar nicht: erst in eine Hilfsdatei, auf
     * die Platte gebracht (fsync), dann umbenannt. Gleich danach geht das ACK
     * an die Uhr, und sie gibt ihre Kopie auf - bei einem Stromausfall in der
     * Schreibpause des Dateisystems laege sonst eine leere oder halbe Datei
     * da, die [lies] als unlesbar loescht. Wirft, wenn es nicht ging.
     */
    fun schreibeAtomar(ziel: File, text: String) {
        val hilf = File(ziel.parentFile, ziel.name + HILF)
        var fertig = false
        try {
            platte(hilf, text.toByteArray())
            if (!hilf.renameTo(ziel)) throw IOException("Umbenennen nach ${ziel.name} ging nicht")
            fertig = true
        } finally {
            if (!fertig) hilf.delete()
        }
        // Auch der neue Name muss auf die Platte, sonst zeigt das Verzeichnis nach dem Neustart den alten Stand.
        try {
            FileChannel.open(ziel.parentFile!!.toPath(), StandardOpenOption.READ).use { it.force(true) }
        } catch (e: Exception) {
            logger.i { "Verzeichnis nicht synchronisiert: " + e.message }
        }
    }

    /** Endung der Hilfsdatei - nicht .json, damit [alle] sie nie als Nachricht liest. */
    private const val HILF = ".neu"

    /**
     * Schreibt Bytes in eine Datei und bringt sie auf die Platte. NUR FUER
     * TESTS austauschbar: dort bricht eine volle Platte nach einigen Bytes ab.
     */
    @Volatile
    internal var platte: (File, ByteArray) -> Unit = { f, b ->
        FileOutputStream(f).use { aus ->
            aus.write(b)
            aus.fd.sync()
        }
    }

    private var zaehler = 0

    fun lies(f: File): KieselNachricht? = try {
        json.decodeFromString(KieselNachricht.serializer(), f.readText())
    } catch (e: Exception) {
        logger.w { "Nachricht nicht lesbar, weg damit: " + e.message }
        f.delete()
        null
    }

    /**
     * Alle liegengebliebenen, aelteste zuerst. [hoechstensTage]: was laenger
     * liegt, ist verfallen und geht weg - ohne Grenze wuchse der Ordner, wenn
     * die Akte ein Recht dauerhaft verweigert, und jeder Start spielte alles ab.
     */
    fun alle(context: Context, ordner: String = ORDNER, hoechstensTage: Long? = null): List<File> {
        val v = verzeichnis(context, ordner)
        // Eine Hilfsdatei, die laenger liegt, blieb von einem Absturz beim Schreiben: ihr Ziel hat
        // den alten Stand, oder es gab keine Bestaetigung, und die Uhr schickt die Nachricht wieder.
        v.listFiles { f -> f.name.endsWith(HILF) && System.currentTimeMillis() - f.lastModified() > 3_600_000L }.orEmpty()
            .forEach { logger.i { "Hilfsdatei weg: ${it.name}" }; it.delete() }
        val dateien = v.listFiles { f -> f.name.endsWith(".json") }.orEmpty().sortedBy { it.name }
        if (hoechstensTage == null) return dateien
        val (alt, frisch) = dateien.partition { verfallen(it.name, System.currentTimeMillis(), hoechstensTage) }
        alt.forEach { logger.w { "Verfallen, nicht eingetragen: ${it.name}" }; it.delete() }
        return frisch
    }

    /**
     * Steht das Scheitern dieser Datei schon im Protokoll? EINMAL JE MELDUNG, NICHT JE START - ein dauerhaft
     * verweigertes Recht fuellte es sonst. Im Namen vermerkt, damit auch ein Versuch nach einem Neustart zaehlt.
     */
    fun gemeldet(f: File): Boolean = f.name.endsWith(GEMELDET)

    /** Vermerkt [gemeldet] im Namen; die Datei unter ihrem neuen Namen - oder der alten, wenn das Umbenennen nicht ging. */
    fun alsGemeldet(f: File): File {
        if (gemeldet(f)) return f
        val neu = File(f.parentFile, f.name.removeSuffix(".json") + GEMELDET)
        if (f.renameTo(neu)) return neu
        logger.w { "Nicht als gemeldet vermerkt, die Zeile kommt beim naechsten Start wieder: ${f.name}" }
        return f
    }

    private const val GEMELDET = "-gemeldet.json"

    /** Wann die Datei [name] abgelegt wurde, in Epochensekunden - also wann die Nachricht ankam; null bei einer fremden. */
    fun abgelegt(name: String): Long? = name.substringBefore('-').toLongOrNull()?.div(1000)

    /** Ob die Datei [name] (Ablagezeit in Millisekunden vorn) aelter als [tage] ist; eine fremde nie. */
    fun verfallen(name: String, jetztMs: Long, tage: Long): Boolean =
        name.substringBefore('-').toLongOrNull()?.let { jetztMs - it > tage * 86_400_000L } ?: false
}
