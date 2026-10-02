package coredevices.pebble.kiesel

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GetraenkeTest {
    private val g = Gesundheitsrechnung

    @Test
    fun eine_fremde_mahlzeit_ist_kein_getraenk() {
        // "Breakfast" aus einer Essens-App: 420 kcal, kein Koffein.
        assertNull(g.einordnung(eigen = false, kennung = null, koffeinMg = null, kcal = 420.0))
        assertEquals(Eintragsart.GETRAENK, g.einordnung(false, null, 80.0, 25.0))
        // Eine Mahlzeit ohne kcal-Angabe ist auch kein Praeparat - ein Kaffee zum Fruehstueck bleibt ein Getraenk.
        assertNull(g.einordnung(false, null, null, null, mahlzeit = true))
        assertEquals(Eintragsart.GETRAENK, g.einordnung(false, null, 95.0, 2.0, mahlzeit = true))
    }

    @Test
    fun praeparat_ist_supcycle_oder_ein_eintrag_ohne_inhalt() {
        assertEquals(Eintragsart.PRAEPARAT, g.einordnung(true, "supcycle-2026-10-02-1", null, null))
        assertEquals(Eintragsart.PRAEPARAT, g.einordnung(false, null, null, null))
        // Ein fremdes Praeparat mit 0 kcal statt ohne Angabe bleibt eines.
        assertEquals(Eintragsart.PRAEPARAT, g.einordnung(false, null, null, 0.0))
    }

    @Test
    fun eigene_getraenke_bleiben_getraenke_auch_ohne_koffein() {
        // Ein koffeinfreier Kaffee oder ein eigenes Getraenk ohne kcal von der Uhr.
        assertEquals(Eintragsart.GETRAENK, g.einordnung(true, "drinktervall-kaffee-1790000000", null, 2.0))
        assertEquals(Eintragsart.GETRAENK, g.einordnung(true, "drinktervall-kaffee-1790000000", null, 0.0))
        assertEquals(Eintragsart.GETRAENK, g.einordnung(true, "koffein-1790000000", 95.0, 2.0))
        // Dieselbe Kennung aus einer fremden App zaehlt nicht.
        assertNull(g.einordnung(false, "koffein-1", null, 120.0))
    }

    @Test
    fun koffein_heute_zaehlt_nur_den_kalendertag() {
        val zone = ZoneId.of("Europe/Zurich")
        val heute = LocalDate.of(2026, 10, 2)
        val dosen = listOf(
            heute.minusDays(1).atTime(23, 50).atZone(zone).toInstant() to 63.0,
            heute.atTime(7, 30).atZone(zone).toInstant() to 95.0,
            heute.atTime(14, 0).atZone(zone).toInstant() to 40.0,
        )
        assertEquals(135.0, g.koffeinHeute(dosen, heute, zone), 0.001)
        assertEquals(0.0, g.koffeinHeute(emptyList<Pair<Instant, Double>>(), heute, zone), 0.001)
    }
}
