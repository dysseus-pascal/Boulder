package boulder.server

import boulder.austausch.Akteneintrag
import boulder.austausch.Lieferung
import boulder.austausch.Minute
import boulder.austausch.Nacht
import boulder.austausch.Probe
import boulder.austausch.Sauerstoff
import boulder.austausch.Spanne
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
    fun einTagBehaeltWasBoulderGeradeNichtKennt() {
        Speicher.oeffne(":memory:").use { s ->
            fun tag(werte: Map<String, Double>, entfernt: List<String> = emptyList()) =
                s.uebernimm(Lieferung(gesendet = jetzt, zone = "UTC", app = "0.56.0", tage = listOf(Tag("2026-10-16", werte, entfernt))), jetzt, 90)
            tag(mapOf("schritte" to 5000.0, "wasser" to 2000.0, "koffein_mg" to 150.0))
            // Ohne Wasser und Koffein, etwa weil der Tag aus dem Fenster der Akte fiel: sie bleiben.
            tag(mapOf("schritte" to 5200.0))
            assertEquals(mapOf("schritte" to 5200.0, "wasser" to 2000.0, "koffein_mg" to 150.0), s.tage("2026-10-16", "2026-10-16").single().werte)
            // Das Glas wurde geloescht: Boulder kennt das Wasser dieses Tages und sagt es.
            tag(mapOf("schritte" to 5200.0), entfernt = listOf("wasser"))
            assertEquals(mapOf("schritte" to 5200.0, "koffein_mg" to 150.0), s.tage("2026-10-16", "2026-10-16").single().werte)
            // Bleibt nichts, faellt der Tag weg.
            tag(emptyMap(), entfernt = listOf("schritte", "koffein_mg"))
            assertEquals(emptyList(), s.tage("2026-10-01", "2026-10-31"))
            assertEquals(null, s.umfang().ersterTag)
        }
    }

    @Test
    fun entfernteTrainingsVerschwinden() {
        Speicher.oeffne(":memory:").use { s ->
            s.uebernimm(lieferung(5000.0).copy(trainings = listOf(Training("s:1", "Run", jetzt - 9000, jetzt - 7200, 1800), Training("s:2", "Run", jetzt - 9000, jetzt - 7080, 1920))), jetzt, 90)
            s.uebernimm(Lieferung(gesendet = jetzt, zone = "UTC", app = "0.56.0", trainingsEntfernt = listOf("s:1", "unbekannt")), jetzt, 90)
            assertEquals(listOf("s:2"), s.trainings(0, Long.MAX_VALUE).map { it.kennung })
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
    fun einSatzDerAkteErsetztSichSamtReiheUndVerschwindet() {
        Speicher.oeffne(":memory:").use { s ->
            fun satz(vararg puls: Double) = Akteneintrag(
                "hr-1", "HeartRate", jetzt, jetzt + 600, 7200, "com.fitbit.FitbitMobile",
                texte = mapOf("device" to "Charge 6"), abschnitte = listOf(Spanne(jetzt, jetzt + 60, "x")),
                proben = puls.mapIndexed { i, w -> Probe(jetzt + i * 60L, w) },
            )
            val leer = Lieferung(gesendet = jetzt, zone = "Europe/Zurich", app = "0.57.0")
            val q = s.uebernimm(leer.copy(akte = listOf(satz(60.0, 62.0, 64.0))), jetzt, 90)
            assertEquals(1, q.akte)
            assertEquals(0, q.akteEntfernt)
            // Health Connect meldet einen geaenderten Satz ganz: die alte Reihe darf nicht stehen bleiben.
            s.uebernimm(leer.copy(akte = listOf(satz(70.0, 80.0))), jetzt, 90)
            assertEquals(Speicher.Probenumfang(2, 70.0, 75.0, 80.0), s.probenUmfang("hr-1"))
            assertEquals(satz(70.0, 80.0), s.akteEintrag("hr-1"))
            assertEquals(1, s.akte("HeartRate", 0, Long.MAX_VALUE, 10).size)
            val weg = s.uebernimm(leer.copy(akteEntfernt = listOf("hr-1", "unbekannt")), jetzt, 90)
            assertEquals(2, weg.akteEntfernt)
            assertEquals(null, s.akteEintrag("hr-1"))
            assertEquals(null, s.probenUmfang("hr-1"))
            assertEquals(emptyList(), s.probenEimer("HeartRate", 0, Long.MAX_VALUE, 300))
        }
    }

    @Test
    fun vorgeschichteErsetztEinenTagGanzUndSauerstoffBleibtEinmal() {
        Speicher.oeffne(":memory:").use { s ->
            val leer = Lieferung(gesendet = jetzt, zone = "Europe/Zurich", app = "0.57.0")
            s.uebernimm(leer.copy(vorgeschichte = listOf(Tag("2025-03-01", mapOf("schritte" to 1.0, "rem" to 2.0))),
                sauerstoff = listOf(Sauerstoff(jetzt, 95, 3))), jetzt, 90)
            val q = s.uebernimm(leer.copy(vorgeschichte = listOf(Tag("2025-03-01", mapOf("schritte" to 5.0))),
                sauerstoff = listOf(Sauerstoff(jetzt, 97, 6))), jetzt, 90)
            assertEquals(1, q.vorgeschichte)
            assertEquals(1, q.sauerstoff)
            assertEquals(listOf(Tag("2025-03-01", mapOf("schritte" to 5.0))), s.vortage("2025-01-01", "2025-12-31"))
            assertEquals(listOf(Sauerstoff(jetzt, 97, 6)), s.sauerstoff(0, Long.MAX_VALUE))
            assertEquals(1L, s.umfang().vortage)
            assertEquals(1L, s.umfang().sauerstoff)
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
