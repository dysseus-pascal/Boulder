package coredevices.pebble.kiesel

import coredevices.pebble.R
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Was das Widget gross zeigt: Vorrang der Lagen, ihre Uhrzeiten, die
 * Wasserkurve und die Praeparate. Die Woerter kommen als ihre Schluessel
 * ([Schluessel]) - so prueft der Test, WELCHER Text gewaehlt wurde, und
 * bleibt gruen, wenn sich der englische Wortlaut aendert.
 */
class WidgetlageTest {
    /** Ein Text ist sein Ressourcenname, die eingesetzten Werte in Klammern dahinter. */
    private object Schluessel : Texte {
        private val namen: Map<Int, String> by lazy {
            listOf(R.string::class.java, R.plurals::class.java).flatMap { it.fields.toList() }.associate { it.getInt(null) to it.name }
        }

        private fun mit(name: String, werte: Array<out Any>) = if (werte.isEmpty()) name else name + werte.joinToString(",", "(", ")")
        override fun text(id: Int, vararg a: Any) = mit(namen.getValue(id), a)
        override fun mehrzahl(id: Int, n: Int, vararg a: Any) = mit(namen.getValue(id), a)
    }

    private val tag = LocalDate.of(2026, 10, 2)
    private fun um(stunde: Int, minute: Int = 0): LocalDateTime = tag.atTime(stunde, minute)

    private fun blick(
        jetzt: LocalDateTime,
        schlafEnde: LocalDateTime? = null, schlafMin: Double? = null, schlafWocheMin: Double? = null,
        trainingEnde: LocalDateTime? = null,
        schritte: Double? = 4000.0,
        wasserMl: Double? = null, wasserZiel: Double? = null,
        praeparate: List<SupCycle.Eintrag> = emptyList(),
        wunsch: Widgetlage.Art? = null,
    ) = Widgetlage.Blick(
        jetzt = jetzt,
        schlafEnde = schlafEnde, schlafMin = schlafMin, schlafZielMin = 480.0, schlafWocheMin = schlafWocheMin, erholsamAnteil = null,
        ruhepuls = null, hrv = null,
        trainingEnde = trainingEnde, trainingName = trainingEnde?.let { "Run" }, trainingMin = trainingEnde?.let { 45L },
        trainingPuls = null, trainingKm = null, trainingBeginn = trainingEnde?.let { 1_000L },
        schritte = schritte, schritteZiel = 10_000.0, aktivMin = 10.0, aktivZiel = 30.0,
        wasserMl = wasserMl, wasserZiel = wasserZiel, glasMl = 250.0,
        praeparate = praeparate, koffeinMg = null, wunsch = wunsch,
    )

    private fun lage(b: Widgetlage.Blick) = Widgetlage.ermittle(b, Schluessel)
    private fun art(b: Widgetlage.Blick) = lage(b).art

    private val zink = SupCycle.Eintrag("Zinc", false)

    // --- Vorrang ---

    @Test
    fun training_vor_morgen_vor_erinnerung_vor_abend() {
        // Um neun am Abend gilt alles zugleich: Training vor einer Stunde, aufgewacht vor zwei, Zink offen.
        val jetzt = um(21)
        assertEquals(Widgetlage.Art.TRAINING, art(blick(jetzt, um(19), 450.0, trainingEnde = um(20), praeparate = listOf(zink))))
        assertEquals(Widgetlage.Art.MORGEN, art(blick(jetzt, um(19), 450.0, praeparate = listOf(zink))))
        assertEquals(Widgetlage.Art.ERINNERUNG, art(blick(jetzt, praeparate = listOf(zink))))
        assertEquals(Widgetlage.Art.ABEND, art(blick(jetzt)))
    }

    @Test
    fun ein_wunsch_geht_allem_vor() {
        val b = blick(um(21), um(19), 450.0, trainingEnde = um(20), praeparate = listOf(zink), wunsch = Widgetlage.Art.TAG)
        assertEquals(Widgetlage.Art.TAG, art(b))
    }

    // --- Uhrzeiten ---

