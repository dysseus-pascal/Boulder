package coredevices.pebble.kiesel

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
