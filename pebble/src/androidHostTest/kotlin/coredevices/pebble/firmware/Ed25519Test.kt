package coredevices.pebble.firmware

import java.security.KeyPairGenerator
import java.security.Signature
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Die eigene Ed25519-Pruefung gegen RFC 8032 (die Vektoren 2 und 3 sind mit
 * OpenSSL aus den RFC-Seeds nachgerechnet), gegen das JDK als Orakel, und
 * gegen alles, was schiefgehen darf: gekippte Bits, formbares S, kaputte
 * Punkte, falsche Laengen. Dazu der echte Schluessel der App mit einer
 * Aussage, die kein Release je hat (Tag "test").
 */
class Ed25519Test {
    private fun hex(s: String) = FirmwareSignatur.hexZuBytes(s)

    private val vektoren = listOf(
        Triple(
            "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a", "",
            "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b",
        ),
        Triple(
            "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c", "72",
            "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00",
        ),
        Triple(
            "fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025", "af82",
            "6291d657deec24024827e69c3abe01a30ce548a284743a445e3680d7db5ac3ac18ff9b538d16f290ae67f760984dc6594a7c15e9716ed28dc027beceea1ec40a",
        ),
    )

    @Test
    fun rfc8032Vektoren() {
        for ((pk, msg, sig) in vektoren) {
            assertTrue(Ed25519.pruefe(hex(msg), hex(sig), hex(pk)), "Vektor $pk")
            assertEquals(true, ed25519Pruefen(hex(msg), hex(sig), hex(pk)))
        }
    }

    private fun gekippt(b: ByteArray, i: Int, bit: Int = 0): ByteArray = b.copyOf().also { it[i] = (it[i].toInt() xor (1 shl bit)).toByte() }

    @Test
    fun jedeAenderungFaelltAuf() {
        val (pk, msg, sig) = vektoren[2]
        val m = hex(msg); val s = hex(sig); val k = hex(pk)
        assertFalse(Ed25519.pruefe(gekippt(m, 0), s, k), "Nachricht")
        assertFalse(Ed25519.pruefe(m, gekippt(s, 3), k), "R")
        assertFalse(Ed25519.pruefe(m, gekippt(s, 40), k), "S")
        assertFalse(Ed25519.pruefe(m, gekippt(k, 5), m.let { k }), "Schluessel")
        assertFalse(Ed25519.pruefe(m, s.copyOf(63), k), "63 Byte")
        assertFalse(Ed25519.pruefe(m, s + byteArrayOf(0), k), "65 Byte")
        assertFalse(Ed25519.pruefe(m, s, k.copyOf(31)), "31 Byte Schluessel")
        assertFalse(Ed25519.pruefe(m, s, k + byteArrayOf(0)), "33 Byte Schluessel")
        assertFalse(Ed25519.pruefe(ByteArray(0), ByteArray(64), ByteArray(32)), "alles null")
        // Der Vektor 2 mit dem Schluessel von Vektor 3: anderer Schluessel.
        assertFalse(Ed25519.pruefe(hex(vektoren[1].second), hex(vektoren[1].third), k))
    }

    @Test
    fun formbaresSWirdAbgelehnt() {
        // S + L ist dieselbe Signatur in nicht kanonischer Form - RFC 8032 verlangt S < L.
        val (pk, msg, sig) = vektoren[1]
        val s = hex(sig)
        val l = java.math.BigInteger.valueOf(2).pow(252) + java.math.BigInteger("27742317777372353535851937790883648493")
        val sZahl = java.math.BigInteger(1, s.copyOfRange(32, 64).reversedArray())
        fun mitS(neu: java.math.BigInteger): ByteArray {
            val bytes = neu.toByteArray().reversedArray().copyOf(32)
            return s.copyOfRange(0, 32) + bytes
        }
        assertFalse(Ed25519.pruefe(hex(msg), mitS(sZahl + l), hex(pk)), "S + L")
        assertFalse(Ed25519.pruefe(hex(msg), mitS(l), hex(pk)), "S = L")
        assertTrue(Ed25519.pruefe(hex(msg), mitS(sZahl), hex(pk)), "S selbst")
    }

