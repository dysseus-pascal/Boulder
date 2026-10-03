package coredevices.pebble.kiesel

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SupCycleTest {
    @Test
    fun neu_genommen_sind_nur_faellige_und_nicht_schon_gemeldete() {
        // Faellig: Plaetze 0, 2, 3. Genommen: 0, 1 (nicht faellig), 3. Vorher schon 0.
        assertEquals(listOf(3), SupCycle.neuGenommen(0b1101, 0b1011, 0b0001))
    }

    @Test
    fun heute_zeigt_nur_faellige_mit_namen_und_platzhalter() {
        val s = SupCycle.Stand(LocalDate.of(2026, 9, 29), listOf("Magnesium", "", "Zink"), 0b111, 0b100)
        assertEquals(
            listOf(
                SupCycle.Eintrag("Magnesium", false),
                SupCycle.Eintrag("Platz 2", false),
                SupCycle.Eintrag("Zink", true),
            ),
            s.heute { "Platz $it" },
        )
    }

    /** Ein Platz im 26-Byte-Format der Uhr (plan_to_bytes in SupCycles plan.c). */
    private fun platz(name: String, h: Int, m: Int, benutzt: Boolean = true, alle: Int = 1, an: Int = 0, aus: Int = 0, anker: Int = 20_000): ByteArray {
        val b = ByteArray(26)
        name.toByteArray(Charsets.UTF_8).copyInto(b)
        b[16] = h.toByte(); b[17] = m.toByte(); b[18] = (if (benutzt) 1 else 0).toByte()
        b[19] = alle.toByte(); b[20] = an.toByte(); b[21] = aus.toByte()
        for (i in 0 until 4) b[22 + i] = (anker shr (8 * i)).toByte()
        return b
    }

    @Test
    fun plan_liest_zeit_rhythmus_und_anker_wie_die_uhr() {
        val bytes = platz("Multivitamin", 8, 0) + platz("Black Maca", 12, 30, an = 8, aus = 2, anker = 20_300) +
            platz("", 0, 0, benutzt = true) + platz("Kaputt", 30, 75, alle = 0, an = 60)
        val plan = SupCycle.plan(bytes)
        assertEquals(4, plan.size)
        assertEquals(SupCycle.Platz("Multivitamin", LocalTime.of(8, 0), true, 1, 0, 0, 20_000), plan[0])
        assertEquals(LocalTime.of(12, 30), plan[1].zeit)
        assertEquals(20_300L, plan[1].anker)
        // Ohne Namen kein Platz, wie prv_sanitize auf der Uhr.
        assertFalse(plan[2].benutzt)
        // Unsinn wird begrenzt wie auf der Uhr: Stunde 8, Minute 0, alle 1 Tag, hoechstens 52 Wochen.
        assertEquals(SupCycle.Platz("Kaputt", LocalTime.of(8, 0), true, 1, 52, 0, 20_000), plan[3])
        assertEquals(emptyList(), SupCycle.plan(ByteArray(25)))
    }

    @Test
    fun plan_liest_negativen_anker() {
        assertEquals(-3L, SupCycle.plan(platz("A", 8, 0, anker = -3))[0].anker)
    }

    @Test
    fun faellig_folgt_zyklus_und_raster_der_uhr() {
        val zyklus = SupCycle.Platz("Maca", LocalTime.of(8, 0), true, alle = 1, wochenAn = 1, wochenAus = 1, anker = 100)
        assertTrue(SupCycle.faelligAm(zyklus, 100))
        assertTrue(SupCycle.faelligAm(zyklus, 106))
        assertFalse(SupCycle.faelligAm(zyklus, 107))
        assertFalse(SupCycle.faelligAm(zyklus, 113))
        assertTrue(SupCycle.faelligAm(zyklus, 114))
        // Vor dem Anker steht der Zyklus auf seinem ersten Tag.
        assertTrue(SupCycle.faelligAm(zyklus, 90))
        val jedenZweiten = zyklus.copy(alle = 2, wochenAn = 0)
        assertTrue(SupCycle.faelligAm(jedenZweiten, 102))
        assertFalse(SupCycle.faelligAm(jedenZweiten, 103))
        assertFalse(SupCycle.faelligAm(zyklus.copy(benutzt = false), 100))
        assertEquals(0b01L, SupCycle.faellig(listOf(zyklus, zyklus.copy(anker = 93)), 100))
    }

    @Test
    fun uhrtag_zaehlt_wie_plan_today() {
        val tag = LocalDate.of(2026, 10, 2)
        // Oestlich von Greenwich liegt die Ortsmitternacht noch am Vortag (UTC) - so zaehlt die Uhr.
        assertEquals(LocalDate.of(2026, 10, 1).toEpochDay(), SupCycle.uhrTag(tag, ZoneId.of("Europe/Zurich")))
        assertEquals(tag.toEpochDay(), SupCycle.uhrTag(tag, ZoneId.of("America/New_York")))
    }

    @Test
    fun neuer_tag_ohne_meldung_rechnet_aus_dem_plan() {
        val zone = ZoneId.of("Europe/Zurich")
        val heute = LocalDate.of(2026, 10, 2)
        // "Abends" jeden zweiten Tag, gestern war einer - heute steht es nicht an.
        val plan = SupCycle.plan(platz("Multivitamin", 8, 0) + platz("Abends", 20, 0, alle = 2, anker = SupCycle.uhrTag(heute, zone).toInt() - 1))
        val gestern = SupCycle.Stand(heute.minusDays(1), listOf("Multivitamin", "Abends"), 0b11, 0b11)
        val s = SupCycle.standFuer(heute, zone, gestern, plan)!!
        assertEquals(heute, s.tag)
        assertEquals(0b01L, s.faellig)
        assertEquals(listOf(SupCycle.Eintrag("Multivitamin", false, LocalTime.of(8, 0))), s.heute { "Platz $it" })
        // Was die Uhr heute meldete, gilt - mit den Planzeiten dazu.
        val gemeldet = SupCycle.Stand(heute, listOf("Multivitamin", "Abends"), 0b11, 0b01)
        assertEquals(LocalTime.of(20, 0), SupCycle.standFuer(heute, zone, gemeldet, plan)!!.heute { "" }[1].zeit)
        assertNull(SupCycle.standFuer(heute, zone, gestern, emptyList()))
    }

    @Test
    fun zurueck_nur_bei_gleichem_namen_und_weiter_faellig() {
        val namen = listOf("Vit D", "Zink", "Maca")
        // Platz 0 abgewaehlt: geloescht. Platz 1 umbenannt: die Einnahme bleibt. Platz 2 nicht mehr faellig: bleibt.
        assertEquals(listOf(0), SupCycle.zurueckgenommen(0b111, 0b011, 0b000, namen, listOf("Vit D", "Zinc", "Maca")))
        // Nicht eingetragen heisst: nichts zu loeschen.
        assertEquals(emptyList(), SupCycle.zurueckgenommen(0b000, 0b111, 0b000, namen, namen))
    }

    @Test
    fun ein_umbenannter_platz_vergisst_eingetragen_und_verworfen() {
        // Platz 1 heisst jetzt anders: was zu "Zink" gemerkt war, gilt fuer "Zinc" nicht.
        assertEquals(0b101L, SupCycle.gleicheNamen(listOf("Vit D", "Zink", "Maca"), listOf("Vit D", "Zinc", "Maca")) and 0b111L)
        // Ohne Namen (aeltere SupCycle) bleibt alles, wie es war.
        assertEquals(-1L, SupCycle.gleicheNamen(listOf("Vit D"), listOf("Vit D")))
        // Offline umbenannt und wieder abgehakt: der neue Haken ist neu und wird eingetragen.
        val gleich = SupCycle.gleicheNamen(listOf("Zink"), listOf("Zinc"))
        assertEquals(listOf(0), SupCycle.neuGenommen(0b1, 0b1, 0b1L and gleich))
    }

    @Test
    fun der_vortag_merkt_was_nicht_in_der_akte_ist() {
        // Faellig 0..2, abgehakt 0..2; eingetragen nur 0, Platz 2 im Strahl verworfen: es fehlt Platz 1.
        assertEquals(0b010L, SupCycle.fehlt(faellig = 0b111, genommen = 0b111, eingetragen = 0b001, verworfen = 0b100))
        // Abgehakt, aber nicht faellig: nichts nachzutragen.
        assertEquals(0L, SupCycle.fehlt(0b001, 0b011, 0b001, 0))
        // Der Nachtrag nimmt nur die fehlenden Plaetze, nicht noch einmal die eingetragenen.
        assertEquals(listOf(1), SupCycle.neuGenommen(0b111, 0b111, 0b010L.inv()))
        assertEquals(0b1010L, SupCycle.maske(listOf(1, 3)))
    }

    @Test
    fun nur_ein_stand_desselben_tages_ersetzt_den_liegengebliebenen() {
        fun stand(tag: Long, genommen: Long) = KieselNachricht(mapOf(SupCycle.SC_TODAY to tag, SupCycle.SC_DUE to 0b11L, SupCycle.SC_TAKEN to genommen))
        assertTrue(SupCycle.selberTag(stand(20261001, 0b01), stand(20261001, 0b11)))
        // Der gescheiterte Stand von gestern bleibt liegen, wenn heute der erste Bericht kommt.
        assertFalse(SupCycle.selberTag(stand(20261001, 0b11), stand(20261002, 0b00)))
    }

    @Test
    fun platz_aus_der_kennung_nur_vom_selben_tag() {
        val tag = LocalDate.of(2026, 10, 2)
        assertEquals(3, SupCycle.platzAus(SupCycle.kennung(tag, 3, "Vit D"), "2026-10-02"))
        // Ein umbenannter Platz bekommt eine eigene Kennung - sein Haken ersetzt die Einnahme des alten nicht.
        assertNotEquals(SupCycle.kennung(tag, 3, "Vit D"), SupCycle.kennung(tag, 3, "Vitamin D3"))
        assertEquals(3, SupCycle.platzAus("supcycle-2026-10-02-3", "2026-10-02"))
        assertNull(SupCycle.platzAus("supcycle-2026-10-01-3", "2026-10-02"))
        assertNull(SupCycle.platzAus(null, "2026-10-02"))
        assertNull(SupCycle.platzAus("koffein-123", "2026-10-02"))
    }

    @Test
    fun offen_erst_ab_der_planzeit() {
        val g = Gesundheitsrechnung
        val liste = listOf(
            SupCycle.Eintrag("Multivitamin", true, LocalTime.of(8, 0)),
            SupCycle.Eintrag("Black Maca", false, LocalTime.of(12, 30)),
            SupCycle.Eintrag("Ashwagandha", false, LocalTime.of(20, 0)),
        )
        // Um 08:05 ist nach dem Morgenhaken nichts versaeumt - der Rest ist nur faellig.
        val morgens = g.praeparatzeile(liste, LocalTime.of(8, 5))
        assertEquals(Praeparatlage.FAELLIG, morgens.lage)
        assertEquals(listOf("Black Maca", "Ashwagandha"), morgens.namen)
        // Ab 12:30 ist Black Maca offen, Ashwagandha noch nicht.
        val mittags = g.praeparatzeile(liste, LocalTime.of(12, 30))
        assertEquals(Praeparatlage.OFFEN, mittags.lage)
        assertEquals(listOf("Black Maca"), mittags.namen)
        assertEquals(0, mittags.weitere)
    }

    private val zuerich = ZoneId.of("Europe/Zurich")
    private val dritter = LocalDate.of(2026, 10, 3)
    private fun um(h: Int, m: Int, tag: LocalDate = dritter): Instant = tag.atTime(h, m).atZone(zuerich).toInstant()

    @Test
    fun hakenzeiten_wie_plan_taken_at_to_bytes() {
        val zeiten = listOf(um(8, 0).epochSecond, 0L, um(20, 15).epochSecond, 0L, 0L, 0L)
        val bytes = SupCycle.hakenzeitenBytes(zeiten)
        assertEquals(24, bytes.size)
        // Little endian, Platz fuer Platz - wie die Uhr sie schreibt.
        assertEquals((um(8, 0).epochSecond and 0xFF).toByte(), bytes[0])
        assertEquals(zeiten, SupCycle.hakenzeiten(bytes))
        // Eine aeltere Uhr-App schickt keine Zeiten; eine kaputte Laenge zaehlt wie keine.
        assertNull(SupCycle.hakenzeiten(null))
        assertNull(SupCycle.hakenzeiten(ByteArray(0)))
        assertNull(SupCycle.hakenzeiten(ByteArray(23)))
    }

    @Test
    fun eingetragen_wird_zur_hakenzeit() {
        val jetzt = um(21, 0)
        // Um 08:00 offline abgehakt, um 21:00 verbunden: die Einnahme steht auf 08:00.
        assertEquals(um(8, 0), SupCycle.eintragZeit(dritter, um(8, 0).epochSecond, LocalTime.of(8, 0), jetzt, zuerich))
        // Ohne Zeit (aeltere Uhr-App) wie bisher: heute die Ankunft ...
        assertEquals(jetzt, SupCycle.eintragZeit(dritter, null, LocalTime.of(8, 0), jetzt, zuerich))
        assertEquals(jetzt, SupCycle.eintragZeit(dritter, 0L, LocalTime.of(8, 0), jetzt, zuerich))
        // ... an einem frueheren Tag dessen Planzeit.
        val morgen = um(7, 0, dritter.plusDays(1))
        assertEquals(um(20, 0), SupCycle.eintragZeit(dritter, null, LocalTime.of(20, 0), morgen, zuerich))
        // Ein liegengebliebener Stand von gestern behaelt seine Hakenzeit.
        assertEquals(um(22, 30), SupCycle.eintragZeit(dritter, um(22, 30).epochSecond, LocalTime.of(20, 0), morgen, zuerich))
        // Eine Zeit ausserhalb des Tages (Uhr ohne Zeit nach einem Neustart) zaehlt nicht.
        assertEquals(jetzt, SupCycle.eintragZeit(dritter, 86_400L, LocalTime.of(8, 0), jetzt, zuerich))
        // Geht die Uhr vor, nie spaeter als jetzt.
        assertEquals(jetzt, SupCycle.eintragZeit(dritter, jetzt.epochSecond + 90, null, jetzt, zuerich))
    }

    @Test
    fun ein_haken_mit_neuer_zeit_ist_eine_neue_einnahme() {
        val alt = listOf(100L, 200L, 0L)
        assertEquals(0b101L, SupCycle.gleicheZeiten(alt, listOf(100L, 250L, 0L)) and 0b111L)
        // Ohne Zeiten auf einer Seite (aeltere Uhr-App, erster Bericht nach dem Update): alle gleich.
        assertEquals(-1L, SupCycle.gleicheZeiten(null, alt))
        assertEquals(-1L, SupCycle.gleicheZeiten(alt, null))
    }

    @Test
    fun der_befehl_an_die_uhr() {
        val r = SupCycle.Ruecknahme(dritter, 0b101, listOf(111L, 0L, 333L, 0L, 0L, 0L))
        val f = r.felder()
        assertEquals(20261003, f[SupCycle.SC_TODAY])
        assertEquals(0b101, f[SupCycle.SC_UNTAKE])
        assertEquals(r.zeiten, SupCycle.hakenzeiten(f[SupCycle.SC_TAKEN_AT] as ByteArray))
    }

    @Test
    fun vorgemerkt_behaelt_die_zeit_jedes_platzes() {
        val erst = SupCycle.vorgemerkt(null, dritter, 0b001, listOf(100L, 200L, 300L))
        assertEquals(SupCycle.Ruecknahme(dritter, 0b001, listOf(100L, 0L, 0L)), erst)
        // Platz 0 wurde inzwischen neu abgehakt (Zeit 150): sein Befehl meint weiter den alten Haken.
        val dann = SupCycle.vorgemerkt(erst, dritter, 0b100, listOf(150L, 200L, 300L))
        assertEquals(SupCycle.Ruecknahme(dritter, 0b101, listOf(100L, 0L, 300L)), dann)
        // Ein neuer Tag faengt neu an.
        assertEquals(SupCycle.Ruecknahme(dritter.plusDays(1), 0b010, listOf(0L, 200L, 0L)), SupCycle.vorgemerkt(dann, dritter.plusDays(1), 0b010, listOf(100L, 200L, 300L)))
    }

    @Test
    fun der_bericht_der_uhr_erledigt_den_befehl() {
        val offen = SupCycle.Ruecknahme(dritter, 0b011, listOf(100L, 200L))
        // Platz 0 ist weg, Platz 1 noch mit derselben Zeit da: nur er bleibt offen.
        assertEquals(SupCycle.Ruecknahme(dritter, 0b010, listOf(100L, 200L)), SupCycle.nochOffen(offen, dritter, 0b010, listOf(0L, 200L)))
        // Platz 1 neu gesetzt: der Befehl ist ueberholt.
        assertNull(SupCycle.nochOffen(offen, dritter, 0b010, listOf(0L, 250L)))
        // Neuer Tag oder eine Uhr-App ohne Zeiten: nichts mehr zu tun.
        assertNull(SupCycle.nochOffen(offen, dritter.plusDays(1), 0b011, listOf(100L, 200L)))
        assertNull(SupCycle.nochOffen(offen, dritter, 0b011, null))
        assertNull(SupCycle.nochOffen(null, dritter, 0b011, listOf(100L, 200L)))
    }

    @Test
    fun ein_uebergangener_befehl_bleibt_offen() {
        // Die Uhr bestaetigte, uebersprang den Befehl aber (anderer Tag) und meldet
        // den Haken unveraendert: er geht beim naechsten Start wieder hinaus.
        val offen = SupCycle.Ruecknahme(dritter, 0b001, listOf(100L, 0L))
        assertEquals(offen, SupCycle.nochOffen(offen, dritter, 0b001, listOf(100L, 0L)))
        // Erst ihr Stand ohne den Haken erledigt ihn.
        assertNull(SupCycle.nochOffen(offen, dritter, 0b000, listOf(0L, 0L)))
    }

    @Test
    fun ein_neu_gesetzter_haken_ersetzt_seinen_eintrag() {
        // Um 08:00 eingetragen, offline zurueckgenommen und um 09:00 neu gesetzt.
        val vorher = listOf(um(8, 0).epochSecond, 0L)
        val nachher = listOf(um(9, 0).epochSecond, 0L)
        // Der Haken gilt als neu und wird eingetragen ...
        assertEquals(0L, SupCycle.gleicheZeiten(vorher, nachher) and 0b1L)
        // ... unter derselben Kennung (Tag, Platz, Name - keine Zeit), mit hoeherer
        // Fassung: die Akte verschiebt den Eintrag von 08:00 auf 09:00, statt einen
        // zweiten anzulegen.
        assertTrue(SupCycle.fassung(nachher[0]) > SupCycle.fassung(vorher[0]))
        // Haken von vor dem Update und aeltere Uhr-Apps: Fassung 0 wie bisher.
        assertEquals(0L, SupCycle.fassung(null))
        assertEquals(0L, SupCycle.fassung(0L))
    }
}
