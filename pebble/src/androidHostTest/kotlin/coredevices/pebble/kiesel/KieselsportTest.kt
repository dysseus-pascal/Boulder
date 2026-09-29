package coredevices.pebble.kiesel

import kotlinx.serialization.json.Json
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Was ohne Telefon und Akte pruefbar ist: das Zerlegen der Nachrichten und
 * der Abschnittsliste, die Pausen, die Saetze und Bahnen fuer die Akte.
 */
class KieselsportTest {

    @Test
    fun abschnitteWerdenStrengZerlegt() {
        val a = Kieselsport.abschnitteAus("10:12:40;80:10:35;kaputt;150:8")
        assertEquals(listOf(Kieselsport.Abschnitt(10, 12, 40), Kieselsport.Abschnitt(80, 10, 35)), a)
        assertTrue(Kieselsport.abschnitteAus(null).isEmpty())
        assertTrue(Kieselsport.abschnitteAus("-1:2:3").isEmpty())
    }

    @Test
    fun pausenSchnittAusDenLuecken() {
        // Luecken: 80-(10+40)=30 und 160-(80+35)=45 -> 37 (ganzzahlig)
        val a = Kieselsport.abschnitteAus("10:12:40;80:10:35;160:8:30")
        assertEquals(37, Kieselsport.pausenSchnitt(a))
        assertEquals(0, Kieselsport.pausenSchnitt(a.take(1)))
    }

    @Test
    fun saetzeMitPausenDazwischen() {
        val anfang = Instant.ofEpochSecond(1_000_000)
        val ende = anfang.plusSeconds(600)
        val s = Kieselsport.alsSegmente(Kieselsport.abschnitteAus("10:12:40;80:10:35"), anfang, ende)
        // Satz, Pause, Satz
        assertEquals(3, s.size)
        assertEquals(12, s[0].repetitions)
        assertEquals(anfang.plusSeconds(50), s[1].startTime)
        assertEquals(anfang.plusSeconds(80), s[1].endTime)
    }

    @Test
    fun einSatzAusserhalbDerSitzungLeertAlles() {
        val anfang = Instant.ofEpochSecond(1_000_000)
        val s = Kieselsport.alsSegmente(Kieselsport.abschnitteAus("10:12:40;580:10:35"), anfang, anfang.plusSeconds(600))
        assertTrue(s.isEmpty())
    }

    @Test
    fun bahnenMitBeckenlaenge() {
        val anfang = Instant.ofEpochSecond(1_000_000)
        val b = Kieselsport.alsBahnen(Kieselsport.abschnitteAus("10:1:25;35:1:27"), anfang, anfang.plusSeconds(120), 25.0)
        assertEquals(2, b.size)
        assertEquals(25.0, b[1].length?.inMeters)
    }

    @Test
    fun nachrichtNachArtDerWerteGetrennt() {
        val n = KieselsportBegleiter.zerlege(
            mapOf(
                Kieselsport.SP_BEGINN to 1_790_445_600L,
                Kieselsport.SP_ART to 1uL,
                Kieselsport.SP_ABSCHNITTE to "10:1:25;",
                Kieselsport.SP_KURVE to ubyteArrayOf(120u, 130u),
            )
        )
        assertEquals(1_790_445_600L, n.zahlen[Kieselsport.SP_BEGINN])
        assertEquals(1L, n.zahlen[Kieselsport.SP_ART])
        assertEquals("10:1:25;", n.texte[Kieselsport.SP_ABSCHNITTE])
        assertContentEquals(byteArrayOf(120, 130.toByte()), n.bytes[Kieselsport.SP_KURVE])
    }

    @Test
    fun nachrichtUeberlebtDiePlatte() {
        // Was Wartend ablegt, muss unveraendert zurueckkommen - auch die Bytes der Kurve.
        val n = KieselNachricht(
            zahlen = mapOf(Kieselsport.SP_BEGINN to 1790696270L, Kieselsport.SP_DAUER to 1332L),
            texte = mapOf(Kieselsport.SP_ABSCHNITTE to "1,2,3"),
            bytes = mapOf(Kieselsport.SP_KURVE to byteArrayOf(120, 125, -128)),
        )
        val text = Json.encodeToString(KieselNachricht.serializer(), n)
        val zurueck = Json.decodeFromString(KieselNachricht.serializer(), text)
        assertEquals(n.zahlen, zurueck.zahlen)
        assertEquals(n.texte, zurueck.texte)
        assertContentEquals(n.bytes[Kieselsport.SP_KURVE], zurueck.bytes[Kieselsport.SP_KURVE])
    }
}
