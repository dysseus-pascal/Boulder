package coredevices.pebble.health

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Ein entzogenes Health-Connect-Recht soll den Schalter wie bei Gravel beim
 * Zurueckkehren in die App ausschalten - auch ohne neue Uhrdaten.
 */
class RechteanlassTest {

    @Test
    fun ersterLaufNachDemStartFragtOhneNeues() = runTest {
        val anlass = Rechteanlass()
        var datenbank = 0
        assertTrue(anlass.fragen { datenbank++; false })
        // Der Anlass genuegt, die Datenbank wird gar nicht erst gefragt.
        assertEquals(0, datenbank)
    }

    @Test
    fun stundenlaufOhneNeuesFragtNicht() = runTest {
        val anlass = Rechteanlass()
        anlass.fragen { false }
        assertFalse(anlass.fragen { false })
        assertFalse(anlass.fragen { false })
    }

    @Test
    fun mitNeuemFragtImmer() = runTest {
        val anlass = Rechteanlass()
        anlass.fragen { false }
        assertTrue(anlass.fragen { true })
        assertTrue(anlass.fragen { true })
    }

    @Test
    fun rueckkehrFragtGenauEinmal() = runTest {
        val anlass = Rechteanlass()
        anlass.fragen { false }
        anlass.merke()
        assertTrue(anlass.fragen { false })
        assertFalse(anlass.fragen { false })
    }

    @Test
    fun rueckkehrWaehrendEinesSyncsFragtImNachlauf() = runTest {
        // Wie in PlatformHealthSync: der Anstoss aus der Rueckkehr kehrt sofort zurueck,
        // seine Arbeit macht der Nachlauf des laufenden Syncs - der Anlass muss dort ankommen.
        val anlass = Rechteanlass()
        val lauf = Einzellauf()
        anlass.fragen { false }
        val gefragt = mutableListOf<Boolean>()
        val tor = CompletableDeferred<Unit>()
        val sync = suspend {
            lauf.lauf {
                gefragt += anlass.fragen { false }
                if (gefragt.size == 1) tor.await()
            }
        }
        val erster = launch { sync() }
        runCurrent()
        anlass.merke()
        assertEquals(Einzellauf.Ausgang.UEBERGEBEN, sync())
        tor.complete(Unit)
        erster.join()
        assertEquals(listOf(false, true), gefragt)
    }
}
