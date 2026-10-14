package coredevices.pebble.kiesel

import android.content.Context
import android.location.Location
import co.touchlab.kermit.Logger
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File

/**
 * Die aufgezeichnete Strecke eines Trainings.
 *
 * DIE UHR HAT KEIN GPS, das Telefon schon. Kieselsport meldet, wann ein
 * Training beginnt und endet; dazwischen schreibt [SpurDienst] mit, wo man
 * war. Das ist derselbe Weg, den auch Uhren ohne eigenen Empfänger gehen -
 * und er kostet keinen Sensor, den es nicht gibt.
 *
 * ALS DATEI UND NICHT IN DER TABELLE. Eine Stunde Laufen sind bei einem Punkt
 * alle drei Sekunden gut tausend Zeilen; die Tagestabelle traegt je Tag EINE.
 * Eine Datei je Training ist ausserdem das, was man exportiert, ohne etwas
 * umzurechnen.
 *
 * DIE PUNKTE WERDEN SOFORT GESCHRIEBEN, nicht am Ende. Ein Dienst, den das
 * System waehrend eines Laufs beendet, nimmt sonst die ganze Strecke mit.
 */
internal object Spur {
    private const val TAG = "Kieselsport"
    private val logger = Logger.withTag(TAG)

    /** Ein Punkt: Zeit, Ort, Höhe. Mehr braucht keine Karte. */
    data class Punkt(
        val zeit: Long,
        val lat: Double,
        val lon: Double,
        val hoehe: Double?,
        val genauigkeit: Float,
    )

    private const val ORDNER = "spuren"

    /**
     * Genauer als das ist kein GPS am Handgelenk.
     *
     * Punkte mit einer gemeldeten Unsicherheit ueber fuenfzig Metern kommen
     * aus dem Mobilfunknetz, nicht von Satelliten. Sie in die Strecke zu
     * nehmen hiesse, Spruenge quer ueber die Stadt zu zeichnen.
     */
    const val GENAUIGKEIT_MAX = 50f

    private fun datei(context: Context, beginn: Long): File {
        val ordner = File(context.filesDir, ORDNER)
        if (!ordner.exists()) ordner.mkdirs()
        return File(ordner, "spur-$beginn.jsonl")
    }

    /** Einen Punkt anhängen. Eine Zeile je Punkt, damit nichts neu zu schreiben ist. */
    fun haengeAn(context: Context, beginn: Long, ort: Location) = haengeAn(context, beginn, listOf(ort))

    /** Mehrere Punkte in EINEM Schreibvorgang: das gebuendelte GPS (SpurDienst) liefert bis zu 20 je Minute. */
    fun haengeAn(context: Context, beginn: Long, orte: List<Location>) {
        try {
            val text = buildString {
                for (ort in orte) {
                    if (ort.accuracy > GENAUIGKEIT_MAX) continue
                    append(buildJsonObject {
                        put("t", ort.time / 1000)
                        put("lat", ort.latitude)
                        put("lon", ort.longitude)
                        if (ort.hasAltitude()) put("h", ort.altitude)
                        put("g", ort.accuracy)
                    }.toString()).append('\n')
                }
            }
            if (text.isNotEmpty()) datei(context, beginn).appendText(text)
        } catch (e: Exception) {
            logger.w { "Spurpunkt: " + e.message }
        }
    }

    fun lies(context: Context, beginn: Long): List<Punkt> = liesOderNull(context, beginn).orEmpty()

    /** Wie [lies], aber null, wenn die Datei nicht zu lesen war - so merkt sich der Laengenspeicher keinen Lesefehler als leere Spur. */
    fun liesOderNull(context: Context, beginn: Long): List<Punkt>? {
        val d = datei(context, beginn)
        if (!d.exists()) return emptyList()
        return try {
            // Eine Zeile je Lesen: so zaehlt logcat, wie oft eine Seite die Spuren noch liest.
            d.bufferedReader().useLines { z -> z.mapNotNull(::zeile).toList() }.also { logger.i { "Spur gelesen: ${d.name}, ${it.size} Punkte" } }
        } catch (e: Exception) {
            logger.w { "Spur lesen: " + e.message }
            null
        }
    }

    /** Wie gross die Datei ist, -1 ohne - fuer den Laengenspeicher der Liste (TrainingsDaten). */
    fun groesse(context: Context, beginn: Long): Long =
        datei(context, beginn).let { if (it.exists()) it.length() else -1L }

