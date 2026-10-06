package coredevices.pebble.kiesel

import androidx.health.connect.client.records.ExerciseSessionRecord
import coredevices.pebble.kiesel.Tagesbandrechnung.Art
import coredevices.pebble.kiesel.Tagesbandrechnung.Ereignis
import coredevices.pebble.kiesel.Tagesbandrechnung.Marke
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Das Tagesband ohne Schirm: sein Zeitfenster, was darin steht, was noch
 * geplant ist, und wie dichte Symbole zusammengehen - an den Grenzen
 * knapp darunter und darueber.
 */
class TagesbandrechnungTest {
    private val zone = ZoneOffset.UTC
    private val heute = LocalDate.of(2026, 10, 2)
    private fun um(stunde: Int, minute: Int = 0, sekunde: Int = 0, tag: LocalDate = heute): Instant = tag.atTime(stunde, minute, sekunde).toInstant(zone)
    private val gestern = heute.minusDays(1)

    private fun training(beginn: Instant): TrainingEintrag {
        val sitzung = ExerciseSessionRecord(
            startTime = beginn, startZoneOffset = null, endTime = beginn.plusSeconds(1800), endZoneOffset = null,
            metadata = Kieselsport.vonDerUhr("probe-${beginn.epochSecond}"),
            exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
        )
        return TrainingEintrag(sitzung, Sport.Laufen, true, 5000.0)
    }

    private fun getraenk(zeit: Instant, name: String = "Coffee") = Tageseintrag(zeit, false, name, 95.0, id = name, eigen = true)
    private fun praeparat(zeit: Instant, name: String, eigen: Boolean = true) = Tageseintrag(zeit, true, name, null, id = name, eigen = eigen)

    /** Eine Nacht von [ein] bis [auf], ohne Abschnitte - fuer das Fenster zaehlt nur das Einschlafen. */
    private fun nacht(ein: Instant, auf: Instant) = Nacht(ein.epochSecond, ein, auf, 420, IntArray(5), emptyList(), 55, 50)

    private fun stand(
        glaeser: List<Glaseintrag> = emptyList(), eintraege: List<Tageseintrag> = emptyList(),
        mitDrinktervall: Boolean = true, mitSupCycle: Boolean = true, nacht: Nacht? = null,
        supplemente: SupCycle.Stand? = null, gestern: List<Tageseintrag> = emptyList(),
    ) = GesundheitsStand(
        schritte = 0, aktivMinuten = 0, meter = 0.0, wocheSchritte = emptyList(), nacht = nacht,
        ruhepulsWoche = emptyList(), wocheSchlaf = emptyList(), puls = emptyList(), spo2 = emptyList(),
        wasserMl = 0, glaeser = emptyList(), wasserZiel = null, supplemente = supplemente, koffein = emptyList(), letzteDaten = null,
        mitDrinktervall = mitDrinktervall, mitSupCycle = mitSupCycle, eintraege = eintraege, glasEintraege = glaeser, gestern = gestern,
    )

    /** Ein Band ohne Nacht um 14:00: von gestern 22:00 bis heute 22:15 (Schlafenszeit 22:00 plus 15 min). */
    private val fenster = Tagesbandrechnung.fenster(null, um(14), zone)

    // --- Das Zeitfenster ---

    @Test
    fun mit_nacht_beginnt_das_band_eine_viertelstunde_vor_dem_einschlafen() {
        val n = nacht(um(22, 40, tag = gestern), um(6, 30))
        val f = Tagesbandrechnung.fenster(n, um(9), zone)
        assertEquals(um(22, 25, tag = gestern), f.von)
        // Am Morgen reicht die Schlafenszeit (22:40 wie gestern) plus 15 min am weitesten.
        assertEquals(um(22, 55), f.bis)
    }

    @Test
    fun ueber_die_zeitumstellung_reicht_der_tag_von_24_stunden_am_weitesten() {
        // 29.3.2026 in Zuerich: die Uhr springt um 2:00 auf 3:00. 24 Stunden nach 21:45 ist es 22:45 Ortszeit,
        // die Schlafenszeit 22:00 plus 15 min waere frueher - das Band ist trotzdem einen ganzen Tag lang.
        val zuerich = java.time.ZoneId.of("Europe/Zurich")
        val ein = LocalDate.of(2026, 3, 28).atTime(22, 0).atZone(zuerich).toInstant()
        val n = nacht(ein, LocalDate.of(2026, 3, 29).atTime(7, 0).atZone(zuerich).toInstant())
        val f = Tagesbandrechnung.fenster(n, LocalDate.of(2026, 3, 29).atTime(14, 0).atZone(zuerich).toInstant(), zuerich)
        assertEquals(LocalDate.of(2026, 3, 29).atTime(22, 45).atZone(zuerich).toInstant(), f.bis)
        assertEquals(Duration.ofHours(24), Duration.between(f.von, f.bis))
    }

