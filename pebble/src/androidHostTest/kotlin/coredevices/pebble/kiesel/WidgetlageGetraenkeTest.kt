package coredevices.pebble.kiesel

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Wasser und Praeparate im Widget. Nur was ohne Ressourcen auskommt: die
 * Hosttests haben die R-Klasse nicht auf dem Klassenpfad.
 */
class WidgetlageGetraenkeTest {
    private fun blick(um: LocalTime, praeparate: List<SupCycle.Eintrag>) =
        Widgetlage.Blick(
            jetzt = LocalDateTime.of(LocalDate.of(2026, 10, 2), um),
            schlafEnde = null, schlafMin = null, schlafZielMin = 480.0, schlafWocheMin = null, erholsamAnteil = null,
            ruhepuls = null, hrv = null,
            trainingEnde = null, trainingName = null, trainingMin = null, trainingPuls = null, trainingKm = null, trainingBeginn = null,
            schritte = 4000.0, schritteZiel = 10_000.0, aktivMin = 10.0, aktivZiel = 30.0,
            wasserMl = null, wasserZiel = null, glasMl = 300.0,
            praeparate = praeparate, koffeinMg = null, wunsch = null,
        )

    private val plan = listOf(
        SupCycle.Eintrag("Multivitamin", true, LocalTime.of(8, 0)),
        SupCycle.Eintrag("Black Maca", false, LocalTime.of(12, 30)),
        SupCycle.Eintrag("Ashwagandha", false, LocalTime.of(20, 0)),
    )

    @Test
    fun mahnt_erst_ab_der_planzeit() {
        assertEquals(emptyList(), Widgetlage.versaeumtePraeparate(blick(LocalTime.of(11, 0), plan)))
        assertEquals(listOf("Black Maca"), Widgetlage.versaeumtePraeparate(blick(LocalTime.of(12, 30), plan)))
        assertEquals(listOf("Black Maca", "Ashwagandha"), Widgetlage.versaeumtePraeparate(blick(LocalTime.of(20, 0), plan)))
    }

    @Test
    fun ohne_planzeit_ab_elf_uhr() {
        val ohnePlan = listOf(SupCycle.Eintrag("Zink", false))
        assertEquals(emptyList(), Widgetlage.versaeumtePraeparate(blick(LocalTime.of(10, 59), ohnePlan)))
        assertEquals(listOf("Zink"), Widgetlage.versaeumtePraeparate(blick(LocalTime.of(11, 0), ohnePlan)))
    }

    @Test
    fun wasser_in_litern_wie_die_app() {
        // Die Akte liefert ml als Double: erst auf ganze ml runden, dann wie die App (Formate.liter) formen.
        assertEquals(Formate.liter(1200), Widgetlage.liter(1200.0))
        assertEquals(Formate.liter(1250), Widgetlage.liter(1249.6))
        assertEquals("0", Widgetlage.liter(0.0))
    }
}
