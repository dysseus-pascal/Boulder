package coredevices.pebble.health

import io.rebble.libpebblecommon.connection.PebbleDevice
import io.rebble.libpebblecommon.connection.fakeWatch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class UhrNachfrageTest {
    private var jetzt = Instant.fromEpochSeconds(1_800_000_000)
    private val uhr = object : Clock {
        override fun now(): Instant = jetzt
    }
    private val uhren = MutableStateFlow<List<PebbleDevice>>(emptyList())
    private var fragen = 0

    private fun nachfrage() = UhrNachfrage({ fragen++ }, uhr)

    @Test
    fun ohneUhrKeineFrageUndSpaeterVerbundenWirdNachgeholt() = runTest {
        val n = nachfrage()
        backgroundScope.launch { n.beobachte(uhren) }
        n.beimOeffnen()
        runCurrent()
        assertEquals(0, fragen)
        // Die Uhr verbindet Sekunden nach dem Oeffnen.
        jetzt += 10.seconds
        uhren.value = listOf(fakeWatch(connected = true))
        runCurrent()
        assertEquals(1, fragen)
    }

    @Test
    fun zuSpaetVerbundenFragtNicht() = runTest {
        val n = nachfrage()
        backgroundScope.launch { n.beobachte(uhren) }
        n.beimOeffnen()
        runCurrent()
        jetzt += UhrNachfrage.WARTEZEIT + 1.seconds
        uhren.value = listOf(fakeWatch(connected = true))
        runCurrent()
        assertEquals(0, fragen)
    }

    @Test
    fun mitUhrGenauEineFrageUndSperre() = runTest {
        uhren.value = listOf(fakeWatch(connected = true))
        val n = nachfrage()
        backgroundScope.launch { n.beobachte(uhren) }
        n.beimOeffnen()
        runCurrent()
        assertEquals(1, fragen)
        jetzt += 30.seconds
        n.beimOeffnen()
        runCurrent()
        assertEquals(1, fragen)
        jetzt += 2.minutes
        n.beimOeffnen()
        runCurrent()
        assertEquals(2, fragen)
    }

    @Test
    fun ausnahmeWirdGeschluckt() = runTest {
        uhren.value = listOf(fakeWatch(connected = true))
        val n = UhrNachfrage({ fragen++; error("weg") }, uhr)
        val beobachter = backgroundScope.launch { n.beobachte(uhren) }
        n.beimOeffnen()
        runCurrent()
        assertEquals(1, fragen)
        assertEquals(true, beobachter.isActive)
    }
}
