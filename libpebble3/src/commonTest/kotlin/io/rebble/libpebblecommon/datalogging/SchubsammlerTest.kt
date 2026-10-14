package io.rebble.libpebblecommon.datalogging

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class SchubsammlerTest {

    @Test
    fun einSchubGibtEineMeldungNachDerRuhe() = runTest {
        var meldungen = 0
        val sammler = Schubsammler(backgroundScope, ruhe = 5.seconds, hoechstens = 60.seconds) { meldungen++ }
        runCurrent()
        repeat(30) {
            sammler.stossAn()
            advanceTimeBy(200.milliseconds)
        }
        assertEquals(0, meldungen)
        advanceTimeBy(5.seconds + 1.milliseconds)
        assertEquals(1, meldungen)
        advanceTimeBy(60.seconds)
        assertEquals(1, meldungen)
    }

    @Test
    fun zweiSchuebeMitPauseGebenZweiMeldungen() = runTest {
        var meldungen = 0
        val sammler = Schubsammler(backgroundScope, ruhe = 5.seconds, hoechstens = 60.seconds) { meldungen++ }
        runCurrent()
        sammler.stossAn()
        advanceTimeBy(10.seconds)
        sammler.stossAn()
        advanceTimeBy(10.seconds)
        assertEquals(2, meldungen)
    }

    @Test
    fun langerRueckstandMeldetSpaetestensNachDerHoechstzeit() = runTest {
        var meldungen = 0
        val sammler = Schubsammler(backgroundScope, ruhe = 5.seconds, hoechstens = 60.seconds) { meldungen++ }
        runCurrent()
        // 100 s lang jede Sekunde ein Paket: nie 5 s Ruhe.
        repeat(100) {
            sammler.stossAn()
            advanceTimeBy(1.seconds)
        }
        assertEquals(1, meldungen)
        advanceTimeBy(6.seconds)
        // Der Rest des Schubs geht nicht verloren.
        assertEquals(2, meldungen)
    }

    @Test
    fun anstossWaehrendDerMeldungFuehrtZuEinerWeiteren() = runTest {
        var meldungen = 0
        lateinit var sammler: Schubsammler
        sammler = Schubsammler(backgroundScope, ruhe = 5.seconds, hoechstens = 60.seconds) {
            meldungen++
            if (meldungen == 1) sammler.stossAn()
        }
        runCurrent()
        sammler.stossAn()
        advanceTimeBy(20.seconds)
        assertEquals(2, meldungen)
    }

    @Test
    fun ohneAnstossKeineMeldung() = runTest {
        var meldungen = 0
        Schubsammler(backgroundScope) { meldungen++ }
        advanceTimeBy(10.seconds)
        assertEquals(0, meldungen)
    }
}