    @Test
    fun ohne_nacht_beginnt_es_zwei_stunden_vor_mitternacht() {
        assertEquals(um(22, 0, tag = gestern), fenster.von)
    }

    @Test
    fun das_ende_ist_das_spaeteste_von_tag_jetzt_und_schlafenszeit() {
        val n = nacht(um(22, 0, tag = gestern), um(6, 0))
        // 23:30, noch wach: der Tag endete um 21:45, die Schlafenszeit 22:00 ist vorbei - jetzt plus eine Stunde.
        assertEquals(um(0, 30, tag = heute.plusDays(1)), Tagesbandrechnung.fenster(n, um(23, 30), zone).bis)
        // Eingeschlafen um 23:30: der Tag endet 23:15, die Schlafenszeit 23:30 plus 15 min reicht weiter.
        val spaet = nacht(um(23, 30, tag = gestern), um(7, 0))
        assertEquals(um(23, 45), Tagesbandrechnung.fenster(spaet, um(14), zone).bis)
        // Ohne Nacht ist die Schlafenszeit 22:00, der Tag endet schon 22:00 - also 22:15.
        assertEquals(um(22, 15), fenster.bis)
    }

    // --- Was im Fenster steht ---

    @Test
    fun alles_im_fenster_nach_der_zeit() {
        val lauf = training(um(7, 30))
        val glas = Glaseintrag(um(9), "g1", eigen = true)
        val kaffee = getraenk(um(8))
        val zink = praeparat(um(6, 45), "Zinc")
        val e = Tagesbandrechnung.ereignisse(stand(listOf(glas), listOf(kaffee, zink)), listOf(lauf), fenster)
        assertEquals(listOf(Ereignis.Praeparate(listOf(zink)), Ereignis.Training(lauf), Ereignis.Getraenk(kaffee), Ereignis.Wasser(glas)), e)
    }

    @Test
    fun ab_dem_anfang_des_bands_auch_vom_vorabend() {
        // Ohne Nacht beginnt das Band gestern 22:00: der Lauf um 23:40 steht darin, der um 21:50 nicht.
        val spaet = training(um(23, 40, tag = gestern))
        val davor = training(um(21, 50, tag = gestern))
        // Ein eigener Kaffee um 23:10 von gestern - die Seite liest gestrige nur eigene.
        val kaffee = getraenk(um(23, 10, tag = gestern), "Late")
        val e = Tagesbandrechnung.ereignisse(stand(gestern = listOf(kaffee)), listOf(davor, spaet), fenster)
        assertEquals(listOf(Ereignis.Getraenk(kaffee), Ereignis.Training(spaet)), e)
    }

    @Test
    fun praeparate_einer_minute_sind_ein_ereignis_fremde_daneben() {
        // SupCycle hakt drei in einem Zug ab, Sekunden auseinander; eine fremde App traegt in derselben Minute ein.
        val a = praeparat(um(8, 0, 1), "Multivitamin")
        val b = praeparat(um(8, 0, 20), "Creatine")
        val c = praeparat(um(8, 0, 59), "Zinc")
        val fremd = praeparat(um(8, 0, 30), "Fish oil", eigen = false)
        val spaeter = praeparat(um(8, 1, 0), "Magnesium")
        val e = Tagesbandrechnung.ereignisse(stand(eintraege = listOf(a, b, fremd, c, spaeter)), emptyList(), fenster)
        val gruppen = e.map { it as Ereignis.Praeparate }
        assertEquals(listOf("Multivitamin, Creatine, Zinc", "Fish oil", "Magnesium"), gruppen.map { it.titel })
        assertEquals(listOf(true, false, true), gruppen.map { it.eigen })
        assertEquals(um(8, 0, 1), gruppen.first().zeit)
    }

    @Test
    fun ohne_uhr_apps_weder_getraenke_noch_praeparate() {
        val glas = Glaseintrag(um(9), "g1", eigen = true)
        val eintraege = listOf(getraenk(um(8)), praeparat(um(8), "Zinc"))
        val ohneDrinktervall = Tagesbandrechnung.ereignisse(stand(listOf(glas), eintraege, mitDrinktervall = false), emptyList(), fenster)
        assertTrue(ohneDrinktervall.all { it is Ereignis.Praeparate })
        val ohneSupCycle = Tagesbandrechnung.ereignisse(stand(listOf(glas), eintraege, mitSupCycle = false), emptyList(), fenster)
        assertTrue(ohneSupCycle.none { it is Ereignis.Praeparate })
        assertEquals(2, ohneSupCycle.size)
    }

