package coredevices.pebble.kiesel

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Die Pausen eines Trainings (Pause = 2, Weiter = 3).
 *
 * DIE UHR ZAEHLT IN AKTIVER ZEIT: Pulskurve, Saetze und Bahnen stehen bei
 * "Sekunde 1800 des Trainings", ohne die Pausen. Die Akte braucht aber die
 * Uhrzeit. Ohne Pausen landete die zweite Haelfte einer Fahrt mit
 * Kaffeepause mitten in der Pause - und der echte Puls der Pause flog beim
 * Entstoeren als Aussetzer hinaus. Hier steht, wann angehalten wurde, und
 * [wand] rechnet die aktive Sekunde in die Uhrzeit um.
 *
 * ZWEI QUELLEN. Die Boulder-Firmware schickt die Pausen mit der
 * Zusammenfassung ([ausUhr]): sekundengenau und vollstaendig - die gelten.
 * Aeltere Firmware schickt nur die Zustandsmeldungen; dann zaehlt deren
 * Zeit (die der Uhr, sonst die Ankunft). Verlor die Uhr eine Meldung (sie
 * gab nach sechs Anlaeufen auf, eine neue ersetzte die offene), verwirft
 * [stimmig] die Pausen, statt mit falschen zu rechnen - wenn es auffaellt: an
 * der Folge (zwei Pausen ohne Weiter, ein Weiter ohne Pause) oder an der
 * Summe gegen die Dauer der Uhr. Fehlen Pause und Weiter derselben Pause
 * beide und dazu die letzte Pause, faellt es nicht auf.
 */
internal object Trainingspausen {
    private const val TAG = "Kieselsport"
    private const val ORDNER = "pausen"

    /** Eine Pause in Epochensekunden, Wandzeit. */
    data class Pause(val von: Long, val bis: Long)

    /**
     * Die Pausen eines Trainings. [offen] ist die letzte Pause ohne Weiter:
     * gespeichert wird auf der Uhr nur aus der Pause, dort endet das Training.
     * [bruch]: in der Folge fehlt sichtbar eine Meldung - die Pausen taugen nicht.
     */
    data class Plan(val pausen: List<Pause>, val offen: Long?, val bruch: Boolean = false) {
        companion object {
            val LEER = Plan(emptyList(), null)
        }
    }

    /**
     * So weit darf die Summe von der Dauer der Uhr abweichen. Jede Meldung
     * kommt einige Sekunden spaet an (Funk, Anlaeufe alle 1,5 s); zehn
     * Pausen sammeln so eine Minute. Und so weit auseinander liegt hoechstens
     * eine Wiederholung derselben Meldung (sechs Anlaeufe) - was weiter weg
     * liegt, ist eine neue ([ausMeldungen]).
     */
    const val TOLERANZ_S = 90L

    /** Eine Datei lebt so lange - laenger wartet keine Zusammenfassung auf der Uhr. */
    private const val AUFHEBEN_TAGE = 30L
    /** So lange wartet eine Kurve auf die Pruefung durch die Zusammenfassung. */
    private const val WARTEN_S = 2 * 86_400L

    // --- Auf der Platte -------------------------------------------------------

    private fun ordner(context: Context): File = File(context.filesDir, ORDNER).apply { mkdirs() }
    private fun roh(context: Context, beginn: Long) = File(ordner(context), "$beginn.csv")
    private fun gepr(context: Context, beginn: Long) = File(ordner(context), "$beginn.ok")

    /** Eine Meldung festhalten: [pause] oder Weiter, zur Zeit [zeit]. */
    @Synchronized
    fun merke(context: Context, beginn: Long, pause: Boolean, zeit: Long) {
        try {
            val f = roh(context, beginn)
            // Beim ersten Eintrag eines Trainings die alten wegraeumen.
            if (!f.exists()) aufraeumen(context, zeit)
            f.appendText((if (pause) "p," else "w,") + zeit + "\n")
        } catch (e: Exception) {
            Log.w(TAG, "Pause: " + e.message)
        }
    }

    /** Das Training wurde verworfen - seine Pausen braucht niemand. */
    fun vergiss(context: Context, beginn: Long) {
        roh(context, beginn).delete()
        gepr(context, beginn).delete()
    }

    private fun aufraeumen(context: Context, jetzt: Long) {
        val grenze = (jetzt - AUFHEBEN_TAGE * 86_400L) * 1000
        ordner(context).listFiles()?.filter { it.lastModified() < grenze }?.forEach { it.delete() }
    }

    /** Was ankam, ungeprueft - fuer das Nachtragen ohne Zusammenfassung. */
    @Synchronized
    fun ungeprueft(context: Context, beginn: Long): Plan = try {
        val f = roh(context, beginn)
        if (f.exists()) ausMeldungen(meldungenAus(f.readLines())) else Plan.LEER
    } catch (e: Exception) {
        Log.w(TAG, "Pausen lesen: " + e.message)
        Plan.LEER
    }

