package coredevices.pebble.kiesel

import io.rebble.libpebblecommon.database.entity.NachtEntity
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TagesurteileTest {
    private val g = Gesundheitsrechnung

    private fun nacht(schlaf: Int, wach: Int = 0, ruhepuls: Int? = 55, hrv: Int? = 52, mitAbschnitten: Boolean = true) = Nacht(
        beginnUtc = 0, einschlafen = Instant.ofEpochSecond(0), aufwachen = Instant.ofEpochSecond((schlaf + wach) * 60L),
        schlafMinuten = schlaf, phasen = intArrayOf(wach, schlaf, 0, 0, 0),
        abschnitte = if (mitAbschnitten) listOf(Nacht.Abschnitt(Instant.ofEpochSecond(0), Instant.ofEpochSecond(schlaf * 60L), 1)) else emptyList(),
        ruhepuls = ruhepuls, hrv = hrv,
    )

    private fun stand(
        schritte: Long = 3860, nacht: Nacht? = nacht(500), puls: List<Pair<Long, Int>> = listOf(1L to 68),
        ruhepulsWoche: List<Int> = emptyList(), tagesherz: List<Gesundheitsrechnung.Tagesherz> = emptyList(),
        supplemente: SupCycle.Stand? = null, mitSupCycle: Boolean = true,
    ) = GesundheitsStand(
        schritte = schritte, aktivMinuten = 22, meter = 2700.0, wocheSchritte = emptyList(), nacht = nacht,
        ruhepulsWoche = ruhepulsWoche, wocheSchlaf = emptyList(), puls = puls, spo2 = emptyList(),
        wasserMl = 0, glaeser = emptyList(), wasserZiel = null, supplemente = supplemente,
        koffein = emptyList(), letzteDaten = null, tagesherz = tagesherz, mitSupCycle = mitSupCycle,
    )

    private val tag = LocalDate.of(2026, 10, 2)
    private fun um(stunde: Int, minute: Int = 0) = tag.atTime(stunde, minute)
    private fun satz(stand: GesundheitsStand, jetzt: LocalDateTime) = g.tagesSatz(stand, jetzt) { "Slot $it" }

    @Test
    fun tageszeit_wechselt_um_elf_und_um_sechs() {
        assertEquals(Tageszeit.MORGEN, g.tageszeit(LocalTime.of(10, 59)))
        assertEquals(Tageszeit.TAG, g.tageszeit(LocalTime.of(11, 0)))
        assertEquals(Tageszeit.TAG, g.tageszeit(LocalTime.of(17, 59)))
        assertEquals(Tageszeit.ABEND, g.tageszeit(LocalTime.of(18, 0)))
    }

    @Test
    fun schlafurteil_nach_den_schwellen() {
        assertEquals(Schlafurteil.AUSGERUHT, g.schlafurteil(480))
        assertEquals(Schlafurteil.KURZ, g.schlafurteil(479))
        assertEquals(Schlafurteil.KURZ, g.schlafurteil(420))
        assertEquals(Schlafurteil.SEHR_KURZ, g.schlafurteil(419))
    }

    @Test
    fun mittags_mit_nacht_ausgeruht_und_durchgeschlafen() {
        val s = satz(stand(), um(13, 40))
        assertEquals(Tageszeit.TAG, s.tageszeit)
        assertEquals(Schlafurteil.AUSGERUHT, s.urteil)
        assertEquals(500, s.schlafMinuten)
        assertTrue(s.durchgeschlafen)
        assertEquals(3860, s.schritte)
        assertFalse(s.ohneDaten)
    }

    @Test
    fun morgens_spricht_die_nacht() {
        val s = satz(stand(schritte = 212, nacht = nacht(400, wach = 25)), um(6, 30))
        assertEquals(Tageszeit.MORGEN, s.tageszeit)
        assertEquals(Schlafurteil.SEHR_KURZ, s.urteil)
        assertFalse(s.durchgeschlafen)
        assertEquals(Tageszeit.ABEND, satz(stand(), um(20, 0)).tageszeit)
    }

    @Test
    fun ohne_nacht_kein_urteil_und_ohne_alles_der_ruhige_zustand() {
        val ohneNacht = satz(stand(nacht = null), um(13, 40))
        assertNull(ohneNacht.urteil)
        assertNull(ohneNacht.schlafMinuten)
        assertNull(ohneNacht.ruhe)
        assertFalse(ohneNacht.durchgeschlafen)
        assertFalse(ohneNacht.ohneDaten)
        assertTrue(satz(stand(schritte = 0, nacht = null, puls = emptyList()), um(13, 40)).ohneDaten)
    }

    @Test
    fun zehn_wachminuten_sind_nicht_mehr_durchgeschlafen() {
        assertTrue(satz(stand(nacht = nacht(480, wach = 9)), um(7)).durchgeschlafen)
        assertFalse(satz(stand(nacht = nacht(480, wach = 10)), um(7)).durchgeschlafen)
    }

    @Test
    fun satz_nennt_den_ruhepuls_gegen_die_frueheren_naechte() {
        // Dasselbe Urteil wie die Herzseite: 58 gegen 55 sind 3 bpm darueber.
        val s = satz(stand(nacht = nacht(480, ruhepuls = 58), ruhepulsWoche = listOf(55, 55, 55)), um(7))
        assertEquals(Herzurteil(3, 55.0, Vergleich.HOEHER, 3), s.ruhe)
    }

    @Test
    fun satz_nennt_die_belastung_des_tages_von_jetzt() {
        // Fuenf Lerntage mit 30 Minuten Belastung, heute 60: mehr als sonst.
        val frueher = (1..5).map { Gesundheitsrechnung.Tagesherz(tag.minusDays(it.toLong()), 70, 30, 100) }
        val heute = Gesundheitsrechnung.Tagesherz(tag, 72, 60, 100)
        assertEquals(Vergleich.HOEHER, satz(stand(tagesherz = frueher + heute), um(15)).haerte)
        // Am Tag danach ist "heute" ein anderer Tag - dann gibt es kein Urteil.
        assertNull(g.tagesSatz(stand(tagesherz = frueher + heute), um(15).plusDays(1)) { "" }.haerte)
    }

    @Test
    fun im_satz_steht_nur_ein_versaeumtes_praeparat() {
        // Platz 1 um acht, Platz 2 ohne Namen um zwoelf, keiner genommen.
        val plan = listOf(
            SupCycle.Platz("Multivitamin", LocalTime.of(8, 0), true, 1, 0, 0, 0),
            SupCycle.Platz("", LocalTime.of(12, 0), true, 1, 0, 0, 0),
        )
        val stand = SupCycle.Stand(tag, listOf("Multivitamin", ""), faellig = 0b11, genommen = 0, plan = plan)
        assertNull(satz(stand(supplemente = stand), um(7, 59)).offen)
        assertEquals("Multivitamin", satz(stand(supplemente = stand), um(8)).offen)
        // Genommen ist es kein Thema mehr; dann ist der namenlose Platz der erste versaeumte.
        val genommen = stand.copy(genommen = 0b01)
        assertNull(satz(stand(supplemente = genommen), um(11)).offen)
        assertEquals("Slot 2", satz(stand(supplemente = genommen), um(12)).offen)
        // Ohne SupCycle auf der Uhr keine Praeparate.
        assertNull(satz(stand(supplemente = stand, mitSupCycle = false), um(13)).offen)
    }

    @Test
    fun ruhepuls_lernt_bis_drei_fruehere_naechte() {
        val u = g.ruhepulsUrteil(55, listOf(54, 56))
        assertEquals(2, u.frueher)
        assertNull(u.urteil)
        assertEquals(55.0, u.schnitt)
    }

    @Test
    fun ruhepuls_wie_immer_innerhalb_von_zwei_schlaegen() {
        assertEquals(Vergleich.WIE_IMMER, g.ruhepulsUrteil(57, listOf(55, 55, 55)).urteil)
        val hoeher = g.ruhepulsUrteil(58, listOf(55, 55, 55))
        assertEquals(Vergleich.HOEHER, hoeher.urteil)
        assertEquals(3, hoeher.abstand)
        assertEquals(Vergleich.TIEFER, g.ruhepulsUrteil(52, listOf(55, 55, 55)).urteil)
    }

    private fun hrvTag(wert: Int?, band: Pair<Int, Int>?) = Gesundheitsrechnung.HrvTag(LocalDate.of(2026, 10, 2), wert, band)

    @Test
    fun hrv_wie_immer_im_band() {
        assertEquals(Bandurteil(Vergleich.WIE_IMMER, 0), g.hrvUrteil(hrvTag(51, 51 to 63)))
        assertEquals(Bandurteil(Vergleich.WIE_IMMER, 0), g.hrvUrteil(hrvTag(63, 51 to 63)))
        // Der Abstand zum Bandrand, nicht zur Mitte: knapp daneben heisst 1 ms daneben.
        assertEquals(Bandurteil(Vergleich.HOEHER, 1), g.hrvUrteil(hrvTag(64, 51 to 63)))
        assertEquals(Bandurteil(Vergleich.TIEFER, 3), g.hrvUrteil(hrvTag(48, 51 to 63)))
    }

    @Test
    fun hrv_ohne_band_oder_wert_kein_urteil() {
        assertNull(g.hrvUrteil(hrvTag(55, null)))
        assertNull(g.hrvUrteil(hrvTag(null, 51 to 63)))
        assertNull(g.hrvUrteil(null))
    }

    @Test
    fun hrv_urteil_sagt_dasselbe_wie_die_hrv_karte() {
        // 30 Naechte abwechselnd 40 und 56 ms: Band 40-56. 55 ms liegen darin, obwohl
        // sie gut zehn Prozent ueber dem Schnitt der letzten sieben Naechte liegen -
        // nach der alten Regel hiess das im Satz "above your usual", in der Karte "in range".
        val heute = LocalDate.of(2026, 10, 2)
        val werte = (1..30).associate { heute.minusDays(it.toLong()) to if (it % 2 == 0) 40 else 56 } + (heute to 55)
        val letzte = g.hrvWoche(werte, heute).last()
        assertEquals(40 to 56, letzte.band)
        assertEquals(true, letzte.imBand)
        assertEquals(Vergleich.WIE_IMMER, g.hrvUrteil(letzte)!!.urteil)
    }

    @Test
    fun hoechstens_sieben_fruehere_naechte_die_juengsten() {
        val u = g.ruhepulsUrteil(50, listOf(90, 50, 50, 50, 50, 50, 50, 50))
        assertEquals(7, u.frueher)
        assertEquals(50.0, u.schnitt)
        assertEquals(Vergleich.WIE_IMMER, u.urteil)
    }

    @Test
    fun ohne_letzten_wert_kein_urteil_aber_der_lernstand() {
        val u = g.ruhepulsUrteil(null, listOf(50, 52, 54))
        assertEquals(3, u.frueher)
        assertNull(u.urteil)
    }

    @Test
    fun naechteschnitt_nur_gemessene_und_hoechstens_sieben() {
        // Die 90 faellt aus den letzten sieben gemessenen, die ungemessene Nacht zaehlt nicht mit.
        assertEquals(7 to 50.0, g.naechteSchnitt(listOf(90, 50, 0, 52, 48, 51, 49, 50, 50)))
        assertEquals(0 to null, g.naechteSchnitt(listOf(0, 0)))
    }

    @Test
    fun puls_ist_jetzt_bis_eine_viertelstunde() {
        assertTrue(g.pulsFrisch(1000, 1000 + 15 * 60))
        assertFalse(g.pulsFrisch(1000, 1000 + 15 * 60 + 1))
    }

    @Test
    fun praeparate_ohne_planzeit_erst_ab_elf_offen() {
        fun e(name: String, genommen: Boolean) = SupCycle.Eintrag(name, genommen)
        val morgens = g.praeparatzeile(listOf(e("Multivitamin", false), e("Creatine", false), e("Zinc", false), e("Magnesium", false)), LocalTime.of(10, 59))
        assertEquals(Praeparatlage.FAELLIG, morgens.lage)
        assertEquals(listOf("Multivitamin", "Creatine"), morgens.namen)
        assertEquals(2, morgens.weitere)
        // Drei schon genommen, um 10:59: der vierte ist faellig, nicht versaeumt.
        val drei = listOf(e("Multivitamin", true), e("Creatine", true), e("Zinc", true), e("Magnesium", false))
        assertEquals(Praeparatlage.FAELLIG, g.praeparatzeile(drei, LocalTime.of(10, 59)).lage)
        val mittags = g.praeparatzeile(drei, LocalTime.of(11, 0))
        assertEquals(Praeparatlage.OFFEN, mittags.lage)
        assertEquals(listOf("Magnesium"), mittags.namen)
        assertEquals(3, mittags.genommen)
        assertEquals(4, mittags.faellig)
        assertEquals(Praeparatlage.ALLE, g.praeparatzeile(listOf(e("Zinc", true)), LocalTime.of(20, 0)).lage)
        assertEquals(Praeparatlage.KEINE, g.praeparatzeile(emptyList(), LocalTime.of(20, 0)).lage)
        assertEquals(11, PRAEPARATE_OHNE_PLAN_AB)
    }

    @Test
    fun erholsam_ist_tief_und_rem_an_der_zeit_im_bett() {
        // Wach und Schlaf ohne Phase zaehlen zum Nenner, denn sie sind Zeit im Bett: 90 von 200 Minuten.
        assertEquals(0.45, g.erholsamAnteil(intArrayOf(10, 100, 50, 40, 0))!!, 1e-9)
        assertEquals(0.45, g.erholsamAnteil(intArrayOf(0, 100, 50, 40, 10))!!, 1e-9)
        // Ohne erkannte Phase kein Anteil - auch nicht null.
        assertNull(g.erholsamAnteil(intArrayOf(30, 0, 0, 0, 400)))
        assertNull(g.erholsamAnteil(IntArray(5)))
    }

    @Test
    fun mit_phasen_heisst_leicht_tief_oder_rem() {
        assertFalse(g.mitPhasen(intArrayOf(30, 0, 0, 0, 400)))
        assertTrue(g.mitPhasen(intArrayOf(0, 1, 0, 0, 0)))
        assertTrue(g.mitPhasen(intArrayOf(0, 0, 1, 0, 0)))
        assertTrue(g.mitPhasen(intArrayOf(0, 0, 0, 1, 0)))
    }

    @Test
    fun tagespuls_ab_drei_schlaegen_daneben() {
        assertEquals(Vergleich.HOEHER, g.tagespulsUrteil(3))
        assertEquals(Vergleich.WIE_IMMER, g.tagespulsUrteil(2))
        assertEquals(Vergleich.WIE_IMMER, g.tagespulsUrteil(0))
        assertEquals(Vergleich.WIE_IMMER, g.tagespulsUrteil(-2))
        assertEquals(Vergleich.TIEFER, g.tagespulsUrteil(-3))
    }

    @Test
    fun ohne_abschnitte_nicht_durchgeschlafen() {
        val s = satz(stand(nacht = nacht(500, mitAbschnitten = false)), um(7))
        assertFalse(s.durchgeschlafen)
        assertEquals(Schlafurteil.AUSGERUHT, s.urteil)
    }

    @Test
    fun urteil_folgt_dem_gerundeten_abstand() {
        // Schnitt 54.67, letzte 57: 2.33 bpm - angezeigt "2", also wie immer.
        assertEquals(Vergleich.WIE_IMMER, g.ruhepulsUrteil(57, listOf(55, 55, 54)).urteil)
        val hoeher = g.ruhepulsUrteil(58, listOf(55, 55, 54))
        assertEquals(Vergleich.HOEHER, hoeher.urteil)
        assertEquals(3, hoeher.abstand)
    }

    private val zone: ZoneId = ZoneOffset.UTC
    private fun nachtAm(tag: LocalDate, ruhepuls: Int = 55): NachtEntity {
        val beginn = tag.minusDays(1).atTime(20, 0).toEpochSecond(ZoneOffset.UTC)
        return NachtEntity(beginnUtc = beginn, utcZuLokal = 0, einschlafen = 160, aufwachen = 640, schlafMinuten = 470, ruhepuls = ruhepuls, hrv = 50)
    }

    @Test
    fun letzte_nacht_zaehlt_nicht_zum_schnitt() {
        val heute = LocalDate.of(2026, 9, 30)
        val naechte = (9 downTo 0).map { nachtAm(heute.minusDays(it.toLong()), ruhepuls = 50 + it) }
        val (letzte, frueher) = g.naechteTeilen(naechte, heute, zone)
        assertEquals(naechte.last(), letzte)
        assertFalse(letzte in frueher)
        assertEquals(Fenster.VERGLEICHSNAECHTE, frueher.size)
        assertEquals(naechte[8], frueher.last())
    }

    @Test
    fun ausgelassene_nacht_heisst_keine_letzte() {
        val heute = LocalDate.of(2026, 9, 30)
        // Mo->Di gemessen, Di->Mi nicht: am Mittwoch gibt es keine letzte Nacht.
        val naechte = listOf(nachtAm(heute.minusDays(2)), nachtAm(heute.minusDays(1)))
        val (letzte, frueher) = g.naechteTeilen(naechte, heute, zone)
        assertNull(letzte)
        assertEquals(naechte, frueher)
    }

    @Test
    fun tage_zurueck_nach_kalendertag() {
        val jetzt = LocalDate.of(2026, 9, 30).atTime(10, 0).toInstant(ZoneOffset.UTC)
        assertEquals(0L, g.tageZurueck(LocalDate.of(2026, 9, 30).atTime(0, 1).toInstant(ZoneOffset.UTC), jetzt, zone))
        assertEquals(1L, g.tageZurueck(LocalDate.of(2026, 9, 29).atTime(21, 14).toInstant(ZoneOffset.UTC), jetzt, zone))
        assertEquals(3L, g.tageZurueck(LocalDate.of(2026, 9, 27).atTime(12, 0).toInstant(ZoneOffset.UTC), jetzt, zone))
    }

    @Test
    fun ruhepuls_urteil_am_gezeigten_schnitt() {
        // Schnitt 52.5, gezeigt 53: 55 sind 2 daneben, also wie immer - nicht 2.5, gerundet 3.
        val u = g.ruhepulsUrteil(55, listOf(52, 53, 52, 53))
        assertEquals(Vergleich.WIE_IMMER, u.urteil)
        assertEquals(2, u.abstand)
        val tiefer = g.ruhepulsUrteil(50, listOf(52, 53, 52, 53))
        assertEquals(Vergleich.TIEFER, tiefer.urteil)
        assertEquals(3, tiefer.abstand)
    }

    @Test
    fun fruehere_naechte_nur_aus_der_woche_davor() {
        val heute = LocalDate.of(2026, 9, 30)
        // Zwei Wochen ohne Uhr: die Naechte von damals zaehlen nicht zum Schnitt.
        val alt = (15..20).map { nachtAm(heute.minusDays(it.toLong())) }
        val neu = listOf(nachtAm(heute.minusDays(2)), nachtAm(heute.minusDays(1)))
        assertEquals(neu, g.naechteTeilen(alt + neu, heute, zone).second)
        // Fuer eine Nacht mitten im Monat (Nachtansicht) gilt dasselbe, spaetere Naechte stoeren nicht.
        val mitte = heute.minusDays(10)
        val monat = (0..20).map { nachtAm(heute.minusDays(it.toLong())) }
        val (letzte, frueher) = g.naechteTeilen(monat, mitte, zone)
        assertEquals(nachtAm(mitte), letzte)
        assertEquals((7 downTo 1).map { nachtAm(mitte.minusDays(it.toLong())) }, frueher)
    }

    @Test
    fun sync_alt_erst_nach_einer_stunde() {
        val jetzt = Instant.ofEpochSecond(10_000)
        // Verbunden liegt der letzte Minutenwert 20 bis 35 Minuten zurueck - das ist nicht "alt".
        assertFalse(g.syncAlt(Instant.ofEpochSecond(10_000 - 35 * 60), jetzt))
        assertFalse(g.syncAlt(Instant.ofEpochSecond(10_000 - 60 * 60), jetzt))
        assertTrue(g.syncAlt(Instant.ofEpochSecond(10_000 - 60 * 60 - 1), jetzt))
        assertFalse(g.syncAlt(null, jetzt))
    }
}
