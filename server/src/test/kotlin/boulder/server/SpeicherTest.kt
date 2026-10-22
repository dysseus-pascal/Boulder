package boulder.server

import boulder.austausch.Lieferung
import boulder.austausch.Minute
import boulder.austausch.Nacht
import boulder.austausch.Tag
import boulder.austausch.Training
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class SpeicherTest {
    private val jetzt = 1_792_152_000L

    private fun lieferung(schritte: Double, minuten: List<Minute> = emptyList()) = Lieferung(
        gesendet = jetzt, zone = "Europe/Zurich", app = "0.55.0",
        tage = listOf(Tag("2026-10-15", mapOf("schritte" to 9000.0)), Tag("2026-10-16", mapOf("schritte" to schritte))),
        naechte = listOf(Nacht(1_792_087_200L, 7200, 100, 580, 450, 51, 54)),
        trainings = listOf(Training("s:1", "Bike", jetzt - 7200, jetzt - 4500, 2700)),
        minuten = minuten,
    )

    @Test
    fun zweimalDasselbeErgibtDenselbenSpiegel() {
        Speicher.oeffne(":memory:").use { s ->
            s.uebernimm(lieferung(5000.0), jetzt, 90)
            s.uebernimm(lieferung(5000.0), jetzt, 90)
            assertEquals(2, s.tage("2026-10-01", "2026-10-31").size)
            assertEquals(1, s.naechte(0, Long.MAX_VALUE).size)
            assertEquals(1, s.trainings(0, Long.MAX_VALUE).size)
        }
    }

    @Test
    fun dieDateiBleibtNachDemNeustart() {
        val ordner = Files.createTempDirectory("boulder-speicher")
        val pfad = ordner.resolve("boulder.db").toString()
        try {
            Speicher.oeffne(pfad).use { it.uebernimm(lieferung(5000.0), jetzt, 90) }
            Speicher.oeffne(pfad).use { s ->
                assertEquals(mapOf("schritte" to 5000.0), s.tage("2026-10-16", "2026-10-16").single().werte)
                assertEquals("0.55.0", s.stand("app"))
            }
        } finally {
            ordner.toFile().deleteRecursively()
        }
    }

    @Test
    fun derLaufendeTagWirdErsetzt() {
        Speicher.oeffne(":memory:").use { s ->
            s.uebernimm(lieferung(5000.0), jetzt, 90)
            s.uebernimm(lieferung(7500.0), jetzt + 600, 90)
            assertEquals(mapOf("schritte" to 7500.0), s.tage("2026-10-16", "2026-10-16").single().werte)
            assertEquals((jetzt + 600).toString(), s.stand("letzte_lieferung"))
            assertEquals("Europe/Zurich", s.stand("zone"))
        }
    }

    @Test
    fun alteMinutenFallenWeg() {
        Speicher.oeffne(":memory:").use { s ->
            val alt = Minute(jetzt - 91 * 86_400L, 1, 2, 3, 4)
            val frisch = Minute(jetzt - 60, 5, 6, 7, 8)
            s.uebernimm(lieferung(5000.0, listOf(alt, frisch)), jetzt, 90)
            assertEquals(listOf(frisch), s.minuten(0, Long.MAX_VALUE))
            assertEquals(1L, s.umfang().minuten)
        }
    }

    @Test
    fun zuVieleClientsVerdraengenDieAeltestenOhneZugang() {
        Speicher.oeffne(":memory:").use { s ->
            s.legeClientAn(Speicher.Client("angemeldet", "Claude", listOf("https://claude.ai/api/mcp/auth_callback")), jetzt)
            s.speichereZugang("pruefwert", "erneuerung", "angemeldet", jetzt + 86_400, jetzt)
            for (i in 0..Speicher.HOECHSTENS_CLIENTS) s.legeClientAn(Speicher.Client("k$i", "x", listOf("http://localhost/callback")), jetzt)
            assertEquals(null, s.client("k0"))
            assertEquals(listOf("http://localhost/callback"), s.client("k1")?.umleitungen)
            assertEquals("Claude", s.client("angemeldet")?.name)
        }
    }

    @Test
    fun einZugangGiltBisZumAblaufUndEinmal() {
        Speicher.oeffne(":memory:").use { s ->
            s.speichereZugang("a", "zugang", "c", jetzt + 60, jetzt)
            s.speichereZugang("e", "erneuerung", "c", jetzt + 60, jetzt)
            assertEquals(true, s.zugangGilt("a", "zugang", jetzt + 60))
            assertEquals(false, s.zugangGilt("a", "zugang", jetzt + 61))
            assertEquals(false, s.zugangGilt("e", "zugang", jetzt))
            assertEquals("c", s.nimmZugang("e", "erneuerung", jetzt))
            assertEquals(null, s.nimmZugang("e", "erneuerung", jetzt))
        }
    }
}
