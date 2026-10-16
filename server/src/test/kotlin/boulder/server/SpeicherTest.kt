package boulder.server

import boulder.austausch.Lieferung
import boulder.austausch.Minute
import boulder.austausch.Nacht
import boulder.austausch.Tag
import boulder.austausch.Training
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
}
