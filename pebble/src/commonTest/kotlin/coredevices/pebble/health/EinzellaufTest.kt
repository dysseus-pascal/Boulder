package coredevices.pebble.health

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class EinzellaufTest {

    @Test
    fun einzelnerAufrufArbeitetEinmal() = runTest {
        var laeufe = 0
        assertEquals(Einzellauf.Ausgang.GEMACHT, Einzellauf().lauf { laeufe++ })
        assertEquals(1, laeufe)
    }

    @Test
    fun anstoesseWaehrendDesLaufsErgebenGenauEinenWeiteren() = runTest {
        val lauf = Einzellauf()
        var laeufe = 0
        var gleichzeitig = 0
        var hoechstGleichzeitig = 0
        val tor = CompletableDeferred<Unit>()
        val erster = launch {
            lauf.lauf {
                laeufe++
                gleichzeitig++
                hoechstGleichzeitig = maxOf(hoechstGleichzeitig, gleichzeitig)
                if (laeufe == 1) tor.await()
                gleichzeitig--
            }
        }
        runCurrent()
        // Drei Anstoesse, waehrend der erste Durchgang haengt.
        repeat(3) { assertEquals(Einzellauf.Ausgang.UEBERGEBEN, lauf.lauf { laeufe++ }) }
        tor.complete(Unit)
        erster.join()
        assertEquals(2, laeufe)
        assertEquals(1, hoechstGleichzeitig)
    }

    @Test
    fun fehlerGibtDieSperreFrei() = runTest {
        val fehler = mutableListOf<Exception>()
        val lauf = Einzellauf(beiFehler = { fehler += it })
        lauf.lauf { error("kaputt") }
        assertEquals(1, fehler.size)
        var laeufe = 0
        assertEquals(Einzellauf.Ausgang.GEMACHT, lauf.lauf { laeufe++ })
        assertEquals(1, laeufe)
    }

    @Test
    fun anstossWaehrendEinesFehlschlagsWirdNachgeholt() = runTest {
        val lauf = Einzellauf()
        var laeufe = 0
        val tor = CompletableDeferred<Unit>()
        val erster = launch {
            lauf.lauf {
                laeufe++
                if (laeufe == 1) {
                    tor.await()
                    error("kaputt")
                }
            }
        }
        runCurrent()
        assertEquals(Einzellauf.Ausgang.UEBERGEBEN, lauf.lauf { laeufe++ })
        tor.complete(Unit)
        erster.join()
        assertEquals(2, laeufe)
    }

    @Test
    fun begrenzteNachlaeufeMeldenDenOffenenAnstoss() = runTest {
        val lauf = Einzellauf(hoechstens = 2)
        var laeufe = 0
        val tore = List(2) { CompletableDeferred<Unit>() }
        var ausgang: Einzellauf.Ausgang? = null
        val erster = launch {
            ausgang = lauf.lauf {
                laeufe++
                tore.getOrNull(laeufe - 1)?.await()
            }
        }
        runCurrent()
        // Anstoss im ersten, dann im zweiten Durchgang: der zweite ist die Grenze.
        lauf.lauf { laeufe++ }
        tore[0].complete(Unit)
        runCurrent()
        lauf.lauf { laeufe++ }
        tore[1].complete(Unit)
        erster.join()
        assertEquals(2, laeufe)
        assertEquals(Einzellauf.Ausgang.OFFEN, ausgang)
        // Der naechste Aufruf arbeitet den offenen Anstoss ab.
        assertEquals(Einzellauf.Ausgang.GEMACHT, lauf.lauf { laeufe++ })
        assertEquals(3, laeufe)
    }
}
