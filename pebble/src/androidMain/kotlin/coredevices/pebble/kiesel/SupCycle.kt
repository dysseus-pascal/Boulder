package coredevices.pebble.kiesel

import android.content.Context
import android.util.Log
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import coredevices.pebble.R
import java.time.Instant
import java.time.LocalDate
import kotlin.uuid.Uuid

/**
 * SupCycle: was heute ansteht und was davon genommen ist - wie in
 * Kiesel-Helper (Aufgaben.supplemente, Supplemente).
 *
 * DIE AKTE KENNT KEIN "GENOMMEN". Was ihr am naechsten kommt, ist ein
 * Ernaehrungseintrag: er bekommt den Namen, aber keine Naehrstoffmengen,
 * denn die weiss SupCycle nicht. Die Quote des Tages (faellig und davon
 * genommen) steht deshalb im eigenen Speicher.
 */
internal object SupCycle {
    private const val TAG = "SupCycle"
    private const val HEUTE = "kiesel-supplemente"
    private const val QUOTEN = "kiesel-supplemente-quoten"

    val UUID: Uuid = Uuid.parse("33ce868a-2beb-4335-9421-4d741ed16eb3")

    /**
     * SupCycle meldet nach jeder Einnahme den Kalendertag (JJJJMMTT) und zwei
     * Bitmasken je Platz: was heute faellig ist und was abgehakt wurde. Die
     * Namen kommen seit SupCycle 0.10.0 mit, durch Zeilenumbruch getrennt,
     * auch die leeren - die Bitmasken zaehlen Plaetze, nicht Eintraege.
     */
    const val SC_TODAY = 10039
    const val SC_DUE = 10040
    const val SC_TAKEN = 10041
    const val SC_NAMES = 10044

    /** Ein Praeparat, das heute ansteht. */
    data class Eintrag(val name: String, val genommen: Boolean)

    data class Stand(val tag: LocalDate, val namen: List<String>, val faellig: Long, val genommen: Long) {
        /**
         * Nur was heute faellig ist, in der Reihenfolge des Plans. Ein
         * Praeparat in der Pausenwoche gehoert nicht auf die Liste - ein
         * "offen" daneben waere ein Vorwurf.
         */
        fun heute(platz: (Int) -> String): List<Eintrag> = (0 until 64)
            .filter { ((faellig shr it) and 1L) == 1L }
            .map { i -> Eintrag(namen.getOrNull(i)?.ifBlank { null } ?: platz(i + 1), ((genommen shr i) and 1L) == 1L) }
    }

    /** Welche Plaetze seit dem letzten Bericht neu abgehakt wurden - nur faellige. */
    fun neuGenommen(faellig: Long, genommen: Long, vorher: Long): List<Int> =
        (0 until 64).filter { ((genommen and faellig and vorher.inv()) shr it) and 1L == 1L }

    /** Der Stand von heute, oder nichts. Gestern ist hier nicht interessant. */
    fun heute(context: Context): Stand? {
        val p = context.getSharedPreferences(HEUTE, Context.MODE_PRIVATE)
        val tag = p.getString("tag", null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
        if (tag != LocalDate.now()) return null
        return Stand(tag, p.getString("namen", "")!!.split("\n"), p.getLong("faellig", 0), p.getLong("genommen", 0))
    }

    /** Die Quote je Tag (faellig, genommen) - fuer Verlauf und Trends. */
    fun quoten(context: Context): Map<LocalDate, Pair<Int, Int>> =
        context.getSharedPreferences(QUOTEN, Context.MODE_PRIVATE).all.mapNotNull { (k, v) ->
            val tag = runCatching { LocalDate.parse(k) }.getOrNull() ?: return@mapNotNull null
            val (f, g) = (v as? String)?.split(":")?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == 2 } ?: return@mapNotNull null
            tag to (f to g)
        }.toMap()

    suspend fun verarbeite(context: Context, n: KieselNachricht): String? {
        val ymd = n.zahlen[SC_TODAY] ?: return null
        val faellig = n.zahlen[SC_DUE] ?: return null
        val genommen = n.zahlen[SC_TAKEN] ?: 0L
        val tag = try {
            LocalDate.of((ymd / 10000).toInt(), ((ymd / 100) % 100).toInt(), (ymd % 100).toInt())
        } catch (e: Exception) {
            Log.w(TAG, "Unbrauchbares Datum $ymd")
            return null
        }
        if (!Riegel.neu(context, "supcycle", "$ymd:$faellig:$genommen")) return null

        // WAS NEU ABGEHAKT WURDE, geht in die Akte - je Praeparat ein Eintrag
        // mit Namen, zum Zeitpunkt des Hakens und nicht des naechsten Berichts.
        val p = context.getSharedPreferences(HEUTE, Context.MODE_PRIVATE)
        val bekannt = p.getString("namen", "")!!.split("\n")
        // Nur ueberschreiben, wenn welche kamen: eine aeltere Fassung von
        // SupCycle schickt keine, dann bleiben die zuletzt bekannten stehen.
        val namen = n.texte[SC_NAMES]?.split("\n") ?: bekannt
        val vorher = if (p.getString("tag", null) == tag.toString()) p.getLong("genommen", 0) else 0L
        val klient = Akte(context).bereit()
        for (platz in neuGenommen(faellig, genommen, vorher)) {
            val name = namen.getOrNull(platz)?.ifBlank { null } ?: context.getString(R.string.ks_s_praeparat_n, platz + 1)
            val jetzt = Instant.now()
            try {
                klient?.insertRecords(listOf(NutritionRecord(
                    startTime = jetzt,
                    startZoneOffset = versatz(jetzt),
                    endTime = jetzt.plusSeconds(60),
                    endZoneOffset = versatz(jetzt),
                    name = name,
                    // TAG PLUS PLATZ als Kennung: meldet SupCycle denselben
                    // Haken noch einmal, ersetzt die Akte den Eintrag.
                    metadata = Metadata.manualEntryWithId("supcycle-$tag-$platz", Device(type = Device.TYPE_PHONE)),
                )))
            } catch (e: Exception) {
                Log.w(TAG, "Praeparat nicht eingetragen: " + e.message)
            }
        }

        p.edit()
            .putString("tag", tag.toString())
            .putString("namen", namen.joinToString("\n"))
            .putLong("faellig", faellig)
            .putLong("genommen", genommen)
            .apply()
        // UND, nicht blosses Zaehlen: abgehakt bleibt abgehakt, auch wenn ein
        // Praeparat heute gar nicht dran waere. In der Quote hat es nichts verloren.
        val wieViele = java.lang.Long.bitCount(faellig)
        val davon = java.lang.Long.bitCount(genommen and faellig)
        context.getSharedPreferences(QUOTEN, Context.MODE_PRIVATE).edit().putString(tag.toString(), "$wieViele:$davon").apply()
        return context.getString(R.string.ks_s_davon_von, davon, wieViele)
    }
}