    /**
     * Die Pausen gegen die Dauer der Zusammenfassung pruefen und das Ergebnis
     * ablegen - die Kurve, die erst danach kommt, rechnet mit genau diesem.
     */
    @Synchronized
    fun pruefe(context: Context, beginn: Long, dauer: Long, jetzt: Long): Plan {
        val plan = stimmig(ungeprueft(context, beginn), beginn, dauer, jetzt)
        try {
            if (roh(context, beginn).exists()) gepr(context, beginn).writeText(alsZeilen(plan))
        } catch (e: Exception) {
            Log.w(TAG, "Pausen ablegen: " + e.message)
        }
        return plan
    }

    /**
     * Den Plan der Uhr ablegen ([ausUhr]) - die Kurve, die nach der
     * Zusammenfassung kommt, rechnet mit ihm statt mit den Ankunftszeiten.
     */
    @Synchronized
    fun uebernimm(context: Context, beginn: Long, plan: Plan) {
        try {
            val f = gepr(context, beginn)
            // Wie beim ersten Eintrag eines Trainings: die alten wegraeumen.
            if (!f.exists()) aufraeumen(context, beginn)
            f.writeText(alsZeilen(plan))
        } catch (e: Exception) {
            Log.w(TAG, "Pausen ablegen: " + e.message)
        }
    }

    /** Der gepruefte Plan, oder null, solange die Zusammenfassung fehlt. */
    @Synchronized
    fun geprueft(context: Context, beginn: Long): Plan? = try {
        val f = gepr(context, beginn)
        if (f.exists()) ausZeilen(f.readLines()) else null
    } catch (e: Exception) {
        Log.w(TAG, "Pausen lesen: " + e.message)
        null
    }

    /**
     * Soll eine Kurve noch auf die Zusammenfassung warten? Nur, wenn es Pausen
     * gab und die Pruefung fehlt - und nicht ewig: kommt die Zusammenfassung
     * nie, steht die Kurve lieber ohne Pausen in der Akte als gar nicht.
     */
    fun wartet(context: Context, beginn: Long, jetzt: Long): Boolean {
        val f = roh(context, beginn)
        return f.exists() && !gepr(context, beginn).exists() && jetzt - f.lastModified() / 1000 < WARTEN_S
    }

    // --- Rechnung ---------------------------------------------------------------

    /** Die Fassung der Pausenliste, die die Uhr schickt (KS_PAUSEN_FASSUNG). */
    const val UHR_FASSUNG = 1

    /**
     * Die Pausenliste der Uhr (SP_PAUSEN) als Plan in Uhrzeit, oder null,
     * wenn sie fehlt oder nicht taugt - dann gelten die Zustandsmeldungen.
     *
     * AUFBAU: Fassung, Zahl aller Pausen, dann je Pause die aktive Sekunde,
     * bei der sie begann, und ihre Dauer - beide uint16 little-endian. Steht
     * die Zahl hoeher, als die Liste lang ist, fehlen Pausen: dann null.
     *
     * [ende] (SP_ENDE) ist der Beginn der letzten Pause, aus der gespeichert
     * wurde - die offene. Passt es nicht zu Beginn + Dauer + Pausen (die Uhr
     * wurde mitten im Training gestellt), gilt die Rechnung.
     */
    fun ausUhr(bytes: ByteArray?, anfang: Long, dauer: Long, ende: Long?): Plan? {
        if (bytes == null || bytes.size < 2 || bytes[0].toInt() != UHR_FASSUNG || (bytes.size - 2) % 4 != 0) return null
        val anzahl = (bytes.size - 2) / 4
        if ((bytes[1].toInt() and 0xFF) != anzahl) return null
        fun u16(i: Int): Long = ((bytes[i].toInt() and 0xFF) or ((bytes[i + 1].toInt() and 0xFF) shl 8)).toLong()
        val pausen = mutableListOf<Pause>()
        var verschoben = 0L
        var vorher = 0L
        for (k in 0 until anzahl) {
            val aktiv = u16(2 + 4 * k)
            val laenge = u16(4 + 4 * k)
            // Rueckwaerts oder hinter dem Ende: das ist keine Liste dieses Trainings.
            if (aktiv < vorher || aktiv > dauer) return null
            vorher = aktiv
            if (laenge == 0L) continue
            val von = anfang + aktiv + verschoben
            pausen += Pause(von, von + laenge)
            verschoben += laenge
        }
        val gerechnet = anfang + dauer + verschoben
        // Die Sekunden der Uhr sind Takte - ueber Stunden duerfen sie ein wenig abweichen.
        val offen = ende?.takeIf { kotlin.math.abs(it - gerechnet) <= TOLERANZ_S + dauer / 100 } ?: gerechnet
        return Plan(pausen, offen)
    }