    @Test
    fun gleiche_zeit_training_vor_wasser_vor_getraenk_vor_praeparat() {
        val zeit = um(12)
        val e = Tagesbandrechnung.ereignisse(
            stand(listOf(Glaseintrag(zeit, "g1", eigen = false, ml = 250)), listOf(praeparat(zeit, "Zinc"), getraenk(zeit))),
            listOf(training(zeit)), fenster,
        )
        assertEquals(listOf(Ereignis.Training::class, Ereignis.Wasser::class, Ereignis.Getraenk::class, Ereignis.Praeparate::class), e.map { it::class })
    }

    // --- Was noch geplant ist ---

    @Test
    fun geplant_sind_nur_offene_mit_planzeit_nach_jetzt() {
        val plan = SupCycle.Stand(
            heute, listOf("Zinc", "D3", "Magnesium", "Iron"), 0b1111, 0b0010,
            listOf(
                SupCycle.Platz("Zinc", LocalTime.of(8, 0), true, 1, 0, 0, 0),       // offen, vorbei: versaeumt
                SupCycle.Platz("D3", LocalTime.of(20, 0), true, 1, 0, 0, 0),        // genommen
                SupCycle.Platz("Magnesium", LocalTime.of(20, 0), true, 1, 0, 0, 0), // offen, kommt
                SupCycle.Platz("Iron", LocalTime.of(14, 1), true, 1, 0, 0, 0),      // offen, in einer Minute
            ),
        )
        val g = Tagesbandrechnung.geplant(stand(supplemente = plan), um(14), zone) { "Slot $it" }
        assertEquals(listOf(Tagesbandrechnung.Geplant(um(14, 1), "Iron"), Tagesbandrechnung.Geplant(um(20), "Magnesium")), g)
        // Genau zur Planzeit ist es nicht mehr geplant.
        assertEquals(listOf("Magnesium"), Tagesbandrechnung.geplant(stand(supplemente = plan), um(14, 1), zone) { "Slot $it" }.map { it.name })
        assertTrue(Tagesbandrechnung.geplant(stand(supplemente = plan, mitSupCycle = false), um(14), zone) { "Slot $it" }.isEmpty())
    }

    // --- Dichte Symbole ---

    /** Die Lage wie im Band: 40 dp je Stunde ab 10:00. */
    private val x: (Instant) -> Float = { Duration.between(um(10), it).toMinutes() * 40f / 60f }

    private fun marke(art: Art, zeit: Instant, geplant: Boolean = false) = Marke(art, zeit, geplant, "${art.name} $zeit")

    @Test
    fun gleiche_art_binnen_45_minuten_ist_ein_symbol() {
        val s = Tagesbandrechnung.anordnen(listOf(marke(Art.WASSER, um(10)), marke(Art.WASSER, um(10, 45))), x)
        assertEquals(1, s.size)
        assertEquals(2, s.single().marken.size)
        assertEquals(0f, s.single().x)
        // Eine Minute spaeter zwei Symbole - das zweite ist 30.7 dp weg und rueckt auf 40.
        val zwei = Tagesbandrechnung.anordnen(listOf(marke(Art.WASSER, um(10)), marke(Art.WASSER, um(10, 46))), x)
        assertEquals(listOf(0f, 40f), zwei.map { it.x })
        assertTrue(zwei.none { it.buendel })
    }

    @Test
    fun die_45_minuten_zaehlen_ab_dem_ersten_glas() {
        // 10:00, 10:30, 11:00: das dritte ist 60 min nach dem ersten - ein eigenes Symbol.
        val s = Tagesbandrechnung.anordnen(listOf(marke(Art.WASSER, um(10)), marke(Art.WASSER, um(10, 30)), marke(Art.WASSER, um(11))), x)
        assertEquals(listOf(2, 1), s.map { it.marken.size })
    }

    @Test
    fun geplantes_geht_nicht_mit_genommenem_zusammen() {
        // Eine halbe Stunde auseinander gingen sie als gleiche Art zusammen - geplant und genommen nicht.
        val s = Tagesbandrechnung.anordnen(listOf(marke(Art.PRAEPARAT, um(10)), marke(Art.PRAEPARAT, um(10, 30), geplant = true)), x)
        assertEquals(listOf(1, 1), s.map { it.marken.size })
    }

