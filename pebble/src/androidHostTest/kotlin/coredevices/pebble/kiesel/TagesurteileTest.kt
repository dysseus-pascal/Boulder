package coredevices.pebble.kiesel

import io.rebble.libpebblecommon.database.entity.NachtEntity
import java.time.Instant
import java.time.LocalDate
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

    private fun stand(schritte: Long = 3860, nacht: Nacht? = nacht(500), puls: List<Pair<Long, Int>> = listOf(1L to 68)) = GesundheitsStand(
        schritte = schritte, aktivMinuten = 22, meter = 2700.0, kcal = 118, wocheSchritte = emptyList(), nacht = nacht,
        ruhepulsWoche = emptyList(), hrvWoche = emptyList(), wocheSchlaf = emptyList(), puls = puls, spo2 = emptyList(),
        wasserMl = 0, glaeser = emptyList(), wocheWasser = emptyList(), wasserZiel = null, supplemente = null,
        koffein = emptyList(), letzteDaten = null,
    )

    @Test
    fun tageszeit_wechselt_um_elf_und_um_sechs() {
        assertEquals(Tageszeit.MORGEN, g.tageszeit(LocalTime.of(10, 59)))
        assertEquals(Tageszeit.TAG, g.tageszeit(LocalTime.of(11, 0)))
        assertEquals(Tageszeit.TAG, g.tageszeit(LocalTime.of(17, 59)))
        assertEquals(Tageszeit.ABEND, g.tageszeit(LocalTime.of(18, 0)))
    }

    @Test
    fun schlafurteil_nach_den_schwellen_der_fussnote() {
        assertEquals(Schlafurteil.AUSGERUHT, g.schlafurteil(480))
        assertEquals(Schlafurteil.KURZ, g.schlafurteil(479))
        assertEquals(Schlafurteil.KURZ, g.schlafurteil(420))
        assertEquals(Schlafurteil.SEHR_KURZ, g.schlafurteil(419))
    }

    @Test
    fun mittags_mit_nacht_ausgeruht_und_schritte_zum_ziel() {
        val s = g.tagesSatz(stand(), LocalTime.of(13, 40))
        assertEquals(Tageszeit.TAG, s.tageszeit)
        assertEquals(Schlafurteil.AUSGERUHT, s.urteil)
        assertTrue(s.durchgeschlafen)
        assertEquals(20, s.schlafAbstand)
        assertEquals(Schrittlage.NOCH, s.schrittlage)
        assertEquals(6140, s.schritteFehlen)
        assertFalse(s.ohneDaten)
    }

    @Test
    fun morgens_spricht_die_nacht_und_der_tag_hat_eben_begonnen() {
        val s = g.tagesSatz(stand(schritte = 212, nacht = nacht(400, wach = 25)), LocalTime.of(6, 30))
        assertEquals(Tageszeit.MORGEN, s.tageszeit)
        assertEquals(Schlafurteil.SEHR_KURZ, s.urteil)
        assertFalse(s.durchgeschlafen)
        assertEquals(25, s.wachMinuten)
        assertEquals(-80, s.schlafAbstand)
        assertEquals(Schrittlage.BEGONNEN, s.schrittlage)
    }

    @Test
    fun abends_zaehlt_der_anteil_und_ein_erreichtes_ziel_gilt_immer() {
        assertEquals(Schrittlage.ANTEIL, g.tagesSatz(stand(schritte = 3860), LocalTime.of(20, 0)).schrittlage)
        assertEquals(38, g.tagesSatz(stand(schritte = 3860), LocalTime.of(20, 0)).zielProzent)
        assertEquals(Schrittlage.ERREICHT, g.tagesSatz(stand(schritte = 10_000), LocalTime.of(7, 0)).schrittlage)
        assertEquals(0, g.tagesSatz(stand(schritte = 12_000), LocalTime.of(13, 0)).schritteFehlen)
    }

    @Test
    fun ohne_nacht_kein_urteil_und_ohne_alles_der_ruhige_zustand() {
        val ohneNacht = g.tagesSatz(stand(nacht = null), LocalTime.of(13, 40))
        assertNull(ohneNacht.urteil)
        assertNull(ohneNacht.schlafAbstand)
        assertFalse(ohneNacht.durchgeschlafen)
        assertFalse(ohneNacht.ohneDaten)
        assertTrue(g.tagesSatz(stand(schritte = 0, nacht = null, puls = emptyList()), LocalTime.of(13, 40)).ohneDaten)
    }

    @Test
    fun zehn_wachminuten_sind_nicht_mehr_durchgeschlafen() {
        assertTrue(g.tagesSatz(stand(nacht = nacht(480, wach = 9)), LocalTime.of(7, 0)).durchgeschlafen)
        assertFalse(g.tagesSatz(stand(nacht = nacht(480, wach = 10)), LocalTime.of(7, 0)).durchgeschlafen)
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
    fun praeparate_erst_nach_dem_ersten_haken_offen() {
        fun e(name: String, genommen: Boolean) = SupCycle.Eintrag(name, genommen)
        val morgens = g.praeparatzeile(listOf(e("Multivitamin", false), e("Creatine", false), e("Zinc", false), e("Magnesium", false)))
        assertEquals(Praeparatlage.FAELLIG, morgens.lage)
        assertEquals(listOf("Multivitamin", "Creatine"), morgens.namen)
        assertEquals(2, morgens.weitere)
        val mittags = g.praeparatzeile(listOf(e("Multivitamin", true), e("Creatine", true), e("Zinc", true), e("Magnesium", false)))
        assertEquals(Praeparatlage.OFFEN, mittags.lage)
        assertEquals(listOf("Magnesium"), mittags.namen)
        assertEquals(3, mittags.genommen)
        assertEquals(4, mittags.faellig)
        assertEquals(Praeparatlage.ALLE, g.praeparatzeile(listOf(e("Zinc", true))).lage)
        assertEquals(Praeparatlage.KEINE, g.praeparatzeile(emptyList()).lage)
    }

    @Test
    fun schrittwoche_zaehlt_ab_dem_ersten_tag_mit_schritten() {
        val mi = LocalDate.of(2026, 9, 30)
        val woche = (6 downTo 0).map { mi.minusDays(it.toLong()) }.zip(listOf(0L, 0L, 0L, 0L, 3489L, 6482L, 3860L))
        val w = g.schrittwoche(woche)
        assertEquals(listOf(mi, mi.minusDays(1), mi.minusDays(2)), w.tage.map { it.tag })
        assertEquals(listOf(Tagesmerkmal.HEUTE, Tagesmerkmal.MEISTE, Tagesmerkmal.ERSTER), w.tage.map { it.merkmal })
        assertEquals(LocalDate.of(2026, 9, 28), w.seit)
        assertEquals(LocalDate.of(2026, 10, 4), w.volleWocheAb)
    }

    @Test
    fun volle_schrittwoche_ohne_fussnote_und_mit_zielen() {
        val mi = LocalDate.of(2026, 9, 30)
        val woche = (6 downTo 0).map { mi.minusDays(it.toLong()) }.zip(listOf(4000L, 11000L, 5000L, 12000L, 3000L, 2000L, 100L))
        val w = g.schrittwoche(woche)
        assertEquals(7, w.tage.size)
        assertNull(w.seit)
        assertNull(w.volleWocheAb)
        assertEquals(Tagesmerkmal.MEISTE, w.tage.first { it.schritte == 12000L }.merkmal)
        assertEquals(Tagesmerkmal.ZIEL, w.tage.first { it.schritte == 11000L }.merkmal)
        assertEquals(Tagesmerkmal.KEINS, w.tage.first { it.schritte == 4000L }.merkmal)
    }

    @Test
    fun phasenanteile_ergeben_hundert() {
        // Tief 1:18, REM 2:20, leicht 4:42 wie im Entwurf.
        assertEquals(listOf(16, 28, 56), g.phasenAnteile(intArrayOf(0, 282, 78, 140, 0)))
        assertEquals(100, g.phasenAnteile(intArrayOf(0, 100, 100, 100, 0)).sum())
        assertEquals(listOf(0, 0, 0), g.phasenAnteile(intArrayOf(10, 0, 0, 0, 0)))
    }

    @Test
    fun ring_mit_ueberlauf_hoechstens_eine_runde() {
        assertEquals(0.5f to 0f, g.ringAnteil(240.0, 480.0))
        val (voll, plus) = g.ringAnteil(500.0, 480.0)
        assertEquals(1f, voll)
        assertEquals(20f / 480f, plus, 0.0001f)
        assertEquals(1f to 1f, g.ringAnteil(2000.0, 480.0))
    }

    @Test
    fun ohne_abschnitte_weder_durchgeschlafen_noch_wachminuten() {
        val s = g.tagesSatz(stand(nacht = nacht(500, mitAbschnitten = false)), LocalTime.of(7, 0))
        assertFalse(s.durchgeschlafen)
        assertNull(s.wachMinuten)
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