    @Test
    fun training_zaehlt_zwei_stunden_nach_dem_ende() {
        assertEquals(Widgetlage.Art.TRAINING, art(blick(um(11, 59), trainingEnde = um(10))))
        assertEquals(Widgetlage.Art.TAG, art(blick(um(12), trainingEnde = um(10))))
        // Ein Ende in der Zukunft (falsche Uhr der Quelle) ist kein beendetes Training.
        assertEquals(Widgetlage.Art.TAG, art(blick(um(12), trainingEnde = um(12, 30))))
    }

    @Test
    fun morgen_drei_stunden_nach_dem_aufwachen_und_nur_mit_schlaf() {
        assertEquals(Widgetlage.Art.MORGEN, art(blick(um(9, 59), um(7), 450.0)))
        assertEquals(Widgetlage.Art.TAG, art(blick(um(10), um(7), 450.0)))
        // Ohne Schlafminuten gibt es nichts zu zeigen - dann ist es ein gewoehnlicher Tag.
        assertEquals(Widgetlage.Art.TAG, art(blick(um(8), um(7), null)))
    }

    @Test
    fun abend_ab_zwanzig_uhr_mit_erreichtem_ziel_ab_sechzehn() {
        assertEquals(Widgetlage.Art.TAG, art(blick(um(19, 59))))
        assertEquals(Widgetlage.Art.ABEND, art(blick(um(20))))
        assertEquals(Widgetlage.Art.TAG, art(blick(um(15, 59), schritte = 10_000.0)))
        assertEquals(Widgetlage.Art.ABEND, art(blick(um(16), schritte = 10_000.0)))
    }

    @Test
    fun mahnt_erst_ab_der_planzeit() {
        val plan = listOf(
            SupCycle.Eintrag("Multivitamin", true, LocalTime.of(8, 0)),
            SupCycle.Eintrag("Black Maca", false, LocalTime.of(12, 30)),
            SupCycle.Eintrag("Ashwagandha", false, LocalTime.of(20, 0)),
        )
        assertEquals(emptyList(), Widgetlage.versaeumtePraeparate(blick(um(11), praeparate = plan)))
        assertEquals(listOf("Black Maca"), Widgetlage.versaeumtePraeparate(blick(um(12, 30), praeparate = plan)))
        assertEquals(listOf("Black Maca", "Ashwagandha"), Widgetlage.versaeumtePraeparate(blick(um(20), praeparate = plan)))
    }

    @Test
    fun ohne_planzeit_ab_elf_uhr() {
        assertEquals(emptyList(), Widgetlage.versaeumtePraeparate(blick(um(10, 59), praeparate = listOf(zink))))
        assertEquals(listOf("Zinc"), Widgetlage.versaeumtePraeparate(blick(um(11), praeparate = listOf(zink))))
    }

    // --- Wasser ---

    @Test
    fun wassersoll_vorne_mehr() {
        val soll = mapOf(6.0 to 0.0, 8.0 to 0.0, 10.0 to 0.2, 12.0 to 0.4, 14.5 to 0.575, 17.0 to 0.75, 19.5 to 0.875, 22.0 to 1.0, 23.0 to 1.0)
        soll.forEach { (stunde, anteil) -> assertEquals(anteil, Widgetlage.wasserSoll(stunde), 1e-9, "um $stunde Uhr") }
    }

    @Test
    fun wasser_mahnt_ab_zwei_glaesern_hinter_der_kurve() {
        // Mittags sollen 40 % von 2 l getrunken sein, also 800 ml; ein Glas hat 250 ml.
        val hinterher = lage(blick(um(12), wasserMl = 300.0, wasserZiel = 2000.0))
        assertEquals(Widgetlage.Art.ERINNERUNG, hinterher.art)
        assertEquals("ks_h_wasser_n", hinterher.name)
        assertEquals("ks_wl_glaeser_hinterher(2)", hinterher.satz)
        assertEquals(Widgetlage.Art.TAG, art(blick(um(12), wasserMl = 301.0, wasserZiel = 2000.0)))
    }