    @Test
    fun verschiedene_arten_ruecken_bis_24_dp_dann_buendel() {
        // 24 min = 16 dp auseinander: 24 dp Schub reichen genau.
        val geschoben = Tagesbandrechnung.anordnen(listOf(marke(Art.KAFFEE, um(10)), marke(Art.WASSER, um(10, 24))), x)
        assertEquals(listOf(0f, 40f), geschoben.map { it.x })
        assertTrue(geschoben.none { it.buendel })
        // 23 min = 15.3 dp: 24.7 dp waeren noetig - ein Buendel "+2" an der Stelle des ersten.
        val gebuendelt = Tagesbandrechnung.anordnen(listOf(marke(Art.KAFFEE, um(10)), marke(Art.WASSER, um(10, 23))), x)
        assertEquals(1, gebuendelt.size)
        assertTrue(gebuendelt.single().buendel)
        assertEquals(0f, gebuendelt.single().x)
        assertEquals(listOf(Art.KAFFEE, Art.WASSER), gebuendelt.single().marken.map { it.art })
        // Eine Stunde auseinander bleibt alles, wo es ist.
        assertEquals(listOf(0f, 40f), Tagesbandrechnung.anordnen(listOf(marke(Art.KAFFEE, um(10)), marke(Art.TRAINING, um(11))), x).map { it.x })
    }

    @Test
    fun ein_buendel_nimmt_weitere_nahe_auf() {
        val s = Tagesbandrechnung.anordnen(
            listOf(marke(Art.KAFFEE, um(10)), marke(Art.WASSER, um(10, 5)), marke(Art.TRAINING, um(10, 10)), marke(Art.BETT, um(12), geplant = true)),
            x,
        )
        assertEquals(2, s.size)
        assertEquals(3, s.first().marken.size)
        assertTrue(s.first().buendel)
        assertEquals(80f, s.last().x)
    }

    // --- Die Pulslinie ---

    /** Ein Puls je [sekunden] ab [ab]. */
    private fun puls(ab: Instant, vararg sekunden: Long, bpm: Int = 70) = sekunden.map { ab.epochSecond + it to bpm }

    @Test
    fun ueber_eine_viertelstunde_ohne_messung_bricht_die_linie() {
        // Der Fall der Gegenpruefung: 10:00 und 10:19 liegen in den Fenstern 10:00 und 10:15 - deren Anfaenge
        // trennen nur 15 Minuten, die Messungen aber 19. Die Linie bricht.
        assertEquals(2, Tagesbandrechnung.pulslinie(puls(um(10), 0, 19 * 60L)).size)
        // An der Grenze: genau 15 min ohne Messung bleibt eine Linie, eine Sekunde mehr bricht sie -
        // obwohl beide Male dieselben zwei Fenster (10:00 und 10:15) entstehen.
        assertEquals(1, Tagesbandrechnung.pulslinie(puls(um(10), 0, 900)).size)
        assertEquals(2, Tagesbandrechnung.pulslinie(puls(um(10), 0, 901)).size)
    }

    @Test
    fun jedes_stueck_traegt_die_mediane_seiner_fuenf_minuten() {
        // Jede Minute 10:00-10:09, dann 20 min nichts, dann jede Minute 10:30-10:39 - unsortiert hereingegeben.
        val punkte = (0..9).flatMap { m -> listOf(um(10, m).epochSecond to 60 + m, um(10, 30 + m).epochSecond to 90 + m) }
        val linie = Tagesbandrechnung.pulslinie(punkte)
        assertEquals(
            listOf(
                // Der obere Median der fuenf Minuten, am Anfang des Fensters.
                listOf(um(10).epochSecond to 62, um(10, 5).epochSecond to 67),
                listOf(um(10, 30).epochSecond to 92, um(10, 35).epochSecond to 97),
            ),
            linie,
        )
        assertTrue(Tagesbandrechnung.pulslinie(emptyList()).isEmpty())
    }

    // --- Die Bezugslinien und die Skala der Pulszeile ---

    /** Die Zonen von Ruhepuls 55 und Maximalpuls 185, wie die Herzseite sie rechnet: 107, 133, 159. */
    private val zonen = Gesundheitsrechnung.pulsZonen(55, 185)

    private fun ruhe(bpm: Int) = Tagesbandrechnung.Bezugslinie(bpm, null)
    private fun grenze(bpm: Int, zone: Int) = Tagesbandrechnung.Bezugslinie(bpm, zone)