    /** Was eine Spur der Liste gibt - TrainingsDaten merkt es sich je Datei und Fenster. */
    data class Mass(val anzahl: Int, val meter: Double, val letzte: Long)

    /** DIESELBE RECHNUNG WIE VORHER IN DER LISTE: die Laenge erst ab zwei Punkten. */
    fun mass(punkte: List<Punkt>): Mass =
        Mass(punkte.size, if (punkte.size >= 2) laenge(punkte) else 0.0, punkte.lastOrNull()?.zeit ?: 0L)

    /**
     * Die Laenge wie TrainingsDaten.punkte waehlt: die Spur ab zwei Punkten, sonst die Route; null darunter.
     * [route] ALS LAMBDA: sie umzurechnen kostet bei 40 GPS-Stunden ~48 000 Punkte je Oeffnen - nur ohne Spur.
     */
    fun laengeAus(spur: Mass?, route: () -> List<Punkt>): Double? =
        if (spur != null && spur.anzahl >= 2) spur.meter else route().takeIf { it.size >= 2 }?.let(::laenge)

    /**
     * Die Fassung der Rechnung hinter einem gemerkten Mass. ERHOEHEN, wenn sich
     * [zeile], [inSitzung], [weg] oder ihre Grenzen aendern: sonst zeigten Liste,
     * Zeitstrahl und Widget bis zu 91 Tage die alten Meter, das Detail die neuen.
     * SpurzeileTest haelt einen Fingerabdruck der Rechnung fest und erinnert daran.
     */
    const val RECHNUNG = 1

    /**
     * Wofuer ein gemerktes Mass gilt: Datei, Fenster (-1 = ganz), Groesse, [RECHNUNG].
     * Eine Spur waechst nur durch Anhaengen - gleiche Groesse heisst gleicher Inhalt.
     * Der Beginn bleibt vorn: TrainingsDaten raeumt danach ab.
     */
    fun stempel(beginn: Long, von: Long, bis: Long, groesse: Long, rechnung: Int = RECHNUNG): String =
        "$beginn;$von;$bis;$groesse;r$rechnung"

    /** Das Mass als Text hinter seinem Stempel. Double.toString ist verlustfrei: dieselben Meter kommen zurueck. */
    fun massText(stempel: String, m: Mass): String = "$stempel;${m.anzahl};${m.letzte};${m.meter}"

    /** Das gemerkte Mass, wenn es zu [stempel] gehoert, sonst null. */
    fun massAus(text: String?, stempel: String): Mass? {
        if (text == null || !text.startsWith("$stempel;")) return null
        val t = text.substring(stempel.length + 1).split(';')
        if (t.size != 3) return null
        return Mass(t[0].toIntOrNull() ?: return null, t[2].toDoubleOrNull() ?: return null, t[1].toLongOrNull() ?: return null)
    }

    /**
     * Die Punkte aus den Zeilen der Datei. Eine kaputte Zeile (die App starb
     * beim Schreiben) kostet nur sie selbst, nicht die ganze Spur; eine Zeile
     * ohne Zeit oder Ort ergibt keinen Punkt bei (0, NaN).
     */
    fun ausZeilen(zeilen: List<String>): List<Punkt> = zeilen.mapNotNull(::zeile)

    /**
     * Eine Zeile als Punkt, oder null. OHNE JSON-BAUM: kotlinx legte je Zeile
     * JsonObject, Map und fuenf Literale an (JVM: 2,5 KB Muell, hier 1,3 KB und
     * ein Fuenftel schneller). Das Ergebnis muss dasselbe bleiben, auch bei
     * kaputten Zeilen - SpurzeileTest vergleicht beide.
     */
    fun zeile(text: String): Punkt? {
        val l = Zeilenleser(text)
        l.leer()
        // Leer oder kein Objekt: kotlinx warf beim .jsonObject, und die Zeile fiel weg.
        if (l.i >= text.length || text[l.i] != '{') return null
        val w = arrayOfNulls<Any>(FELDER.size)
        if (!l.objekt { k, v -> if (k >= 0) w[k] = v }) return null
        l.leer()
        if (l.i != text.length) return null
        // Ein Objekt statt einer Zahl in einem der Felder: kotlinx warf beim
        // Lesen ausserhalb der Zeile, und die ganze Datei war leer. So bleibt es.
        require(w.none { it === Zeilenleser.VERSCHACHTELT }) { "Spurzeile: Feld ist kein Wert" }
        val p = Punkt(
            (w[0] as String?)?.let(::alsLong) ?: 0L,
            (w[1] as String?)?.toDoubleOrNull() ?: Double.NaN,
            (w[2] as String?)?.toDoubleOrNull() ?: Double.NaN,
            (w[3] as String?)?.toDoubleOrNull(),
            (w[4] as String?)?.toDoubleOrNull()?.toFloat() ?: Float.NaN,
        )
        return p.takeIf { it.zeit > 0 && !it.lat.isNaN() && !it.lon.isNaN() }
    }

