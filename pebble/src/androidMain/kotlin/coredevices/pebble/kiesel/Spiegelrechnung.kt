package coredevices.pebble.kiesel

import boulder.austausch.Abschnitt
import boulder.austausch.HrvFenster
import boulder.austausch.Minute
import boulder.austausch.Nacht
import boulder.austausch.Tag
import io.rebble.libpebblecommon.database.entity.HealthDataEntity
import io.rebble.libpebblecommon.database.entity.NachtAbschnittEntity
import io.rebble.libpebblecommon.database.entity.NachtEntity
import io.rebble.libpebblecommon.database.entity.NachtHrvEntity
import java.net.URI
import java.security.MessageDigest
import java.time.LocalDate

/**
 * Die Rechnung des Spiegels ([Spiegel]) ohne Android: welche Adresse gilt, was seit dem letzten
 * Senden neu ist und wie die Daten der App ins Austauschformat kommen.
 *
 * GESENDET WIRD NUR, WAS SICH GEAENDERT HAT: je Tag, Nacht und Training ein Abdruck der Werte.
 * Sonst gingen alle 15 Minuten ein Jahr Tage und alle Naechte ueber das Mobilnetz.
 */
internal object Spiegelrechnung {
    /** Nach so vielen Tagen aendert sich eine Nacht nicht mehr; spaeter schickt die Uhr nichts mehr dazu. */
    const val NACHT_RUHE_TAGE = 7L

    /** Minuten je Lieferung: ein Tag, gut 130 KB. */
    const val MINUTEN_JE_LIEFERUNG = 1440

    /** So weit zurueck gehen Minuten noch einmal mit: was die Uhr nach dem letzten Senden noch nachreichte. */
    const val MINUTEN_RUECKBLICK_S = 900L

    /**
     * So weit zurueck vergleicht ein gewoehnlicher Lauf Trainings - dort aendern sie sich noch (ein
     * spaet bestaetigtes, eine nachgetragene Kurve). Weiter zurueck nur der gruendliche ([GRUENDLICH_S]).
     */
    const val TRAININGS_KURZ_TAGE = 3L

    /**
     * So oft vergleicht ein Lauf alles: Trainings des ganzen Fensters samt Puls und Routen. Jeder
     * Lauf - 100 bis 150 am Tag - laese sonst Dutzende Trainings, nur um festzustellen, dass gleich blieb.
     */
    const val GRUENDLICH_S = 86_400L

    /**
     * Die Adresse des Servers ohne Schraegstrich am Ende; null, wenn sie nicht taugt. NUR HTTPS:
     * das Sync-Token und die Gesundheitsdaten gingen sonst lesbar durchs Netz. Ohne Pfad, Nutzer,
     * Abfrage und Anker, wie im LIESMICH des Servers.
     */
    fun adresse(text: String): String? {
        val t = text.trim().removeSuffix("/")
        val u = versuche("Spiegel", "Keine Adresse") { URI(t) } ?: return null
        val gut = u.scheme == "https" && !u.host.isNullOrEmpty() && u.rawUserInfo == null &&
            u.rawPath.isNullOrEmpty() && u.rawQuery == null && u.rawFragment == null
        return if (gut) t else null
    }

    /** Ein Token taugt ab 32 Zeichen, wie der Server es verlangt, und ohne Leerraum mittendrin. */
    fun token(text: String): String? = text.trim().takeIf { it.length >= 32 && it.none(Char::isWhitespace) }

    /** Gleich, solange sich [text] nicht aendert: die ersten 8 Bytes von SHA-256, als Hex. */
    fun abdruck(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).take(8).joinToString("") { Integer.toHexString(it.toInt() and 0xff).padStart(2, '0') }

    /**
     * Ein Tag, wie er gesendet wuerde; die Reihenfolge der Werte zaehlt nicht. Ohne [Tag.entfernt]
     * derselbe Abdruck wie bis 0.55.0, als es nur die Werte gab.
     */
    fun abdruck(t: Tag): String {
        val werte = t.werte.toSortedMap().entries.joinToString(";") { "${it.key}=${it.value}" }
        return abdruck(if (t.entfernt.isEmpty()) werte else werte + "|-" + t.entfernt.sorted().joinToString(","))
    }

    /** Die Tage, die neu oder anders sind als beim letzten Senden ([gesendet]: Datum -> Abdruck). */
    fun neueTage(tage: List<Tag>, gesendet: Map<String, String>): List<Tag> = tage.filter { gesendet[it.datum] != abdruck(it) }

