package coredevices.pebble.firmware

/**
 * Boulder: die Signatur der Firmware-Releases (Ed25519).
 *
 * WAS SIGNIERT IST: nicht das Paket, sondern eine Aussage ueber das Release -
 * Tag, Dateiname, Groesse, sha256. So gehoert die Signatur zu genau diesem
 * Release: ein einmal signiertes Paket laesst sich nicht unter einem neuen
 * Tag wiederverwenden, und die Aussage steht schon fest, wenn das Paket
 * geladen ist (der sha256 laeuft beim Laden mit). Der Workflow signieren.yml
 * in dysseus-pascal/boulder-firmware baut dieselbe Aussage und signiert sie;
 * die README dort beschreibt Schluessel und Wechsel.
 *
 * MEHRERE SCHLUESSEL, damit ein Wechsel ohne Gleichschritt geht: erst den
 * neuen hier aufnehmen und die App ausrollen, dann mit ihm signieren, dann
 * den alten streichen. Die Signatur gilt, wenn sie zu einem passt.
 */
object FirmwareSignatur {
    const val KOPF = "boulder-firmware/v1"

    /** Die erlaubten oeffentlichen Schluessel, 32 Byte roh als Hex (signatur.pub in boulder-firmware). */
    val SCHLUESSEL: List<String> = listOf(
        "b4151694844af76321d9e870eeb3fd3512086017b767ed174780c489666b6e6f",
    )

    fun schluessel(): List<ByteArray> = SCHLUESSEL.map(::hexZuBytes)

    /** Die Aussage ueber ein Release - Zeile fuer Zeile, LF, ohne Leerzeichen. */
    fun aussage(tag: String, dateiname: String, groesse: Long, sha256Hex: String): ByteArray =
        "$KOPF\n$tag\n$dateiname\n$groesse\nsha256:${sha256Hex.lowercase()}\n".encodeToByteArray()

    enum class Ergebnis { Gueltig, Ungueltig, NichtVerfuegbar }

    fun pruefe(aussage: ByteArray, signatur: ByteArray, schluessel: List<ByteArray> = schluessel()): Ergebnis {
        if (signatur.size != 64 || schluessel.isEmpty()) return Ergebnis.Ungueltig
        var verfuegbar = false
        for (k in schluessel) {
            when (ed25519Pruefen(aussage, signatur, k)) {
                true -> return Ergebnis.Gueltig
                false -> verfuegbar = true
                null -> Unit
            }
        }
        return if (verfuegbar) Ergebnis.Ungueltig else Ergebnis.NichtVerfuegbar
    }

    fun hexZuBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "hex mit ungerader Laenge" }
        return ByteArray(hex.length / 2) { i -> hex.substring(2 * i, 2 * i + 2).toInt(16).toByte() }
    }
}

/**
 * Ed25519 (RFC 8032) pruefen. true/false: geprueft; null: auf dieser Plattform
 * nicht moeglich - dann gibt es kein Update, keine stille Ausnahme.
 */
expect fun ed25519Pruefen(nachricht: ByteArray, signatur: ByteArray, oeffentlich: ByteArray): Boolean?