    /** Die Felder einer Zeile, in der Reihenfolge von Punkt. */
    val FELDER = listOf("t", "lat", "lon", "h", "g")

    /** Wie kotlinx' longOrNull; nur reine Ziffern (bis 18, ohne Ueberlauf) nehmen den kurzen Weg. */
    private fun alsLong(text: String): Long? =
        if (text.length in 1..18 && text.all { it in '0'..'9' }) text.toLong() else JsonPrimitive(text).longOrNull

    fun vorhanden(context: Context, beginn: Long): Boolean = datei(context, beginn).exists()

    /**
     * Die Punkte, die in die Sitzung fallen: [anfang, ende).
     *
     * EIN FENSTER FUER ALLES: die Akte nimmt nur Routenpunkte darin an. Rechneten
     * Distanz, Hoehe und Tempo ueber die ganze Datei (der erste Fix kann aelter
     * sein als der Start), stuende neben der Route eine laengere Strecke als ihre eigene.
     */
    fun inSitzung(punkte: List<Punkt>, anfang: java.time.Instant, ende: java.time.Instant): List<Punkt> =
        punkte.filter { it.zeit >= anfang.epochSecond && it.zeit < ende.epochSecond }

    /**
     * Die Datei, wie sie ist - fuer die Sicherung.
     *
     * UNVERAENDERT UND NICHT UMGERECHNET: was gesichert und zurueckgeholt
     * wird, muss Zeichen fuer Zeichen dasselbe sein. Ein Umweg ueber Punkte
     * und zurueck verlore Genauigkeit an einer Stelle, an der es keinen
     * Grund dafuer gibt.
     */
    fun roh(context: Context, beginn: Long): String? = try {
        val d = datei(context, beginn)
        if (d.exists()) d.readText() else null
    } catch (e: Exception) {
        logger.w { "Spur lesen: " + e.message }
        null
    }

    /** Eine zurueckgeholte Spur ablegen - nur, wenn hier keine liegt. */
    fun schreibeRoh(context: Context, beginn: Long, inhalt: String): Boolean = try {
        val d = datei(context, beginn)
        if (d.exists()) false else { d.writeText(inhalt); true }
    } catch (e: Exception) {
        logger.w { "Spur schreiben: " + e.message }
        false
    }

    /** Welche Spuren es gibt, jüngste zuerst. */
    fun alle(context: Context): List<Long> {
        val ordner = File(context.filesDir, ORDNER)
        if (!ordner.exists()) return emptyList()
        return ordner.listFiles().orEmpty()
            .mapNotNull { it.name.removePrefix("spur-").removeSuffix(".jsonl").toLongOrNull() }
            .sortedDescending()
    }

    /**
     * Die Streckenlänge in Metern.
     *
     * GERECHNET, NICHT VOM SENSOR. Jede Strecke zwischen zwei Punkten einzeln,
     * aufsummiert. Sprünge über [SPRUNG_MAX] werden übersprungen: ein
     * verlorener und wiedergefundener Empfang sieht sonst aus wie zweihundert
     * Meter in drei Sekunden.
     */
    const val SPRUNG_MAX = 200.0

    fun laenge(punkte: List<Punkt>): Double = weg(punkte).lastOrNull { !it.isNaN() } ?: 0.0

