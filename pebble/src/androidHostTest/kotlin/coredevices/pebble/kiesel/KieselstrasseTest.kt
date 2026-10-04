package coredevices.pebble.kiesel

import io.rebble.libpebblecommon.packets.AppMessageTuple
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KieselstrasseTest {
    @Test
    fun kuerzel_werden_zur_kennzahl_der_uhr() {
        assertEquals(1, Kieselstrasse.artAusText("C"))
        assertEquals(2, Kieselstrasse.artAusText("TL"))
        assertEquals(5, Kieselstrasse.artAusText("TR"))
        assertEquals(12, Kieselstrasse.artAusText("OFFR"))
        assertEquals(13, Kieselstrasse.artAusText("RNDB3"))
        assertEquals(14, Kieselstrasse.artAusText("RNLB2"))
        assertEquals(0, Kieselstrasse.artAusText("XYZ"))
        assertEquals(0, Kieselstrasse.artAusText(null))
    }

    @Test
    fun ausfahrt_nur_im_kreisel() {
        assertEquals(3, Kieselstrasse.ausfahrt("RNDB3"))
        assertEquals(12, Kieselstrasse.ausfahrt("RNLB12"))
        assertEquals(0, Kieselstrasse.ausfahrt("TR"))
        assertEquals(0, Kieselstrasse.ausfahrt("RNDB"))
    }

    @Test
    fun winkel_fuer_die_uhr() {
        assertEquals(90, Kieselstrasse.winkelFuerUhr(89.6f))
        assertEquals(-90, Kieselstrasse.winkelFuerUhr(270f))
        assertEquals(-180, Kieselstrasse.winkelFuerUhr(-180f))
        assertEquals(179, Kieselstrasse.winkelFuerUhr(-181f))
        assertEquals(0, Kieselstrasse.winkelFuerUhr(360f))
        assertEquals(null, Kieselstrasse.winkelFuerUhr(Float.NaN))
    }

    /** Die Groesse des Dictionarys auf der Uhr: ein Byte Zahl, je Tupel Kopf und Wert (libpebble3 kodiert so). */
    private fun diktBytes(felder: Map<Int, Any>): Int = 1 + felder.entries.sumOf { (k, v) ->
        when (v) {
            is String -> AppMessageTuple.createString(k.toUInt(), v)
            is Int -> AppMessageTuple.createInt(k.toUInt(), v)
            else -> error("Typ ${v::class}")
        }.toBytes().size
    }

    private fun gueltigesUtf8(s: String): Boolean {
        val bytes = s.encodeToByteArray()
        return bytes.decodeToString().encodeToByteArray().contentEquals(bytes) && !s.contains('\uFFFD')
    }

    @Test
    fun strasse_utf8_sicher_auf_47_bytes() {
        // 51 Bytes; byteweise bei 47 gekuerzt endete der Name mitten im "e" mit Akzent.
        val lang = "Exit 2 · Route de la Gare de Neuchâtel-Serrières"
        val kurz = Kieselstrasse.strasseFuerUhr(lang)
        assertTrue(kurz.encodeToByteArray().size <= 47, "${kurz.encodeToByteArray().size} Bytes")
        assertTrue(lang.startsWith(kurz))
        assertEquals(46, kurz.encodeToByteArray().size)
        // Kyrillisch (2 Bytes je Zeichen) und ein Emoji (Ersatzpaar, 4 Bytes) an der Grenze.
        val kyrillisch = Kieselstrasse.strasseFuerUhr("Проспект".repeat(3))
        assertEquals(46, kyrillisch.encodeToByteArray().size)
        val emoji = Kieselstrasse.strasseFuerUhr("a".repeat(45) + "\uD83D\uDE97")
        assertEquals("a".repeat(45), emoji)
        assertEquals("Bahnhofstrasse", Kieselstrasse.strasseFuerUhr("Bahnhofstrasse"))
    }

    @Test
    fun die_nachricht_passt_in_den_eingang_der_uhr() {
        // Ungekuerzt waeren es ueber 500 Bytes - die Uhr (Eingang 320) verwirft die ganze Nachricht.
        val felder = Kieselstrasse.felderFuerUhr(120, 13, "Exit 2 · " + "Улица ".repeat(40), 1_790_000_000L, 5_000, 2, 45)
        val strasse = felder[Kieselstrasse.STRASSE] as String
        assertTrue(strasse.encodeToByteArray().size <= 47)
        assertTrue(gueltigesUtf8(strasse))
        assertTrue(diktBytes(felder) <= 320, "${diktBytes(felder)} Bytes")
    }

    @Test
    fun ankunft_als_int32_mit_den_bits_der_vorzeichenlosen_zahl() {
        // 2039: als int32 negativ; die Uhr nimmt nur TUPLE_INT - die Bits sind die der uint32.
        val felder = Kieselstrasse.felderFuerUhr(120, 1, "", 2_200_000_000L, 5_000, 0, null)
        val ankunft = felder[Kieselstrasse.ANKUNFT] as Int
        assertEquals(2_200_000_000L, ankunft.toUInt().toLong())
        // Was nicht in 32 Bit passt, geht nicht hinaus - eine kleine Zahl waere eine falsche Uhrzeit.
        assertEquals(null, Kieselstrasse.felderFuerUhr(120, 1, "", 5_000_000_000L, 5_000, 0, null)[Kieselstrasse.ANKUNFT])
    }
}
