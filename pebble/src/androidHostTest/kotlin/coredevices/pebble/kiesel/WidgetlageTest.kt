package coredevices.pebble.kiesel

import coredevices.pebble.R
import coredevices.pebble.health.Einzellauf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

    @Test
    fun widget_und_seite_nennen_dieselben_versaeumten_praeparate() {
        // Eine Regel fuer beide (Gesundheitsrechnung.praeparatVersaeumt), den ganzen Tag hindurch.
        val liste = listOf(
            zink,
            SupCycle.Eintrag("D3", true),
            SupCycle.Eintrag("Black Maca", false, LocalTime.of(12, 30)),
            SupCycle.Eintrag("Ashwagandha", false, LocalTime.of(20, 0)),
        )
        val g = Gesundheitsrechnung
        for (minute in 0 until 24 * 60 step 7) {
            val jetzt = LocalTime.of(minute / 60, minute % 60)
            val z = g.praeparatzeile(liste, jetzt)
            val seite = if (z.lage == Praeparatlage.OFFEN) z.namen + List(z.weitere) { "+" } else emptyList()
            val widget = Widgetlage.versaeumtePraeparate(blick(tag.atTime(jetzt), praeparate = liste))
            assertEquals(seite.size, widget.size, "um $jetzt")
            assertEquals(seite.filter { it != "+" }, widget.take(2), "um $jetzt")
        }
        // Knapp vor und nach elf: Zink ohne Planzeit, obwohl D3 schon genommen ist.
        assertEquals(emptyList(), Widgetlage.versaeumtePraeparate(blick(tag.atTime(10, 59), praeparate = liste)))
        assertEquals(Praeparatlage.FAELLIG, g.praeparatzeile(liste, LocalTime.of(10, 59)).lage)
        assertEquals(listOf("Zinc"), Widgetlage.versaeumtePraeparate(blick(tag.atTime(11, 0), praeparate = liste)))
        assertEquals(Praeparatlage.OFFEN, g.praeparatzeile(liste, LocalTime.of(11, 0)).lage)
    }

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

    // --- Der Takt ohne Wecken: wann das Widget ohne neue Daten wieder zeichnet ---

    /** Die naechste Wende ohne die halbe Stunde - so zeigt sich, welche Uhrzeit sie setzt. */
    private fun wende(b: Widgetlage.Blick, wunschBis: LocalDateTime? = null) = Widgetlage.naechsteWende(b, wunschBis, java.time.Duration.ofDays(2))

    @Test
    fun spaetestens_nach_einer_halben_stunde() {
        // Ohne Uhr kommt kein Schub: dann zeichnet der Takt so oft wie frueher Androids updatePeriodMillis.
        assertEquals(um(14, 40), Widgetlage.naechsteWende(blick(um(14, 10)), null))
        assertEquals(java.time.Duration.ofMinutes(30), Widgetlage.TAKT)
    }

    @Test
    fun wende_an_den_festen_uhrzeiten_der_lage() {
        assertEquals(um(9), wende(blick(um(7))))          // Beginn der Wassermahnung
        assertEquals(um(11), wende(blick(um(9, 30))))     // Praeparate ohne Planzeit
        assertEquals(um(16), wende(blick(um(12))))        // Schrittziel zaehlt als Bilanz
        assertEquals(um(20), wende(blick(um(19, 59))))    // Abend
        assertEquals(um(22), wende(blick(um(21))))        // Ende der Wassermahnung
        assertEquals(tag.plusDays(1).atStartOfDay(), wende(blick(um(23, 50)))) // Mitternacht: neuer Tag, neue Schritte
    }

    @Test
    fun wende_am_ende_von_training_morgen_und_wunsch() {
        assertEquals(um(12), wende(blick(um(11, 30), trainingEnde = um(10))))
        assertEquals(um(10, 15), wende(blick(um(9, 30), um(7, 15), 450.0)))
        // Ein Ende in der Zukunft gilt ab dann.
        assertEquals(um(13, 30), wende(blick(um(13, 20), trainingEnde = um(13, 30))))
        // Der Kachelwunsch endet nach einer Stunde.
        assertEquals(um(15, 5), wende(blick(um(14, 5), wunsch = Widgetlage.Art.MORGEN), um(15, 5)))
    }

    @Test
    fun wende_an_der_planzeit_eines_praeparats() {
        val abends = SupCycle.Eintrag("D3", false, LocalTime.of(18, 30))
        assertEquals(um(18, 30), wende(blick(um(16, 1), praeparate = listOf(abends))))
        // Ist die Zeit vorbei, ist sie keine Wende mehr.
        assertEquals(um(20), wende(blick(um(18, 30), praeparate = listOf(abends))))
    }

    @Test
    fun zwischen_zwei_wenden_bleibt_die_lage() {
        // Ueber einen ganzen Tag: wechselt die Lage von selbst, liegt eine Wende davor - sonst verpasste der Takt sie.
        val zink = SupCycle.Eintrag("Zinc", false, LocalTime.of(12, 10))
        fun b(j: LocalDateTime) = blick(
            j, um(6, 40), 430.0, trainingEnde = um(17, 20), schritte = 10_500.0, wasserMl = 1500.0, wasserZiel = 2000.0, praeparate = listOf(zink),
        )
        var j = um(0, 5)
        var wenden = 0
        while (j.isBefore(tag.plusDays(1).atStartOfDay())) {
            val naechste = wende(b(j))
            var m = j.plusMinutes(1)
            while (m.isBefore(naechste)) {
                assertEquals(art(b(j)), art(b(m)), "zwischen $j und $naechste, um $m")
                m = m.plusMinutes(1)
            }
            j = naechste
            wenden++
        }
        // Wenige Wenden am Tag statt 48 Weckungen.
        assertTrue(wenden in 5..15, "$wenden Wenden")
    }

    // --- Das Netz vor dem Zeichnen: der Takt steht, auch wenn der Durchgang stirbt ---

    private val jetzt: Instant = Instant.parse("2026-10-02T12:10:00Z")
    private val netz: Instant = jetzt.plus(Widgetlage.TAKT)

    @Test
    fun erst_das_netz_dann_zeichnen_dann_die_wende() = runBlocking {
        val folge = mutableListOf<String>()
        val wende = jetzt.plusSeconds(20 * 60)
        val ausgang = Widgetlage.mitTakt(jetzt, { folge += "plane $it" }) {
            folge += "zeichne"
            Einzellauf.Ausgang.GEMACHT to wende
        }
        assertEquals(Einzellauf.Ausgang.GEMACHT, ausgang)
        // Setzt Android den Alarm mit derselben Absicht neu, gilt der letzte: die Wende.
        assertEquals(listOf("plane $netz", "zeichne", "plane $wende"), folge)
    }

    @Test
    fun stirbt_der_durchgang_bleibt_das_netz() = runBlocking {
        // Der Prozess stirbt beim Lesen (LMK, Frist des Broadcasts): hier bricht der Durchgang ab.
        val geplant = mutableListOf<Instant>()
        val abbruch = runCatching {
            Widgetlage.mitTakt(jetzt, { geplant += it }) { throw CancellationException("Prozess weg") }
        }
        assertTrue(abbruch.isFailure)
        assertEquals(listOf(netz), geplant)
        // Ein Fehler im Zeichnen (keine Wende): ebenso nur das Netz, nicht nichts.
        geplant.clear()
        Widgetlage.mitTakt(jetzt, { geplant += it }) { Einzellauf.Ausgang.GEMACHT to null }
        assertEquals(listOf(netz), geplant)
    }

    @Test
    fun uebergeben_die_wende_des_laufenden_kommt_nach_dem_netz() = runBlocking {
        // Der Alarm, wie Android ihn haelt: was zuletzt geplant wurde, gilt.
        var alarm: Instant? = null
        val lauf = Einzellauf(hoechstens = 2)
        val tor = CompletableDeferred<Unit>()
        val wende = jetzt.plusSeconds(600)
        var gezeichnet = 0
        suspend fun durchgang() = Widgetlage.mitTakt(jetzt, { alarm = it }) {
            var w: Instant? = null
            val a = lauf.lauf {
                gezeichnet++
                if (gezeichnet == 1) tor.await()
                w = wende
            }
            a to w
        }
        val erster = async { durchgang() }
        while (gezeichnet == 0) yield()
        // Ein Schub waehrend des Lesens: sein Netz ersetzt den Alarm, zeichnen tut der laufende ...
        assertEquals(Einzellauf.Ausgang.UEBERGEBEN, durchgang())
        assertEquals(netz, alarm)
        tor.complete(Unit)
        assertEquals(Einzellauf.Ausgang.GEMACHT, erster.await())
        // ... der einmal nachzeichnet und danach die Wende setzt - das Netz des Schubs bleibt nicht stehen.
        assertEquals(2, gezeichnet)
        assertEquals(wende, alarm)
    }
}
