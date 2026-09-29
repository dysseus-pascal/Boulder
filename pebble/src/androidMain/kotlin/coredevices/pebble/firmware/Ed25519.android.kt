package coredevices.pebble.firmware

import android.os.Build
import java.math.BigInteger
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * ZWEI PRUEFUNGEN, WO ES ZWEI GIBT. Die eigene (unten, RFC 8032 mit
 * BigInteger) laeuft ueberall; ab Android 13 kann es auch die Plattform.
 * Beide muessen ja sagen - so prueft die Plattform die eigene Umsetzung bei
 * jedem Update erneut, und ein Fehler in einer von beiden faellt zur
 * sicheren Seite.
 */
actual fun ed25519Pruefen(nachricht: ByteArray, signatur: ByteArray, oeffentlich: ByteArray): Boolean? {
    val eigen = Ed25519.pruefe(nachricht, signatur, oeffentlich)
    val plattform = plattformPruefen(nachricht, signatur, oeffentlich) ?: return eigen
    return eigen && plattform
}

// X.509-Vorspann fuer einen rohen Ed25519-Schluessel (RFC 8410).
private val DER_VORSPANN = FirmwareSignatur.hexZuBytes("302a300506032b6570032100")

private fun plattformPruefen(nachricht: ByteArray, signatur: ByteArray, oeffentlich: ByteArray): Boolean? {
    // Auf dem Telefon erst ab API 33; im Host-Test (SDK_INT 0) ist das JDK die Plattform.
    if (Build.VERSION.SDK_INT in 1..32) return null
    return try {
        val schluessel = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(DER_VORSPANN + oeffentlich))
        Signature.getInstance("Ed25519").run {
            initVerify(schluessel)
            update(nachricht)
            verify(signatur)
        }
    } catch (e: NoSuchAlgorithmException) {
        null
    } catch (e: GeneralSecurityException) {
        // Ein Schluessel oder eine Signatur, die die Plattform nicht einmal
        // liest, ist ungueltig - nicht "nicht verfuegbar".
        false
    }
}

/**
 * Ed25519 nach RFC 8032, Abschnitt 5.1 - nur die Pruefung.
 *
 * DIE FALLEN, JE MIT TEST (Ed25519Test): Zahlen sind little-endian, BigInteger
 * big-endian; Reste mit mod, nicht rem (negativ); Bit 255 des y ist das
 * Vorzeichen von x und muss weg; y >= p ist keine Kodierung; x = 0 mit
 * Vorzeichen 1 auch nicht; S >= L (nicht kanonisch, formbar) wird abgelehnt;
 * in den Hash geht der Schluessel so, wie er ankam, nicht neu kodiert.
 * Rechnung in erweiterten Koordinaten (X, Y, Z, T), Addition nach RFC 8032
 * 5.1.4 - sie ist auf dieser Kurve vollstaendig, also auch die Verdopplung.
 */
