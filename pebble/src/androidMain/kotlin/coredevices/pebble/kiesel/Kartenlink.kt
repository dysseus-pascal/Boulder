package coredevices.pebble.kiesel

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import coredevices.pebble.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

/**
 * Was in einem Kartenlink steckt - aus Kiesel-Helper (Kartenlink).
 *
 * Die Zerlegung ist reine Zeichenkettenarbeit ohne android.net.Uri, damit
 * sich die halbe Dutzend Linkformen von Google im Test nachrechnen lassen.
 */
internal object Kartenlink {

    /** Ein Ziel: Koordinaten, ein Suchtext, oder beides. */
    data class Ziel(
        val lat: Double?,
        val lon: Double?,
        val text: String?,
    ) {
        val brauchbar: Boolean get() = (lat != null && lon != null) || !text.isNullOrBlank()
    }

    /**
     * Die Formen, die tatsaechlich vorkommen:
     *
     *  - `.../maps/dir/?api=1&destination=47.1,8.2` oder `destination=Name`
     *  - `.../maps/search/?api=1&query=47.1,8.2`
     *  - `.../maps/place/Name/@47.1,8.2,17z/...`
     *  - `.../maps/@47.1,8.2,15z`
     *  - `?daddr=47.1,8.2` (die alte Form, immer noch unterwegs)
     *  - `geo:47.1,8.2?q=Name` und `geo:0,0?q=Name`
     *
     * DAS AT-ZEICHEN IST NICHT IMMER DAS ZIEL. In einem `place`-Link ist es
     * der Kartenausschnitt, der meist auf dem Ort liegt - aber nicht immer.
     * Steht ein Name daneben, geht der vor: OsmAnd sucht ihn selbst und
     * trifft damit den Eingang statt der Bildmitte.
     */
    fun zerlege(roh: String): Ziel? {
        if (roh.isBlank()) return null

        if (roh.startsWith("geo:", ignoreCase = true)) {
            val rest = roh.substring(4)
            val koordinaten = rest.substringBefore('?')
            val frage = abfrage(roh, "q")
            val paar = alsPaar(koordinaten)
            // "geo:0,0?q=..." ist die uebliche Form fuer eine reine Suche.
            val echt = paar?.takeUnless { it.first == 0.0 && it.second == 0.0 }
            // Steht im q= eine Koordinate, gilt die.
            val ausFrage = frage?.let { alsPaar(it) }
            return Ziel(
                echt?.first ?: ausFrage?.first,
                echt?.second ?: ausFrage?.second,
                frage?.takeIf { ausFrage == null },
            ).takeIf { it.brauchbar }
        }

        // NUR AUS EINEM KARTENLINK. Ein "q=" gibt es auch in einer
        // Google-Suche, und daraus ein Navigationsziel zu machen waere
        // eine Anmassung - die Pruefung hat genau das gefunden.
        if (!istKarte(roh)) return null

        listOf("destination", "query", "daddr", "q").forEach { name ->
            abfrage(roh, name)?.takeIf { it.isNotBlank() }?.let { wert ->
                alsPaar(wert)?.let { return Ziel(it.first, it.second, null) }
                return Ziel(null, null, wert)
            }
        }

        // Ein Name im Pfad schlaegt den Kartenausschnitt: die Koordinate
        // hinter dem At-Zeichen ist die Bildmitte, nicht der Eingang.
        val name = Regex("/maps/place/([^/@?]+)").find(roh)?.groupValues?.get(1)
            ?.let { entschluessle(it.replace('+', ' ')) }
            ?.takeIf { it.isNotBlank() }

        val stelle = Regex("@(-?\\d+\\.\\d+),(-?\\d+\\.\\d+)").find(roh)
        val lat = stelle?.groupValues?.get(1)?.toDoubleOrNull()
        val lon = stelle?.groupValues?.get(2)?.toDoubleOrNull()

        return Ziel(lat, lon, name).takeIf { it.brauchbar }
    }

    /** Ein Wert aus der Abfrage hinter dem Fragezeichen. */
    private fun abfrage(roh: String, name: String): String? {
        val teil = roh.substringAfter('?', "")
        if (teil.isEmpty()) return null
        return teil.split('&')
            .firstOrNull { it.startsWith("$name=", ignoreCase = true) }
            ?.substringAfter('=')
            ?.let { entschluessle(it.replace('+', ' ')) }
    }

    /** "47.1,8.2" - alles andere ist ein Name. */
    private fun alsPaar(text: String): Pair<Double, Double>? {
        val teile = entschluessle(text).split(',')
        if (teile.size != 2) return null
        val lat = teile[0].trim().toDoubleOrNull() ?: return null
        val lon = teile[1].trim().toDoubleOrNull() ?: return null
        if (lat < -90 || lat > 90 || lon < -180 || lon > 180) return null
        return lat to lon
    }

    private fun entschluessle(text: String): String = try {
        URLDecoder.decode(text, "UTF-8")
    } catch (e: Exception) {
        text
    }

