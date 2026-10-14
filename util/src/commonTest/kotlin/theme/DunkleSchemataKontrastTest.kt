package theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Boulder: Titel, Buttons und Fehlertexte muessen auf jedem Schema AA (4,5:1)
 * erreichen. Das Markengruen kam auf Grau nur auf 2,65:1 - und die Fehlerfarbe
 * der Vorlage mit Weiss auf 2,85:1.
 */
class DunkleSchemataKontrastTest {
    private fun kontrast(a: Color, b: Color): Float {
        val hell = maxOf(a.luminance(), b.luminance())
        val dunkel = minOf(a.luminance(), b.luminance())
        return (hell + 0.05f) / (dunkel + 0.05f)
    }

    private fun mindestens(wert: Float, a: Color, b: Color, was: String) =
        assertTrue(kontrast(a, b) >= wert, "$was: ${kontrast(a, b)}:1")

    @Test
    fun `Primaerfarbe auf dem Grund der dunklen Schemata`() {
        for (s in listOf(greyScheme, blackScheme)) {
            mindestens(4.5f, s.primary, s.background, "primary auf background")
            // Auf der hellsten Karte reicht die Schwelle fuer Bedienelemente (3:1):
            // ein Gruen, das dort 4,5:1 schaffte, waere auf dem Grund zu blass.
            mindestens(3.0f, s.primary, s.surfaceContainerHighest, "primary auf surfaceContainerHighest")
            mindestens(4.5f, s.onPrimary, s.primary, "onPrimary auf primary")
            mindestens(4.5f, s.onPrimaryContainer, s.primaryContainer, "onPrimaryContainer auf primaryContainer")
            mindestens(4.5f, s.error, s.background, "error auf background")
            mindestens(4.5f, s.onError, s.error, "onError auf error")
            mindestens(4.5f, s.onErrorContainer, s.errorContainer, "onErrorContainer auf errorContainer")
            mindestens(4.5f, s.onTertiaryContainer, s.tertiaryContainer, "onTertiaryContainer auf tertiaryContainer")
        }
    }

    @Test
    fun `Primaer- und Fehlerfarbe auf Weiss`() {
        mindestens(4.5f, lightScheme.primary, lightScheme.background, "primary auf weiss")
        mindestens(4.5f, lightScheme.onPrimary, lightScheme.primary, "onPrimary auf primary")
        mindestens(4.5f, lightScheme.error, lightScheme.background, "error auf weiss")
        mindestens(4.5f, lightScheme.onError, lightScheme.error, "onError auf error")
        mindestens(4.5f, lightScheme.onErrorContainer, lightScheme.errorContainer, "onErrorContainer auf errorContainer")
    }

    @Test
    fun `Menues haben im hellen Schema eine Kante`() {
        assertNotEquals(lightScheme.surface, lightScheme.surfaceContainer)
    }
}