    /** Die Zeilen der Datei: "p,<zeit>" oder "w,<zeit>"; Kaputtes faellt weg. */
    fun meldungenAus(zeilen: List<String>): List<Pair<Boolean, Long>> = zeilen.mapNotNull { z ->
        val t = z.split(',')
        if (t.size != 2 || t[0] !in setOf("p", "w")) return@mapNotNull null
        val zeit = t[1].trim().toLongOrNull() ?: return@mapNotNull null
        (t[0] == "p") to zeit
    }

    /**
     * Aus den Meldungen die Pausen. DOPPELTE ZAEHLEN NICHT: die Uhr wiederholt
     * eine Meldung, deren Bestaetigung sie nicht sah - eine zweite Pause kurz
     * nach der ersten und ein zweites Weiter kurz nach dem ersten aendern
     * nichts. Liegen sie weiter auseinander, als die Uhr es wiederholt
     * ([TOLERANZ_S]), fehlt dazwischen eine Meldung: aus p1 ... p2 ... w2
     * wuerde sonst eine einzige lange Pause, und alles danach rutschte um sie.
     */
    fun ausMeldungen(meldungen: List<Pair<Boolean, Long>>): Plan {
        val pausen = mutableListOf<Pause>()
        var offen: Long? = null
        var weiter: Long? = null
        var bruch = false
        for ((pause, zeit) in meldungen.sortedBy { it.second }) {
            val von = offen
            if (pause) {
                // Nach einem verlorenen Weiter beginnt hier eine neue Pause - die
                // juengste ist die, an der gespeichert wurde.
                if (von == null || zeit - von > TOLERANZ_S) offen = zeit
                if (von != null && zeit - von > TOLERANZ_S) bruch = true
            } else if (von != null) {
                if (zeit > von) pausen += Pause(von, zeit)
                offen = null
                weiter = zeit
            } else if (weiter == null || zeit - weiter > TOLERANZ_S) {
                // Ein Weiter ohne Pause, das keine Wiederholung ist: die Pause fehlt.
                bruch = true
            }
        }
        return Plan(pausen, offen, bruch)
    }

    /**
     * Passen die Pausen zur aktiven Dauer der Uhr? Dann liegt die letzte,
     * offene Pause genau dort, wo Beginn + Dauer + Pausen hinzeigen, und das
     * letzte Weiter davor. Fehlt eine Meldung, stimmt das nicht mehr: dann
     * lieber ohne Pausen rechnen. Die offene Pause bleibt, wenn sie nach der
     * aktiven Zeit liegt - an ihr hielt das GPS an, und dort endet die Spur.
     */
    fun stimmig(plan: Plan, anfang: Long, dauer: Long, jetzt: Long): Plan {
        val drin = plan.pausen.filter { it.von >= anfang }
        val ende = wand(drin, anfang, dauer)
        val offenGut = plan.offen?.takeIf { it >= anfang + dauer - TOLERANZ_S && it <= jetzt + TOLERANZ_S }
        val passt = !plan.bruch && drin.size == plan.pausen.size && ende <= jetzt + TOLERANZ_S &&
            // Nach dem letzten Weiter lief das Training noch - die aktive Zeit endet nicht davor.
            drin.all { it.bis <= ende + TOLERANZ_S } &&
            (plan.offen == null || kotlin.math.abs(plan.offen - ende) <= TOLERANZ_S)
        return Plan(if (passt) drin else emptyList(), offenGut)
    }

    /** Fuer das Nachtragen ohne Dauer der Uhr: bei einem Bruch keine Pausen, nur die letzte. */
    fun ohneBruch(plan: Plan): Plan = if (plan.bruch) Plan(emptyList(), plan.offen) else plan

    /**
     * Die Wandzeit zur aktiven Sekunde [aktiv]: jede Pause, die begann, bevor
     * so viel aktive Zeit vergangen war, schiebt sie um ihre Laenge nach hinten.
     */
    fun wand(pausen: List<Pause>, anfang: Long, aktiv: Long): Long {
        var verschoben = 0L
        for (p in pausen.sortedBy { it.von }) {
            // So viel aktive Zeit war vergangen, als diese Pause begann.
            val bisDahin = p.von - anfang - verschoben
            if (aktiv <= bisDahin) break
            verschoben += p.bis - p.von
        }
        return anfang + aktiv + verschoben
    }

    private fun alsZeilen(plan: Plan): String = buildString {
        plan.pausen.forEach { append("p,").append(it.von).append(',').append(it.bis).append('\n') }
        plan.offen?.let { append("o,").append(it).append('\n') }
    }

    private fun ausZeilen(zeilen: List<String>): Plan {
        val pausen = mutableListOf<Pause>()
        var offen: Long? = null
        zeilen.forEach { z ->
            val t = z.split(',')
            when {
                t.size == 3 && t[0] == "p" -> {
                    val von = t[1].toLongOrNull()
                    val bis = t[2].toLongOrNull()
                    if (von != null && bis != null && bis > von) pausen += Pause(von, bis)
                }
                t.size == 2 && t[0] == "o" -> offen = t[1].toLongOrNull()
            }
        }
        return Plan(pausen, offen)
    }
}
