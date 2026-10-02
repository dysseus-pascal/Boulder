package coredevices.pebble.kiesel

import android.content.Context
import android.location.Location
import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
    fun haengeAn(context: Context, beginn: Long, ort: Location) {
        if (ort.accuracy > GENAUIGKEIT_MAX) return
        try {
            val zeile = buildJsonObject {
                put("t", ort.time / 1000)
                put("lat", ort.latitude)
                put("lon", ort.longitude)
                if (ort.hasAltitude()) put("h", ort.altitude)
                put("g", ort.accuracy)
            }
            datei(context, beginn).appendText(zeile.toString() + "\n")
        } catch (e: Exception) {
            Log.w(TAG, "Spurpunkt: " + e.message)
        }
    }

    fun lies(context: Context, beginn: Long): List<Punkt> {
        val d = datei(context, beginn)
        if (!d.exists()) return emptyList()
        return try {
            ausZeilen(d.readLines())
        } catch (e: Exception) {
            Log.w(TAG, "Spur lesen: " + e.message)
            emptyList()
        }
    }

    /**
     * Die Punkte aus den Zeilen der Datei. Eine kaputte Zeile (die App starb
     * beim Schreiben) kostet nur sie selbst, nicht die ganze Spur; eine Zeile
     * ohne Zeit oder Ort ergibt keinen Punkt bei (0, NaN).
     */
    fun ausZeilen(zeilen: List<String>): List<Punkt> = zeilen.mapNotNull { zeile ->
        if (zeile.isBlank()) return@mapNotNull null
        // kotlinx statt org.json: das gibt es auch ohne Android, also im Host-Test.
        val o = try { Json.parseToJsonElement(zeile).jsonObject } catch (e: Exception) { return@mapNotNull null }
        Punkt(
            o["t"]?.jsonPrimitive?.longOrNull ?: 0L,
            o["lat"]?.jsonPrimitive?.doubleOrNull ?: Double.NaN,
            o["lon"]?.jsonPrimitive?.doubleOrNull ?: Double.NaN,
            o["h"]?.jsonPrimitive?.doubleOrNull,
            o["g"]?.jsonPrimitive?.doubleOrNull?.toFloat() ?: Float.NaN,
        )
    }.filter { it.zeit > 0 && !it.lat.isNaN() && !it.lon.isNaN() }


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
        Log.w(TAG, "Spur lesen: " + e.message)
        null
    }

    /** Eine zurueckgeholte Spur ablegen - nur, wenn hier keine liegt. */
    fun schreibeRoh(context: Context, beginn: Long, inhalt: String): Boolean = try {
        val d = datei(context, beginn)
        if (d.exists()) false else { d.writeText(inhalt); true }
    } catch (e: Exception) {
        Log.w(TAG, "Spur schreiben: " + e.message)
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
     * Die gesammelten Höhenmeter - nur das Aufwärts.
     *
     * MIT EINER SCHWELLE, sonst zählt das Rauschen. GPS-Höhen schwanken um
     * mehrere Meter im Stehen; ohne Schwelle sammelte ein Spaziergang in der
     * Ebene hundert Höhenmeter.
     */
    const val STEIGUNG_MIN = 3.0

    fun hoehenmeter(punkte: List<Punkt>): Double {
        var summe = 0.0
        var bezug: Double? = null
        punkte.forEachIndexed { i, p ->
            val h = p.hoehe ?: return@forEachIndexed
            // Nach einem Sprung beginnt die Hoehe neu: ein wiedergefundener
            // Empfang (oder der erste Fix an einem alten Ort) ist kein Anstieg.
            if (i > 0 && abstand(punkte[i - 1], p) > SPRUNG_MAX) { bezug = h; return@forEachIndexed }
            val b = bezug
            if (b == null) { bezug = h; return@forEachIndexed }
            if (h - b >= STEIGUNG_MIN) { summe += h - b; bezug = h }
            else if (b - h >= STEIGUNG_MIN) { bezug = h }
        }
        return summe
    }

    /** Die Spur als GPX - das Format, das jede Karten-App liest. */
    fun alsGpx(punkte: List<Punkt>, name: String): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        append("""<gpx version="1.1" creator="Kiesel-Helper" """)
        append("""xmlns="http://www.topografix.com/GPX/1/1">""").append('\n')
        append("  <trk><name>").append(name).append("</name><trkseg>\n")
        punkte.forEach { p ->
            append("    <trkpt lat=\"").append(p.lat).append("\" lon=\"").append(p.lon).append("\">")
            p.hoehe?.let { append("<ele>").append(it).append("</ele>") }
            append("<time>").append(java.time.Instant.ofEpochSecond(p.zeit)).append("</time>")
            append("</trkpt>\n")
        }
        append("  </trkseg></trk>\n</gpx>\n")
    }

    /** Für den Export als eine Datei. */
    fun alsJson(punkte: List<Punkt>): String = buildJsonArray {
        punkte.forEach { p ->
            add(buildJsonObject {
                put("t", p.zeit); put("lat", p.lat); put("lon", p.lon)
                p.hoehe?.let { put("h", it) }
            })
        }
    }.toString()
}