    @Test
    fun ein_ruhiger_tag_reicht_bis_zur_ersten_grenze_ueber_seinem_hoechsten_wert() {
        assertEquals(listOf(107, 133, 159), zonen)
        // Hoechstens 104: oben die Grenze zu moderat (107) plus 5, unten Ruhepuls 55 minus 10.
        val s = Tagesbandrechnung.pulsskala(55, zonen, listOf(52, 60, 104, 70))
        assertEquals(Tagesbandrechnung.Pulsskala(45, 112, listOf(ruhe(55), grenze(107, 1))), s)
    }

    @Test
    fun ein_bewegter_tag_zeigt_alle_grenzen_bis_ueber_seinen_hoechsten_wert() {
        // Hoechstens 150: oben die Grenze zur Spitze (159) plus 5 - kraeftig und Spitze stehen im Bild.
        assertEquals(
            Tagesbandrechnung.Pulsskala(45, 164, listOf(ruhe(55), grenze(107, 1), grenze(133, 2), grenze(159, 3))),
            Tagesbandrechnung.pulsskala(55, zonen, listOf(52, 150, 90)),
        )
        // UEBER heisst darueber: genau auf der Grenze 133 ist man schon in "kraeftig" (pulsZone), die naechste ist 159.
        assertEquals(164, Tagesbandrechnung.pulsskala(55, zonen, listOf(60, 133)).hoch)
        assertEquals(133 + 5, Tagesbandrechnung.pulsskala(55, zonen, listOf(60, 132)).hoch)
        // Ueber der Spitzengrenze gibt es keine Grenze mehr: der hoechste Wert selbst, mit Luft.
        assertEquals(Tagesbandrechnung.Pulsskala(45, 177, listOf(ruhe(55), grenze(107, 1), grenze(133, 2), grenze(159, 3))),
            Tagesbandrechnung.pulsskala(55, zonen, listOf(60, 172)))
    }

    @Test
    fun unten_zehn_unter_dem_ruhepuls_tiefer_nur_wenn_der_puls_darunter_liegt() {
        assertEquals(45, Tagesbandrechnung.pulsskala(55, zonen, listOf(46, 80)).tief)
        assertEquals(45, Tagesbandrechnung.pulsskala(55, zonen, listOf(45, 80)).tief)
        assertEquals(41, Tagesbandrechnung.pulsskala(55, zonen, listOf(41, 80)).tief)
    }

    @Test
    fun nur_linien_in_der_skala() {
        // Ein Ruhepuls weit ueber dem Tag - eine fiebrige Nacht, 70 bpm: der Tag bleibt darunter. Die Ruhelinie
        // steht, die Grenzen 133 und 159 liegen ueber der Skala (bis 112) und fehlen.
        val s = Tagesbandrechnung.pulsskala(70, zonen, listOf(58, 66))
        assertEquals(Tagesbandrechnung.Pulsskala(58, 112, listOf(ruhe(70), grenze(107, 1))), s)
        assertTrue(s.linien.all { it.bpm in s.tief..s.hoch })
    }

    @Test
    fun ohne_ruhepuls_keine_ruhelinie_und_unten_zehn_unter_dem_tiefsten_wert() {
        val s = Tagesbandrechnung.pulsskala(null, zonen, listOf(58, 90))
        assertEquals(Tagesbandrechnung.Pulsskala(48, 112, listOf(grenze(107, 1))), s)
        // Eine 0 aus der Datenbank ist kein Ruhepuls.
        assertEquals(s, Tagesbandrechnung.pulsskala(0, zonen, listOf(58, 90)))
    }

    @Test
    fun ohne_puls_keine_linien() {
        // Die Linien stehen HINTER dem Puls - ohne ihn misst keine etwas. Die Skala bleibt brauchbar (unten < oben).
        val s = Tagesbandrechnung.pulsskala(55, zonen, emptyList())
        assertTrue(s.linien.isEmpty())
        assertTrue(s.tief < s.hoch, "$s")
        val ohneAlles = Tagesbandrechnung.pulsskala(null, emptyList(), emptyList())
        assertTrue(ohneAlles.linien.isEmpty() && ohneAlles.tief < ohneAlles.hoch, "$ohneAlles")
    }

    @Test
    fun ohne_zonen_nur_der_ruhepuls() {
        assertEquals(
            Tagesbandrechnung.Pulsskala(45, 109, listOf(ruhe(55))),
            Tagesbandrechnung.pulsskala(55, emptyList(), listOf(50, 104)),
        )
    }
}