    /**
     * Der zurueckgelegte Weg bis zu jedem Punkt.
     *
     * NUR WAS UEBER DIE UNGENAUIGKEIT HINAUSGEHT, ZAEHLT. Im Stehen zittert der
     * Ort um die gemeldete Genauigkeit; alle drei Sekunden zwanzig Meter
     * Zittern ergaben aus neun Minuten Warten fast sechs Kilometer. Gemessen
     * wird darum vom letzten gezaehlten Punkt aus, und erst ein Abstand ueber
     * der Genauigkeit beider Punkte gilt als Bewegung.
     *
     * EIN AUSREISSER IST KEIN WEG. Das GPS setzt einen Punkt mal vierzig
     * Meter neben die Fahrt und den naechsten wieder auf sie; ohne Pruefung
     * zaehlte das achtzig Meter in drei Sekunden und war die "Spitze" des
     * Tages. Ist der Umweg ueber einen Punkt um mehr als das Doppelte der
     * Genauigkeit laenger als der direkte Weg zum naechsten, UND kaeme man
     * nur mit ueber [AUSREISSER_MS] dorthin, faellt er weg: NaN im Ergebnis,
     * damit auch die Karte ihn nicht zeichnet. Die Geschwindigkeit trennt ihn
     * von der Kehre auf derselben Strasse - die sieht gleich aus, aber
     * niemand wendet mit zwanzig Sachen in einem Messtakt.
     */
    const val AUSREISSER_MS = 6.0

    fun weg(punkte: List<Punkt>): DoubleArray {
        val weg = DoubleArray(punkte.size)
        var anker = 0
        var letzter = 0.0
        for (i in 1 until punkte.size) {
            weg[i] = letzter
            if (abstand(punkte[i - 1], punkte[i]) > SPRUNG_MAX) {
                anker = i
                continue
            }
            val d = abstand(punkte[anker], punkte[i])
            val schwelle = genauigkeit(punkte[anker], punkte[i])
            if (d < schwelle) continue
            val naechster = punkte.getOrNull(i + 1)
            val dt = (punkte[i].zeit - punkte[anker].zeit).coerceAtLeast(1)
            if (naechster != null && abstand(punkte[i], naechster) <= SPRUNG_MAX && d / dt > AUSREISSER_MS &&
                d + abstand(punkte[i], naechster) - abstand(punkte[anker], naechster) > 2 * schwelle.coerceAtLeast(5f)
            ) {
                weg[i] = Double.NaN
                continue
            }
            letzter += d
            weg[i] = letzter
            anker = i
        }
        return weg
    }

    /** Die Ungenauigkeit zweier Punkte zusammen; ohne gemeldete (NaN, aeltere Spuren) null. */
    private fun genauigkeit(a: Punkt, b: Punkt): Float =
        maxOf(a.genauigkeit, b.genauigkeit).takeUnless { it.isNaN() } ?: 0f

    /**
     * Abstand zweier Punkte nach der Haversine-Formel.
     *
     * Nicht Location.distanceTo: das braucht Location-Objekte, und die
     * entstehen nur mit Android. So bleibt die Rechnung ohne Telefon
     * pruefbar - siehe SpurTest.
     */
    fun abstand(a: Punkt, b: Punkt): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val h = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return 2 * r * Math.asin(Math.sqrt(h.coerceIn(0.0, 1.0)))
    }

    /**
     * So viel muss es aufwaerts gehen, bevor ein Anstieg zaehlt.
     *
     * MIT EINER SCHWELLE, sonst zählt das Rauschen. GPS-Höhen schwanken um
     * mehrere Meter im Stehen; ohne Schwelle sammelte ein Spaziergang in der
     * Ebene hundert Höhenmeter. Gerechnet wird der Aufstieg in
     * [Trainingsanalyse.aufstiegBis], auf der geglaetteten Hoehe.
     */
    const val STEIGUNG_MIN = 3.0
}

/**
 * Liest eine JSON-Zeile so streng wie kotlinx (ohne isLenient) und gibt die
 * Felder der obersten Ebene als Text, wie kotlinx' content. Was kotlinx
 * ablehnt, lehnt er auch ab - sonst haette eine kaputte Zeile einen Punkt.
 */
private class Zeilenleser(private val s: String) {
    var i = 0

    /** Nur die vier Leerzeichen von JSON, wie kotlinx. */
    fun leer() {
        while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++
    }