    /**
     * Die Tage zum Senden, aufsteigend. Der Server fuehrt sie mit seinen zusammen ([Tag]): was hier
     * fehlt, weil die Akte gerade nicht antwortet oder der Tag aus ihrem Fenster fiel, bleibt dort.
     * Nur die ganz gelesenen Spalten der Akte ([Trendstand.akteGanz]) nennt ein Tag in ihrem
     * Fenster als entfernt, wenn sie ihm fehlen - so verschwindet auch ein geloeschtes Glas.
     */
    fun tage(stand: Trendstand, heute: LocalDate): List<Tag> {
        val werte = jeTag(stand.eigen)
        val ab = heute.minusDays(Trenddaten.TAGE)
        val fenster = if (stand.akteGanz.isEmpty()) emptyList() else generateSequence(ab) { it.plusDays(1) }.takeWhile { it <= heute }.toList()
        return (werte.keys + fenster).toSortedSet().map { tag ->
            val w = werte[tag].orEmpty()
            val imFenster = tag >= ab && tag <= heute
            Tag(tag.toString(), w, if (imFenster) stand.akteGanz.filter { it !in w }.sorted() else emptyList())
        }.filter { it.werte.isNotEmpty() || it.entfernt.isNotEmpty() }
    }

    /** Die Reihen der Trends je Tag, wie die Sicherung sie schreibt. */
    fun jeTag(reihen: Reihen): Map<LocalDate, Map<String, Double>> =
        reihen.flatMap { (spalte, l) -> l.map { (tag, w) -> Triple(tag, spalte, w) } }
            .groupBy { it.first }.mapValues { (_, l) -> l.associate { it.second to it.third } }

    /** Eine Nacht der Firmware im Austauschformat. */
    fun nacht(e: NachtEntity, abschnitte: List<NachtAbschnittEntity>, hrv: List<NachtHrvEntity>): Nacht = Nacht(
        beginn = e.beginnUtc,
        utcZuLokal = e.utcZuLokal,
        einschlafen = e.einschlafen,
        aufwachen = e.aufwachen,
        schlafMinuten = e.schlafMinuten,
        ruhepuls = e.ruhepuls,
        hrv = e.hrv,
        messstufe = e.messstufe,
        akkuBeginn = e.akkuBeginn,
        akkuEnde = e.akkuEnde,
        abschnitte = abschnitte.sortedBy { it.von }.map { Abschnitt(it.von, it.bis, it.phase) },
        hrvFenster = hrv.sortedBy { it.minute }.map { HrvFenster(it.minute, it.rmssd, it.puls) },
    )

    /** Eine Nacht samt Abschnitten und HRV-Fenstern - so, wie sie gesendet wuerde. */
    fun abdruck(n: Nacht): String = abdruck(n.toString())

    /**
     * Welche Naechte gelesen werden muessen, bevor man weiss, ob sie neu sind: die nie gesendeten
     * und die der letzten [NACHT_RUHE_TAGE] Tage - an denen kann die Uhr noch HRV-Fenster
     * nachreichen. Aeltere gesendete aendern sich nicht mehr.
     */
    fun zuPruefen(naechte: List<NachtEntity>, gesendet: Map<String, String>, jetzt: Long): List<NachtEntity> =
        naechte.filter { it.beginnUtc.toString() !in gesendet || it.beginnUtc >= jetzt - NACHT_RUHE_TAGE * 86_400L }

    fun minute(d: HealthDataEntity): Minute = Minute(d.timestamp, d.steps, d.intensity, d.heartRate, d.orientation)

    /**
     * Was der Spiegel je gesendetem Training behaelt, als "abdruck@beginn", mit "@b" fuer eines aus
     * Boulders eigenem Paket - die liest die Akte auch im Hintergrund ohne Sonderrecht.
     */
    data class GesendetesTraining(val abdruck: String, val beginn: Long, val eigenesPaket: Boolean) {
        override fun toString() = "$abdruck@$beginn" + if (eigenesPaket) "@b" else ""

        companion object {
            /** Auch die Form von 0.55.0 ohne "@b": dann gilt das Training als fremd. */
            fun aus(text: String): GesendetesTraining {
                val teile = text.split('@')
                return GesendetesTraining(teile[0], teile.getOrNull(1)?.toLongOrNull() ?: 0L, teile.getOrNull(2) == "b")
            }
        }
    }

    /**
     * Die gesendeten Trainings ([gesendet]: Kennung -> [GesendetesTraining]), die ein Lauf nicht
     * mehr fand ([gefunden]): in der Akte geloescht oder einem Doppel gewichen. Nur die ab [ab] - davor
     * las er nicht -, und ohne [alleHerkuenfte] nur Boulders eigene: fremde sah er dann gar nicht.
     */
    fun entfernteTrainings(gesendet: Map<String, String>, gefunden: Set<String>, ab: Long, alleHerkuenfte: Boolean): List<String> =
        gesendet.filter { (kennung, text) ->
            val g = GesendetesTraining.aus(text)
            kennung !in gefunden && g.beginn >= ab && (alleHerkuenfte || g.eigenesPaket)
        }.keys.sorted()

    /**
     * Ab wann Minuten zu senden sind: nach dem letzten Senden ([bis]) mit [MINUTEN_RUECKBLICK_S]
     * Rueckblick, beim ersten Mal ab der aeltesten Minute im Speicher ([aelteste]).
     */
    fun minutenAb(bis: Long?, aelteste: Long): Long = if (bis == null) aelteste else maxOf(aelteste, bis - MINUTEN_RUECKBLICK_S)
}
