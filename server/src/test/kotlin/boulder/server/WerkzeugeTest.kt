package boulder.server

import boulder.austausch.Abschnitt
import boulder.austausch.Akteneintrag
import boulder.austausch.HrvFenster
import boulder.austausch.Lieferung
import boulder.austausch.Minute
import boulder.austausch.Nacht
import boulder.austausch.Probe
import boulder.austausch.Sauerstoff
import boulder.austausch.Spanne
import boulder.austausch.Stand
import boulder.austausch.Tag
import boulder.austausch.Training
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
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

    /** Eine Ortszeit in Zuerich als UTC-Sekunde. */
    private fun z(t: String): Long = LocalDateTime.parse(t).atZone(ZoneId.of("Europe/Zurich")).toEpochSecond()

    private val fitbit = "com.fitbit.FitbitMobile"
    private val boulder = "ch.dysseus.boulder"

    /** Puls aus zwei Apps, Gewicht, eine Nacht mit Komma im Titel und Schritte, einer davon am Tag der Zeitumstellung. */
    private val akte = listOf(
        Akteneintrag("hr-1", "HeartRate", jetzt, jetzt + 600, 7200, fitbit,
            proben = listOf(Probe(jetzt, 60.0), Probe(jetzt + 60, 62.0), Probe(jetzt + 300, 70.0), Probe(jetzt + 540, 80.0))),
        Akteneintrag("hr-2", "HeartRate", jetzt + 60, jetzt + 180, 7200, boulder,
            proben = listOf(Probe(jetzt + 60, 90.0), Probe(jetzt + 120, 100.0))),
        Akteneintrag("w-1", "Weight", z("2026-10-16T07:00"), quelle = "com.withings", werte = mapOf("kg" to 72.4)),
        Akteneintrag("w-2", "Weight", z("2026-10-16T20:00"), quelle = "com.withings", werte = mapOf("kg" to 72.8)),
        Akteneintrag("s-1", "SleepSession", z("2026-10-15T22:30"), z("2026-10-16T06:30"), 7200, fitbit,
            texte = mapOf("title" to "Nacht, gut"),
            abschnitte = listOf(Spanne(z("2026-10-15T23:00"), z("2026-10-15T23:40"), "deep"), Spanne(z("2026-10-15T23:40"), z("2026-10-16T01:00"), "light"))),
        Akteneintrag("st-1", "Steps", z("2026-10-16T08:00"), z("2026-10-16T09:00"), 7200, fitbit, werte = mapOf("steps" to 1200.0)),
        Akteneintrag("st-2", "Steps", z("2026-10-16T10:00"), z("2026-10-16T10:30"), 7200, fitbit, werte = mapOf("steps" to 800.0)),
        Akteneintrag("st-3", "Steps", z("2026-10-16T08:00"), z("2026-10-16T08:10"), 7200, boulder, werte = mapOf("steps" to 1000.0)),
        // 01:30 Ortszeit ist in UTC noch der 15.: gezaehlt wird nach dem Ortstag.
        Akteneintrag("st-5", "Steps", z("2026-10-16T01:30"), z("2026-10-16T01:40"), 7200, boulder, werte = mapOf("steps" to 100.0)),
        // 25.10.2026: Ende der Sommerzeit. 23:30 Ortszeit ist 22:30 UTC und gehoert noch zum 25.
        Akteneintrag("st-4", "Steps", z("2026-10-25T23:30"), z("2026-10-25T23:45"), 3600, fitbit, werte = mapOf("steps" to 500.0)),
    )

    private fun mitAkte() = werkzeuge(lieferung().copy(akte = akte))

    @Test
    fun akteListetSaetzeMitQuelleReiheUndTeilen() {
        val w = mitAkte()
        assertEquals(listOf(
            "# bpm_n/_min/_mean/_max: the samples inside one record; akte_verlauf and akte_eintrag show them",
            "start,end,source,bpm_n,bpm_min,bpm_mean,bpm_max,id",
            "2026-10-16 14:00,14:10,com.fitbit.FitbitMobile,4,60,68,80,hr-1",
            "2026-10-16 14:01,14:03,ch.dysseus.boulder,2,90,95,100,hr-2",
        ), w.akte("HeartRate", LocalDate.parse("2026-10-16"), LocalDate.parse("2026-10-16")).trimEnd().lines())
        val nacht = w.akte("SleepSession", LocalDate.parse("2026-10-15"), LocalDate.parse("2026-10-16")).trimEnd().lines()
        assertEquals("start,end,source,title,parts,id", nacht[1])
        // Das Komma im Titel einer fremden App darf die Spalten nicht verschieben.
        assertEquals("2026-10-15 22:30,2026-10-16 06:30,com.fitbit.FitbitMobile,\"Nacht, gut\",deep 40 min; light 80 min,s-1", nacht[2])
        assertEquals("no Gewicht records between 2026-10-16 and 2026-10-16; known types: HeartRate, SleepSession, Steps, Weight",
            w.akte("Gewicht", LocalDate.parse("2026-10-16"), LocalDate.parse("2026-10-16")))
    }

    @Test
    fun akteTageJeQuelleUndTagAuchUeberDieZeitumstellung() {
        val w = mitAkte()
        assertEquals(listOf(
            "# records: records starting that day; values are daily sums",
            "date,source,records,steps",
            "2026-10-16,ch.dysseus.boulder,2,1100",
            "2026-10-16,com.fitbit.FitbitMobile,2,2000",
            "2026-10-25,com.fitbit.FitbitMobile,1,500",
        ), w.akteTage("Steps", LocalDate.parse("2026-10-16"), LocalDate.parse("2026-10-26")).trimEnd().lines())
        assertEquals("2026-10-16,com.withings,2,72.4,72.6,72.8",
            w.akteTage("Weight", LocalDate.parse("2026-10-16"), LocalDate.parse("2026-10-16")).trimEnd().lines().last())
        assertEquals(listOf("date,source,records,minutes", "2026-10-15,com.fitbit.FitbitMobile,1,480"),
            w.akteTage("SleepSession", LocalDate.parse("2026-10-15"), LocalDate.parse("2026-10-16")).trimEnd().lines().drop(1))
        assertEquals(listOf(
            "date,source,records,bpm_n,bpm_min,bpm_mean,bpm_max",
            "2026-10-16,ch.dysseus.boulder,1,2,90,95,100",
            "2026-10-16,com.fitbit.FitbitMobile,1,4,60,68,80",
        ), w.akteTage("HeartRate", LocalDate.parse("2026-10-16"), LocalDate.parse("2026-10-16")).trimEnd().lines().drop(2))
    }

    @Test
    fun akteVerlaufInBloeckenJeQuelle() {
        val w = mitAkte()
        assertEquals(listOf(
            "# blocks of 5 min; n = samples in the block",
            "time,source,n,bpm_min,bpm_mean,bpm_max",
            "2026-10-16 14:00,ch.dysseus.boulder,2,90,95,100",
            "2026-10-16 14:00,com.fitbit.FitbitMobile,2,60,61,62",
            "2026-10-16 14:05,com.fitbit.FitbitMobile,2,70,75,80",
        ), w.akteVerlauf("HeartRate", LocalDateTime.parse("2026-10-16T14:00"), LocalDateTime.parse("2026-10-16T14:10")).trimEnd().lines())
        val stuendlich = w.akteVerlauf("HeartRate", LocalDateTime.parse("2026-10-16T00:00"), LocalDateTime.parse("2026-10-17T12:00")).trimEnd().lines()
        assertEquals("# blocks of 60 min; n = samples in the block", stuendlich.first())
        assertEquals("2026-10-16 14:00,com.fitbit.FitbitMobile,4,60,68,80", stuendlich.last())
        assertEquals("at most 7 days per query", assertFailsWith<Werkzeuge.Fehler> {
            w.akteVerlauf("HeartRate", LocalDateTime.parse("2026-10-01T00:00"), LocalDateTime.parse("2026-10-08T00:01"))
        }.message)
    }

    @Test
    fun akteEintragZeigtTeileUndReihe() {
        val lang = Akteneintrag("hr-lang", "HeartRate", jetzt, jetzt + 400, 7200, fitbit, proben = (0 until 400).map { Probe(jetzt + it, 60.0 + it % 2) })
        val w = werkzeuge(lieferung().copy(akte = akte + lang))
        val nacht = w.akteEintrag("s-1").trimEnd().lines()
        assertEquals(listOf("SleepSession from com.fitbit.FitbitMobile", "start: 2026-10-15 22:30, end: 2026-10-16 06:30", "title: Nacht, gut",
            "parts:", "23:00-23:40 deep", "23:40-01:00 light"), nacht)
        assertEquals(listOf("samples (time,bpm):", "14:00:00,60", "14:01:00,62", "14:05:00,70", "14:09:00,80"),
            w.akteEintrag("hr-1").trimEnd().lines().drop(2))
        // Mehr als 300 Werte: in 5-Minuten-Bloecken, sonst sprengte ein Tag Puls Claudes Kontext.
        assertEquals(listOf("samples in 5-minute blocks (time,n,bpm_min,bpm_mean,bpm_max):", "14:00,300,60,60.5,61", "14:05,100,60,60.5,61"),
            w.akteEintrag("hr-lang").trimEnd().lines().drop(2))
        assertEquals("no Health Connect record with id x", w.akteEintrag("x"))
    }

    @Test
    fun tageMitVorgeschichteWieDieTrends() {
        val w = werkzeuge(lieferung(tage = listOf(
            Tag("2026-10-14", mapOf("schritte" to 5000.0)),
            Tag("2026-10-15", mapOf("schritte" to 6000.0, "wasser" to 1500.0)),
            Tag("2026-10-16", mapOf("wasser" to 1200.0)),
        )).copy(vorgeschichte = listOf(
            Tag("2026-10-12", mapOf("schritte" to 9000.0, "rem" to 80.0, "aktiv" to 60.0, "wasser" to 2000.0)),
            Tag("2026-10-13", mapOf("schritte" to 8000.0)),
            Tag("2026-10-14", mapOf("schritte" to 7777.0, "rem" to 70.0)),
            Tag("2026-10-15", mapOf("rem" to 75.0, "wasser" to 1000.0)),
            // Nach dem ersten eigenen Schritt-Tag zaehlt die Vorgeschichte fuer Schritte nicht mehr, auch wo Boulder keine hat.
            Tag("2026-10-16", mapOf("schritte" to 4444.0)),
        )))
        val zeilen = w.tage(LocalDate.parse("2026-10-12"), LocalDate.parse("2026-10-16")).trimEnd().lines()
        assertTrue("# rem: REM sleep of that night [min] (history only)" in zeilen, zeilen.toString())
        // Die Vorgeschichte nur vor dem ersten eigenen Wert jeder Spalte, die Aktivminuten nie.
        assertEquals(listOf(
            "date,schritte,wasser,rem,source",
            "2026-10-12,9000,2000,80,history",
            "2026-10-13,8000,,,history",
            "2026-10-14,5000,,70,boulder+history",
            "2026-10-15,6000,1500,75,boulder+history",
            "2026-10-16,,1200,,boulder",
        ), zeilen.dropWhile { it.startsWith("#") })
    }

    @Test
    fun spo2DerUhr() {
        val w = werkzeuge(lieferung().copy(sauerstoff = listOf(Sauerstoff(jetzt, 96, 5), Sauerstoff(jetzt + 600, 94, 2))))
        assertEquals(listOf("# quality: sensor quality 0-7 as reported by the watch", "time,spo2_percent,quality",
            "2026-10-16 14:00,96,5", "2026-10-16 14:10,94,2"), w.spo2(LocalDate.parse("2026-10-16"), LocalDate.parse("2026-10-16")).trimEnd().lines())
        assertFailsWith<Werkzeuge.Fehler> { w.spo2(LocalDate.parse("2026-09-01"), LocalDate.parse("2026-10-16")) }
    }

    @Test
    fun standNenntAkteVorgeschichteUndSpo2() {
        val text = werkzeuge(lieferung().copy(akte = akte, vorgeschichte = listOf(Tag("2025-03-01", mapOf("schritte" to 1.0))),
            sauerstoff = listOf(Sauerstoff(jetzt, 96, 5)))).stand()
        assertTrue("history days: 2025-03-01 .. 2025-03-01 (" in text, text)
        assertTrue("watch SpO2 readings: 1 (since 2026-10-16 14:00)" in text, text)
        assertTrue("  HeartRate: 2 (2026-10-16 .. 2026-10-16) ch.dysseus.boulder 1, com.fitbit.FitbitMobile 1" in text, text)
        assertTrue("  Steps: 5 (2026-10-16 .. 2026-10-25) com.fitbit.FitbitMobile 3, ch.dysseus.boulder 2" in text, text)
    }

    @Test
    fun zahlenUndFelderFuerCsv() {
        assertEquals("72", Werkzeuge.wert(72.0))
        assertEquals("0.85", Werkzeuge.wert(0.854))
        assertEquals("72.5", Werkzeuge.wert(72.5))
        assertEquals("Nacht", Werkzeuge.csv("Nacht"))
        assertEquals("\"a,b\"", Werkzeuge.csv("a,b"))
        assertEquals("\"er sagte \"\"gut\"\"\"", Werkzeuge.csv("er sagte \"gut\""))
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