    /** Ein Objekt ab "{"; [nimm] bekommt jedes Feld (Nummer in Spur.FELDER, -1 fremd). false, wenn es kaputt ist. */
    fun objekt(nimm: ((Int, Any) -> Unit)?): Boolean {
        i++
        leer()
        if (i < s.length && s[i] == '}') { i++; return true }
        while (true) {
            leer()
            val k = feld()
            if (k == KAPUTT) return false
            leer()
            if (i >= s.length || s[i] != ':') return false
            i++
            leer()
            val w = wert() ?: return false
            nimm?.invoke(k, w)
            leer()
            if (i >= s.length) return false
            when (s[i]) {
                ',' -> i++
                '}' -> { i++; return true }
                else -> return false
            }
        }
    }

    private fun liste(): Boolean {
        i++
        leer()
        if (i < s.length && s[i] == ']') { i++; return true }
        while (true) {
            leer()
            wert() ?: return false
            leer()
            if (i >= s.length) return false
            when (s[i]) {
                ',' -> i++
                ']' -> { i++; return true }
                else -> return false
            }
        }
    }

    /** Text, Wort (Zahl, true, null) oder [VERSCHACHTELT]; null, wenn kaputt. */
    private fun wert(): Any? = when {
        i >= s.length -> null
        s[i] == '"' -> text()
        s[i] == '{' -> if (objekt(null)) VERSCHACHTELT else null
        s[i] == '[' -> if (liste()) VERSCHACHTELT else null
        else -> wort()
    }

    /** Ein Wort ohne Anfuehrungszeichen bis zum naechsten Zeichen mit Bedeutung - wie kotlinx' consumeStringLenient. */
    private fun wort(): String? {
        val start = i
        while (i < s.length && !grenze(s[i])) i++
        return if (i == start) null else s.substring(start, i)
    }

    /**
     * Wo ein Wort endet. AUCH AN JEDEM STEUERZEICHEN bis 0x20: kotlinx bricht dort ab
     * (TC_INVALID) und verwirft die Zeile. Sonst wurde aus "1.5" und einem Nullbyte nach
     * einem Absturz ueber toDoubleOrNull (schneidet <= 0x20 ab) wieder ein Punkt.
     */
    private fun grenze(c: Char) = c <= ' ' || when (c) {
        ',', ':', '{', '}', '[', ']', '"', '\\' -> true
        else -> false
    }

    /** Ein Schluessel als Nummer in Spur.FELDER, ohne Text anzulegen; mit Escape ueber [text]. */
    private fun feld(): Int {
        if (i >= s.length || s[i] != '"') return KAPUTT
        var e = i + 1
        while (e < s.length && s[e] != '"' && s[e] != '\\') e++
        if (e < s.length && s[e] == '"') {
            var n = Spur.FELDER.size - 1
            while (n >= 0 && !(FELDER[n].length == e - i - 1 && s.regionMatches(i + 1, FELDER[n], 0, FELDER[n].length))) n--
            i = e + 1
            return n
        }
        return text()?.let { Spur.FELDER.indexOf(it) } ?: KAPUTT
    }

    /** Ein Text in Anfuehrungszeichen, die Escapes aufgeloest. */
    private fun text(): String? {
        if (i >= s.length || s[i] != '"') return null
        val start = ++i
        // Der schnelle Weg: Boulders Zeilen haben keine Escapes.
        while (i < s.length && s[i] != '"' && s[i] != '\\') i++
        if (i >= s.length) return null
        if (s[i] == '"') return s.substring(start, i++)
        val b = StringBuilder().append(s, start, i)
        while (i < s.length) {
            val c = s[i++]
            when {
                c == '"' -> return b.toString()
                c != '\\' -> b.append(c)
                i >= s.length -> return null
                else -> when (val e = s[i++]) {
                    '"', '\\', '/' -> b.append(e)
                    'b' -> b.append('\b')
                    'f' -> b.append('\u000C')
                    'n' -> b.append('\n')
                    'r' -> b.append('\r')
                    't' -> b.append('\t')
                    'u' -> {
                        if (i + 4 > s.length) return null
                        var z = 0
                        repeat(4) { z = z * 16 + (hex(s[i++]) ?: return null) }
                        b.append(z.toChar())
                    }
                    else -> return null
                }
            }
        }
        return null
    }

    /** Nur ASCII-Hexziffern wie kotlinx - Character.digit naehme auch arabische. */
    private fun hex(c: Char): Int? = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> null
    }

    companion object {
        /** Ein Objekt oder eine Liste als Wert - in einem der Felder kein Punkt. */
        val VERSCHACHTELT = Any()
        private const val KAPUTT = -2
        private val FELDER = Spur.FELDER.toTypedArray()
    }
}
