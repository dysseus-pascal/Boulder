package coredevices.analytics

import coredevices.database.HeartbeatStateEntity
import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Nur Wechsel zu schreiben darf die Kennzahl nicht aendern: die Zeit im
 * Zustand true ist mit und ohne die wiederholten Eintraege dieselbe.
 */
class ZustandsfilterTest {

    @Test
    fun gleicherWertWirdNichtNochmalGeschrieben() = runBlocking {
        val filter = Zustandsfilter()
        assertTrue(filter.wechsel("a", true))
        assertEquals(false, filter.wechsel("a", true))
        assertTrue(filter.wechsel("b", true))
        assertTrue(filter.wechsel("a", false))
        assertTrue(filter.wechsel("a", true))
    }

    @Test
    fun zeitImZustandBleibtGleich() = runBlocking {
        val zufall = Random(17)
        repeat(300) {
            val filter = Zustandsfilter()
            var t = 1_000L
            val alle = mutableListOf<HeartbeatStateEntity>()
            val geschrieben = mutableListOf<HeartbeatStateEntity>()
            repeat(zufall.nextInt(1, 60)) {
                t += zufall.nextLong(1, 600_000)
                val e = HeartbeatStateEntity(name = "n", state = zufall.nextInt(4) != 0, timestamp = t)
                alle += e
                if (filter.wechsel(e.name, e.state)) geschrieben += e
            }
            // processHeartbeat haengt den letzten Wert mit "jetzt" an (getValuesAndClear).
            val jetzt = t + zufall.nextLong(0, 600_000)
            val ende = alle.last().copy(timestamp = jetzt)
            assertEquals(
                processHeartbeatState(alle + ende, "n"),
                processHeartbeatState(geschrieben + geschrieben.last().copy(timestamp = jetzt), "n"),
            )
        }
    }
}
