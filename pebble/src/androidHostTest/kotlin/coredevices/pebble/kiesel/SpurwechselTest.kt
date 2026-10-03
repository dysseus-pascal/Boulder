package coredevices.pebble.kiesel

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Die Regeln des SpurDienst seit dem gebuendelten GPS: Wechsel auf ein neues
 * Training und das Warten der Zusammenfassung auf den Puffer.
 */
class SpurwechselTest {

    @Test
    fun `beim Wechsel gehen die Punkte noch ins alte Training`() {
        val alt = 1_700_000_000L
        val neu = 1_700_003_600L
        assertEquals(alt, Spurwechsel.ziel(neu, alt))
        // Ist der Puffer geholt (frueher = 0), alles ins neue.
        assertEquals(neu, Spurwechsel.ziel(neu, 0L))
    }

    @Test
    fun `die Zusammenfassung wartet, bis der Puffer in der Datei steht`() = runTest {
        val aufnahme = MutableStateFlow(42L)
        launch {
            delay(1_200)
            aufnahme.value = 0L
        }
        assertTrue(Spurwechsel.warteBisFrei(aufnahme, 42L, 5_000))
        assertEquals(1_200, currentTime)
    }

    @Test
    fun `nach dem Wechsel auf ein neues Training ist das alte frei`() = runTest {
        // Der Dienst stellt die Aufnahme erst nach dem Puffer des alten um.
        val aufnahme = MutableStateFlow(42L)
        launch {
            delay(300)
            aufnahme.value = 99L
        }
        assertTrue(Spurwechsel.warteBisFrei(aufnahme, 42L, 5_000))
        assertEquals(300, currentTime)
    }

    @Test
    fun `ein anderes Training haelt nicht auf`() = runTest {
        assertTrue(Spurwechsel.warteBisFrei(MutableStateFlow(7L), 42L, 5_000))
        assertTrue(Spurwechsel.warteBisFrei(MutableStateFlow(0L), 42L, 5_000))
        assertEquals(0, currentTime)
    }

    @Test
    fun `haengt der Dienst, wartet sie hoechstens die Frist`() = runTest {
        assertFalse(Spurwechsel.warteBisFrei(MutableStateFlow(42L), 42L, 5_000))
        assertEquals(5_000, currentTime)
    }
}
