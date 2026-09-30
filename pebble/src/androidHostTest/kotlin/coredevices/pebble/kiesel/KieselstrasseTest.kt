package coredevices.pebble.kiesel

import kotlin.test.Test
import kotlin.test.assertEquals

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
}