    @Test
    fun kaputtePunkteWerdenAbgelehnt() {
        val (pk, msg, sig) = vektoren[2]
        val s = hex(sig)
        val allesFf = ByteArray(32) { 0xFF.toByte() }
        assertFalse(Ed25519.pruefe(hex(msg), allesFf + s.copyOfRange(32, 64), hex(pk)), "R nicht auf der Kurve")
        assertFalse(Ed25519.pruefe(hex(msg), s, allesFf), "A nicht auf der Kurve")
        // y = p (nicht kanonisch): p als 32 Byte little-endian, Bit 255 frei.
        val pBytes = (java.math.BigInteger.valueOf(2).pow(255) - java.math.BigInteger.valueOf(19)).toByteArray().reversedArray().copyOf(32)
        assertFalse(Ed25519.pruefe(hex(msg), pBytes + s.copyOfRange(32, 64), hex(pk)), "y = p")
        // x = 0 mit Vorzeichen 1: y = 1 ergibt x = 0, das Vorzeichenbit macht es ungueltig.
        val yEins = ByteArray(32).also { it[0] = 1; it[31] = 0x80.toByte() }
        assertFalse(Ed25519.pruefe(hex(msg), yEins + s.copyOfRange(32, 64), hex(pk)), "x = 0 mit Vorzeichen")
    }

    @Test
    fun jdkAlsOrakel() {
        // Das JDK signiert, die eigene Pruefung muss es lesen - und Manipulation sehen.
        val paar = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val roh = paar.public.encoded.takeLast(32).toByteArray()
        for (laenge in listOf(0, 1, 63, 64, 65, 1000, 5000)) {
            val msg = ByteArray(laenge) { (it * 31 + laenge).toByte() }
            val sig = Signature.getInstance("Ed25519").run { initSign(paar.private); update(msg); sign() }
            assertTrue(Ed25519.pruefe(msg, sig, roh), "Laenge $laenge")
            if (laenge > 0) assertFalse(Ed25519.pruefe(gekippt(msg, laenge / 2), sig, roh), "manipuliert $laenge")
        }
    }

    @Test
    fun derSchluesselDerAppPasstZumSignierschluessel() {
        // Mit dem echten Schluessel signiert (Workflow "Schluessel zeigen" in boulder-firmware);
        // Tag "test" kann kein Release sein.
        val aussage = FirmwareSignatur.aussage("test", "normal_obelix_pvt_test.pbz", 12, "6f4b6612125fb3a0daecd2799dfd6c9c299424fd920f9b308110a2c1fbd8f443")
        assertEquals(
            "boulder-firmware/v1\ntest\nnormal_obelix_pvt_test.pbz\n12\nsha256:6f4b6612125fb3a0daecd2799dfd6c9c299424fd920f9b308110a2c1fbd8f443\n",
            aussage.decodeToString(),
        )
        val sig = hex("b2af7c838887b3924847179020828c4ed81907c20d8ed8b73915c77de980971e825159fe66d3dfcc2562e97e192a5c76522eab59a9b8b304de0d065004a44601")
        assertEquals(FirmwareSignatur.Ergebnis.Gueltig, FirmwareSignatur.pruefe(aussage, sig))
        assertEquals(FirmwareSignatur.Ergebnis.Ungueltig, FirmwareSignatur.pruefe(gekippt(aussage, 25), sig))
        assertEquals(FirmwareSignatur.Ergebnis.Ungueltig, FirmwareSignatur.pruefe(aussage, sig.copyOf(63)))
        // Ein anderer Tag in der Aussage: die Signatur gilt nicht mehr.
        val andere = FirmwareSignatur.aussage("v4.38.3-b11", "normal_obelix_pvt_test.pbz", 12, "6f4b6612125fb3a0daecd2799dfd6c9c299424fd920f9b308110a2c1fbd8f443")
        assertEquals(FirmwareSignatur.Ergebnis.Ungueltig, FirmwareSignatur.pruefe(andere, sig))
    }
}
