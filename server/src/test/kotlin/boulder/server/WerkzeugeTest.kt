package boulder.server

import boulder.austausch.Abschnitt
import boulder.austausch.HrvFenster
import boulder.austausch.Lieferung
import boulder.austausch.Minute
import boulder.austausch.Nacht
import boulder.austausch.Stand
import boulder.austausch.Tag
import boulder.austausch.Training
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WerkzeugeTest {
    // 16.10.2026, 14:00 in Zuerich (UTC+2).
    private val jetzt = 1_792_152_000L

    private fun werkzeuge(l: Lieferung): Werkzeuge {
        val s = Speicher.oeffne(":memory:")
        s.uebernimm(l, jetzt, 90)
        return Werkzeuge(s)
    }

    private fun lieferung(
        tage: List<Tag> = emptyList(),
        naechte: List<Nacht> = emptyList(),
        trainings: List<Training> = emptyList(),
        minuten: List<Minute> = emptyList(),
    ) = Lieferung(gesendet = jetzt, zone = "Europe/Zurich", app = "0.55.0", tage = tage, naechte = naechte,
        trainings = trainings, minuten = minuten, stand = Stand("v4.41.1-b31", mapOf("schritte" to 10_000), 190))

    @Test
    fun tageAlsTabelleMitLegende() {
        val w = werkzeuge(lieferung(tage = listOf(
            Tag("2026-10-15", mapOf("schritte" to 9000.0, "hrv" to 54.0)),
            Tag("2026-10-16", mapOf("schritte" to 8123.0, "wasser" to 1500.0, "eigenes" to 2.5)),
        )))
        val text = w.tage(LocalDate.parse("2026-10-15"), LocalDate.parse("2026-10-16"))
        val zeilen = text.trimEnd().lines()
        assertTrue("# hrv: HRV of that night, RMSSD [ms]" in zeilen, text)
        assertEquals("date,schritte,hrv,wasser,eigenes", zeilen.first { !it.startsWith("#") })
        assertEquals("2026-10-15,9000,54,,", zeilen[zeilen.size - 2])
        assertEquals("2026-10-16,8123,,1500,2.5", zeilen.last())
    }

    @Test
    fun grenzenDerAbfrage() {
        val w = werkzeuge(lieferung())
        assertFailsWith<Werkzeuge.Fehler> { w.tage(LocalDate.parse("2026-10-16"), LocalDate.parse("2026-10-15")) }
        assertFailsWith<Werkzeuge.Fehler> { w.tage(LocalDate.parse("2025-01-01"), LocalDate.parse("2026-10-16")) }
        // Das Schema sagt "hoechstens 366 Tage nach von": genau 366 geht.
        val von = LocalDate.parse("2025-10-15")
        w.tage(von, von.plusDays(366))
        w.trainings(von, von.plusDays(366))
        assertEquals("bis may be at most 366 days after von", assertFailsWith<Werkzeuge.Fehler> { w.tage(von, von.plusDays(367)) }.message)
        val ab = LocalDateTime.parse("2026-10-16T08:00")
        assertFailsWith<Werkzeuge.Fehler> { w.minuten(ab, ab.plusHours(6).plusMinutes(1)) }
        assertFailsWith<Werkzeuge.Fehler> { w.puls(ab, ab.plusHours(25)) }
        assertEquals("bis must be after von", assertFailsWith<Werkzeuge.Fehler> { w.minuten(ab, ab) }.message)
    }

    @Test
    fun dieLueckeDerSommerzeit() {
        // 28.3.2027 in Zuerich: um 02:00 springt die Uhr auf 03:00 (01:00 UTC).
        val sprung = 1_806_195_600L
        val w = werkzeuge(lieferung(minuten = (-60..59).map { Minute(sprung + it * 60L, 0, 100, 60, 55) }))
        // 02:30 gibt es nicht; es zaehlt als der Sprung. Die halbe Stunde bis 03:30 sind 30 Minuten.
        val nachDemSprung = w.minuten(LocalDateTime.parse("2027-03-28T02:30"), LocalDateTime.parse("2027-03-28T03:30")).trimEnd().lines()
        assertEquals(30, nachDemSprung.count { it.startsWith("03:") }, nachDemSprung.joinToString("\n"))
        assertEquals("03:00,0,100,60,55", nachDemSprung[2])
        // Und bis 02:30: die letzte halbe Stunde vor dem Sprung, keine Minute danach.
        val davor = w.minuten(LocalDateTime.parse("2027-03-28T01:30"), LocalDateTime.parse("2027-03-28T02:30")).trimEnd().lines()
        assertEquals("01:59,0,100,60,55", davor.last())
        assertEquals(30, davor.size - 2)
    }

    @Test
    fun zweiNaechteAmSelbenMorgenDieLaengsteZaehlt() {
        // Hauptnacht wie unten (Aufwachen 05:40 am 16.10.) und ein Morgenschlaf 06:40-07:40 als eigene Nacht.
        val beginn = 1_792_087_200L
        val haupt = Nacht(beginn, 7200, 100, 580, 450, 51, 54)
        val morgen = Nacht(beginn + 640 * 60L, 7200, 0, 60, 55, 0, 0)
        val text = werkzeuge(lieferung(naechte = listOf(haupt, morgen))).nacht(LocalDate.parse("2026-10-16"))
        assertTrue("asleep: 450 min" in text, text)
        assertTrue("HRV (RMSSD): 54 ms" in text, text)
    }

    @Test
    fun nachtNachDemTagDesAufwachens() {
        // Beginn 15.10. 20:00 Ortszeit; eingeschlafen nach 100 min (21:40), aufgewacht nach 580 min (05:40 am 16.10.).
        val beginn = 1_792_087_200L
        val w = werkzeuge(lieferung(naechte = listOf(Nacht(
            beginn, 7200, 100, 580, 450, 51, 54, messstufe = 4, akkuBeginn = 100, akkuEnde = 100, geladen = true,
            abschnitte = listOf(Abschnitt(100, 160, 1), Abschnitt(160, 220, 2), Abschnitt(220, 250, 3)),
            hrvFenster = listOf(HrvFenster(120, 50, 52), HrvFenster(130, 70, 50), HrvFenster(140, 60, 51)),
        ))))
        val text = w.nacht(LocalDate.parse("2026-10-16"))
        assertTrue("fell asleep: 21:40, woke up: 05:40, asleep: 450 min" in text, text)
        assertTrue("stages total: light 60 min, deep 60 min, REM 30 min" in text, text)
        assertTrue("21:40-22:40 light" in text, text)
        assertTrue("RMSSD median 60 ms, range 50-70 ms" in text, text)
        assertTrue("watch battery: 100% -> 100% (charged during the night, no consumption figure)" in text, text)
        assertEquals("no night ending on 2026-10-15", w.nacht(LocalDate.parse("2026-10-15")))
    }

    @Test
    fun trainingsUndPuls() {
        val w = werkzeuge(lieferung(
            trainings = listOf(Training("s:1", "Bike", jetzt - 7200, jetzt - 4500, 2700, 14_200.0, 128, 145)),
            minuten = listOf(Minute(jetzt, 0, 900, 100, 55), Minute(jetzt + 60, 0, 900, 110, 55), Minute(jetzt + 120, 0, 900, 0, 55),
                Minute(jetzt + 300, 2, 500, 90, 55)),
        ))
        val t = w.trainings(LocalDate.parse("2026-10-16"), LocalDate.parse("2026-10-16")).trimEnd().lines()
        assertEquals("2026-10-16 12:00,12:45,Bike,45,14.20,128,145,Boulder", t.last())
        val p = w.puls(LocalDateTime.parse("2026-10-16T14:00"), LocalDateTime.parse("2026-10-16T14:10")).trimEnd().lines()
        // Ungemessene Minuten (0) zaehlen nicht: im ersten Block zwei Werte, Median der oberen Mitte.
        assertEquals(listOf("time,hr_median,hr_max,measured_minutes", "14:00,110,110,2", "14:05,90,90,1"), p)
    }

    @Test
    fun standNenntZoneUndUmfang() {
        val text = werkzeuge(lieferung(tage = listOf(Tag("2026-10-16", mapOf("schritte" to 8123.0))))).stand()
        assertTrue("timezone: Europe/Zurich" in text, text)
        assertTrue("last upload: 2026-10-16 14:00 (app 0.55.0)" in text, text)
        assertTrue("watch firmware: v4.41.1-b31" in text, text)
        assertTrue("days: 2026-10-16 .. 2026-10-16" in text, text)
    }
}
