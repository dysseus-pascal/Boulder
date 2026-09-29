package coredevices.pebble.kiesel

import android.content.Context
import android.location.Geocoder
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Aus einer Adresse eine Koordinate machen.
 *
 * WARUM DAS NOETIG WURDE: ein Google-Maps-Link traegt oft nur einen Namen.
 * OsmAnd kann selbst suchen, aber seine Suche ist offline und findet nur, was
 * in der geladenen Karte steht und dort auch so heisst. Eine Adresse aus einem
 * Link fand sie im Versuch nicht - und ein Ziel, das nicht ankommt, ist keins.
 *
 * Mit einer Koordinate stellt sich die Frage nicht mehr. Das ist der ganze
 * Zweck dieser Datei: aus "Bahnhofstrasse 1, Bern" ein Paar Zahlen machen,
 * bevor OsmAnd ueberhaupt gefragt wird.
 *
 * ZWEI WEGE, in dieser Reihenfolge:
 *  1. Androids eigener [Geocoder]. Kostet nichts, verlaesst das Telefon
 *     womoeglich gar nicht - aber er braucht einen Dienst im Hintergrund, und
 *     den hat nicht jedes Geraet (auf einem ohne Google-Dienste fehlt er).
 *  2. Nominatim, der Suchdienst von OpenStreetMap. Dieselbe Datengrundlage,
 *     aus der OsmAnds Karten stammen - was er findet, liegt also auch dort,
 *     wo OsmAnd hinfaehrt.
 *
 * WAS DABEI DAS TELEFON VERLAESST: im zweiten Fall die Adresse, an einen
 * fremden Rechner. Das geschieht nur fuer Links OHNE Koordinate und nur, wenn
 * jemand gerade einen angetippt hat. Steht eine Koordinate im Link, wird
 * niemand gefragt.
 */
internal object Ortsuche {

    private const val DIENST = "https://nominatim.openstreetmap.org/search"

    /**
     * Nominatim verlangt eine aussagekraeftige Kennung.
     *
     * Das ist keine Formalie, sondern ihre Nutzungsbedingung: anonyme
     * Anfragen werden abgewiesen. Eine Anfrage je angetipptem Link liegt weit
     * unter ihrer Grenze von einer je Sekunde.
     */
    private const val KENNUNG = "Boulder (privat, github.com/dysseus-pascal)"

    /** Erst das Telefon fragen, dann die Karte. Null heisst: nicht gefunden. */
    fun finde(context: Context, adresse: String): Pair<Double, Double>? {
        if (adresse.isBlank()) return null
        vomTelefon(context, adresse)?.let { return it }
        return vonNominatim(adresse)
    }

    /**
     * Androids eingebauter Umsetzer.
     *
     * `isPresent` ist die Frage, ob ueberhaupt ein Dienst dahintersteht - auf
     * einem Geraet ohne Google-Dienste steht keiner, und der Aufruf gaebe
     * stumm eine leere Liste zurueck.
     */
    private fun vomTelefon(context: Context, adresse: String): Pair<Double, Double>? {
        if (!Geocoder.isPresent()) return null
        return try {
            @Suppress("DEPRECATION")
            Geocoder(context).getFromLocationName(adresse, 1)
                ?.firstOrNull()
                ?.let { it.latitude to it.longitude }
        } catch (e: Exception) {
            Log.w("Ortsuche", "Geocoder: " + e.message)
            null
        }
    }

    private fun vonNominatim(adresse: String): Pair<Double, Double>? {
        val ziel = DIENST + "?format=json&limit=1&q=" +
            URLEncoder.encode(adresse, "UTF-8")
        val verbindung = try {
            (URL(ziel).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("User-Agent", KENNUNG)
                setRequestProperty("Accept", "application/json")
            }
        } catch (e: Exception) {
            Log.w("Ortsuche", "Nominatim: " + e.message)
            return null
        }
        return try {
            if (verbindung.responseCode != 200) return null
            ausAntwort(verbindung.inputStream.bufferedReader().use { it.readText() })
        } catch (e: Exception) {
            Log.w("Ortsuche", "Nominatim: " + e.message)
            null
        } finally {
            verbindung.disconnect()
        }
    }

    /**
     * Die Antwort zerlegen - als eigene Funktion, damit sie pruefbar ist.
     *
     * Nominatim schickt die Zahlen als ZEICHENKETTEN ("47.3769"), nicht als
     * Zahlen. Als Zahl gelesen ergaeben sie auf manchen Wegen eine Null - und ein
     * Ziel im Golf von Guinea.
     */
    fun ausAntwort(json: String): Pair<Double, Double>? = try {
        val erster = Json.parseToJsonElement(json).jsonArray.firstOrNull()?.jsonObject
        val lat = erster?.get("lat")?.jsonPrimitive?.content?.toDoubleOrNull()
        val lon = erster?.get("lon")?.jsonPrimitive?.content?.toDoubleOrNull()
        if (lat == null || lon == null) null
        else if (lat < -90 || lat > 90 || lon < -180 || lon > 180) null
        else lat to lon
    } catch (e: Exception) {
        null
    }
}