internal object Ed25519 {
    private val EINS: BigInteger = BigInteger.ONE
    // Nicht BigInteger.TWO: das gibt es auf Android erst ab API 33.
    private val ZWEI: BigInteger = BigInteger.valueOf(2)
    private val P: BigInteger = ZWEI.pow(255) - BigInteger.valueOf(19)
    private val L: BigInteger = ZWEI.pow(252) + BigInteger("27742317777372353535851937790883648493")
    private val D: BigInteger = BigInteger.valueOf(-121665).multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P)
    private val D2: BigInteger = D.shiftLeft(1).mod(P)
    private val WURZEL_MINUS_EINS: BigInteger = ZWEI.modPow((P - EINS).shiftRight(2), P)
    private val BY: BigInteger = BigInteger.valueOf(4).multiply(BigInteger.valueOf(5).modInverse(P)).mod(P)
    private val B: Punkt = Punkt(xAusY(BY, false)!!, BY)
    private val NULL_PUNKT = Punkt(BigInteger.ZERO, EINS, EINS, BigInteger.ZERO)

    class Punkt(val x: BigInteger, val y: BigInteger, val z: BigInteger, val t: BigInteger) {
        constructor(x: BigInteger, y: BigInteger) : this(x, y, EINS, x.multiply(y).mod(P))
    }

    private fun add(p: Punkt, q: Punkt): Punkt {
        val a = (p.y - p.x).multiply(q.y - q.x).mod(P)
        val b = (p.y + p.x).multiply(q.y + q.x).mod(P)
        val c = p.t.multiply(D2).multiply(q.t).mod(P)
        val d = p.z.shiftLeft(1).multiply(q.z).mod(P)
        val e = b - a
        val f = d - c
        val g = d + c
        val h = b + a
        return Punkt(e.multiply(f).mod(P), g.multiply(h).mod(P), f.multiply(g).mod(P), e.multiply(h).mod(P))
    }

    private fun mal(p: Punkt, k: BigInteger): Punkt {
        var q = NULL_PUNKT
        for (i in k.bitLength() - 1 downTo 0) {
            q = add(q, q)
            if (k.testBit(i)) q = add(q, p)
        }
        return q
    }

    private fun xAusY(y: BigInteger, ungerade: Boolean): BigInteger? {
        val y2 = y.multiply(y).mod(P)
        val u = (y2 - EINS).mod(P)
        val v = D.multiply(y2).add(EINS).mod(P)
        val x2 = u.multiply(v.modInverse(P)).mod(P)
        var x = x2.modPow((P + BigInteger.valueOf(3)).shiftRight(3), P)
        if (x.multiply(x).mod(P) != x2) x = x.multiply(WURZEL_MINUS_EINS).mod(P)
        if (x.multiply(x).mod(P) != x2) return null
        if (x.signum() == 0 && ungerade) return null
        if (x.testBit(0) != ungerade) x = P - x
        return x
    }

    private fun kleinEndian(bytes: ByteArray): BigInteger = BigInteger(1, bytes.reversedArray())

    private fun dekodiere(bytes: ByteArray): Punkt? {
        if (bytes.size != 32) return null
        val ohneVorzeichen = bytes.copyOf()
        ohneVorzeichen[31] = (ohneVorzeichen[31].toInt() and 0x7F).toByte()
        val y = kleinEndian(ohneVorzeichen)
        if (y >= P) return null
        val ungerade = (bytes[31].toInt() and 0x80) != 0
        val x = xAusY(y, ungerade) ?: return null
        return Punkt(x, y)
    }

    private fun kodiere(p: Punkt): ByteArray {
        val zInv = p.z.modInverse(P)
        val x = p.x.multiply(zInv).mod(P)
        val y = p.y.multiply(zInv).mod(P)
        val aus = ByteArray(32)
        val yBytes = y.toByteArray().reversedArray()
        yBytes.copyInto(aus, 0, 0, minOf(32, yBytes.size))
        if (x.testBit(0)) aus[31] = (aus[31].toInt() or 0x80).toByte()
        return aus
    }

    fun pruefe(nachricht: ByteArray, signatur: ByteArray, oeffentlich: ByteArray): Boolean {
        if (signatur.size != 64 || oeffentlich.size != 32) return false
        val rBytes = signatur.copyOfRange(0, 32)
        val r = dekodiere(rBytes) ?: return false
        val s = kleinEndian(signatur.copyOfRange(32, 64))
        if (s >= L) return false
        val a = dekodiere(oeffentlich) ?: return false
        val hash = MessageDigest.getInstance("SHA-512").run {
            update(rBytes)
            update(oeffentlich)
            update(nachricht)
            digest()
        }
        val k = kleinEndian(hash).mod(L)
        val links = kodiere(mal(B, s))
        val rechts = kodiere(add(r, mal(a, k)))
        return links.contentEquals(rechts)
    }
}