    /**
     * Der Host eines Links - nicht ein Teilstring: "maps.google.com.boese.tld"
     * oder "?q=maps.app.goo.gl" sahen sonst aus wie Google, und die Aufloesung
     * des Kurzlinks fragte einen fremden Host.
     */
    // Von Hand statt java.net.URI: das wirft bei Leerzeichen oder "|" im Link,
    // und geteilte Links haben so etwas oft.
    private val HOST = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://([^/?#]*)")
    private fun host(roh: String): String? =
        HOST.find(roh.trim())?.groupValues?.get(1)?.substringAfterLast('@')?.substringBefore(':')?.lowercase()?.takeIf { it.isNotEmpty() }
    private fun pfad(roh: String): String {
        val treffer = HOST.find(roh.trim()) ?: return ""
        return roh.trim().substring(treffer.range.last + 1).substringBefore('?').substringBefore('#')
    }
    private fun istGoogle(host: String): Boolean =
        Regex("^(www\\.|maps\\.)?google\\.[a-z]{2,3}(\\.[a-z]{2})?$").matches(host)

    /** Ein Kartenlink von Google: Karte oder Kurzlink dorthin. */
    fun istKarte(roh: String): Boolean {
        val h = host(roh) ?: return false
        // maps.google.tld nimmt Ziele auch ohne /maps im Pfad (die alte daddr-Form).
        return (istGoogle(h) && (h.startsWith("maps.") || pfad(roh).startsWith("/maps"))) || istKurzlink(roh)
    }

    /** Kurzlinks, die erst aufgeloest werden muessen. */
    fun istKurzlink(roh: String): Boolean {
        val h = host(roh) ?: return false
        return h == "maps.app.goo.gl" || (h == "goo.gl" && pfad(roh).startsWith("/maps")) ||
            (h == "g.co" && pfad(roh).startsWith("/kgs"))
    }

    /**
     * Einem Kurzlink folgen, bis er sich zu erkennen gibt.
     *
     * NUR DEN KOPF LESEN, nicht die Seite: gesucht ist das Ziel der
     * Umleitung, und eine Google-Maps-Seite ist ein Megabyte JavaScript.
     * Hoechstens fuenf Sprünge - eine Kette, die laenger ist, ist keine
     * Umleitung mehr, sondern eine Schleife.
     *
     * Laeuft auf einem Hintergrundfaden; der Aufrufer sorgt dafuer.
     */
    fun folge(roh: String, spruenge: Int = 5): String {
        var aktuell = roh
        repeat(spruenge) {
            val verbindung = try {
                (URL(aktuell).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    requestMethod = "HEAD"
                    connectTimeout = 8000
                    readTimeout = 8000
                    setRequestProperty("User-Agent", "Mozilla/5.0")
                }
            } catch (e: Exception) {
                return aktuell
            }
            try {
                val code = verbindung.responseCode
                if (code !in 300..399) return aktuell
                val weiter = verbindung.getHeaderField("Location") ?: return aktuell
                aktuell = if (weiter.startsWith("http")) weiter
                          else URL(URL(aktuell), weiter).toString()
            } catch (e: Exception) {
                return aktuell
            } finally {
                verbindung.disconnect()
            }
        }
        return aktuell
    }

    /**
     * Ein Ziel an OsmAnd geben. Die Koordinate geht vor: OsmAnds Suche ist
     * offline und fand im Versuch gewoehnliche Adressen nicht. Bleibt nur ein
     * Name, macht [Ortsuche] erst eine Koordinate daraus.
     *
     * Per Intent und nicht ueber AIDL: `navigate()` meldete dort Erfolg, auch
     * wenn OsmAnd noch startete und den Aufruf verwarf.
     */
    suspend fun uebergib(context: Context, ziel: Ziel): String? = withContext(Dispatchers.IO) {
        if (ziel.lat != null && ziel.lon != null) {
            return@withContext oeffneZiel(context, ziel.lat, ziel.lon, ziel.text)?.let { context.getString(R.string.ks_k_weg_koordinate, it) }
        }
        val name = ziel.text
        if (name.isNullOrBlank()) return@withContext null
        Ortsuche.finde(context, name)?.let { (lat, lon) ->
            return@withContext oeffneZiel(context, lat, lon, name)?.let { context.getString(R.string.ks_k_weg_adresse, it) }
        }
        oeffneZiel(context, null, null, name)
    }

    /** Den Ort in OsmAnd zeigen - oder, ohne Koordinate, OsmAnd danach suchen lassen. */
    private fun oeffneZiel(context: Context, lat: Double?, lon: Double?, name: String?): String? {
        val paket = Kieselstrasse.osmandPaket(context) ?: return null
        if (lat != null && lon != null) {
            val titel = URLEncoder.encode(name ?: context.getString(R.string.ks_k_ziel), "UTF-8")
            val uri = String.format(Locale.ROOT, "geo:%f,%f?q=%f,%f(%s)", lat, lon, lat, lon, titel)
            return if (starte(context, paket, uri)) context.getString(R.string.ks_k_ort_gezeigt) else null
        }
        if (name.isNullOrBlank()) return null
        return if (starte(context, paket, "geo:0,0?q=" + URLEncoder.encode(name, "UTF-8"))) context.getString(R.string.ks_k_suche) else null
    }

    private fun starte(context: Context, paket: String, uri: String): Boolean = try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(paket).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: Exception) {
        Log.i("Kartenlink", "OsmAnd nimmt nicht: " + e.message)
        false
    }
}