    @Test
    fun wasser_mahnt_nur_von_neun_bis_einundzwanzig_uhr() {
        // Zehn Liter, damit die Kurve schon vor neun drei Glaeser verlangt - mit 2 l schwiege das Widget dort auch ohne Grenze.
        assertEquals(Widgetlage.Art.TAG, art(blick(um(8, 59), wasserMl = 0.0, wasserZiel = 10_000.0)))
        assertEquals(Widgetlage.Art.ERINNERUNG, art(blick(um(9), wasserMl = 0.0, wasserZiel = 10_000.0)))
        // Abends steht ohnehin der Abend an (ab 20 Uhr) - die Mahnung geht ihm bis 21:59 vor, ab 22 Uhr nicht mehr.
        assertEquals(Widgetlage.Art.ERINNERUNG, art(blick(um(21, 59), wasserMl = 0.0, wasserZiel = 2000.0)))
        assertEquals(Widgetlage.Art.ABEND, art(blick(um(22), wasserMl = 0.0, wasserZiel = 2000.0)))
    }

    @Test
    fun praeparate_vor_dem_wasser() {
        val l = lage(blick(um(12), wasserMl = 0.0, wasserZiel = 2000.0, praeparate = listOf(zink)))
        assertEquals("ks_w_praeparate", l.name)
        assertEquals("ks_wl_noch_offen(Zinc)", l.satz)
        assertEquals("ks_wl_offen", l.einheit)
        val drei = listOf(zink, SupCycle.Eintrag("Iron", false), SupCycle.Eintrag("Omega 3", false))
        assertEquals("ks_wl_noch_offen(Zinc, Iron …)", lage(blick(um(12), praeparate = drei)).satz)
    }

    @Test
    fun wasser_in_litern_wie_die_app() {
        // Die Akte liefert ml als Double: erst auf ganze ml runden, dann wie die App (Formate.liter) formen.
        assertEquals(Formate.liter(1200), Widgetlage.liter(1200.0))
        assertEquals(Formate.liter(1250), Widgetlage.liter(1249.6))
        assertEquals("0", Widgetlage.liter(0.0))
    }

    // --- Die Lagen selbst ---

    @Test
    fun morgen_vergleicht_die_nacht_mit_der_woche() {
        fun satz(woche: Double?) = lage(blick(um(8), um(7), 450.0, schlafWocheMin = woche)).satz
        assertEquals("ks_wl_laenger", satz(420.0))
        assertEquals("ks_wl_kuerzer", satz(480.0))
        assertEquals("ks_wl_wie_sonst", satz(460.0))
        assertEquals("ks_wl_nacht_eingetragen", satz(null))
        // Die Wasserkachel nur mit Drinktervall (wasserMl gesetzt).
        assertEquals(listOf("schritte", "aktiv"), lage(blick(um(8), um(7), 450.0)).kacheln.map { it.schluessel })
        assertEquals(listOf("schritte", "aktiv", "wasser"), lage(blick(um(8), um(7), 450.0, wasserMl = 0.0, wasserZiel = 2000.0)).kacheln.map { it.schluessel })
    }

    @Test
    fun training_nennt_wann_es_endete_und_fuehrt_zur_seite() {
        val l = lage(blick(um(10, 30), trainingEnde = um(10)))
        assertEquals("ks_wl_beendet_min(30)", l.satz)
        assertEquals(1_000L, l.trainingBeginn)
        assertEquals("ks_wl_beendet_h(1)", lage(blick(um(11, 30), trainingEnde = um(10))).satz)
    }

    @Test
    fun abend_zieht_bilanz() {
        val l = lage(blick(um(22), schritte = 10_000.0, wasserMl = 1500.0, wasserZiel = 2000.0))
        assertEquals(Widgetlage.Art.ABEND, l.art)
        assertEquals("ks_wl_schrittziel_erreicht · ks_wl_glaeser_fehlen(2)", l.satz)
        assertEquals("ks_wl_tag_eingetragen", lage(blick(um(22), schritte = null)).satz)
    }
}
