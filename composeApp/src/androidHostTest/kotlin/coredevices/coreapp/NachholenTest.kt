package coredevices.coreapp

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Was ohne Uhr liegen blieb, holt das Verbinden nur nach, wenn es faellig ist:
 * ein kurzer Abriss der Verbindung darf keinen zusaetzlichen Abruf ausloesen.
 */
class NachholenTest {
    private val jetzt = Instant.fromEpochSeconds(1_800_000_000)
    private fun vor(d: kotlin.time.Duration) = (jetzt - d).toEpochMilliseconds()

    @Test
    fun wetterNachKurzemAbrissNichtNochmal() {
        assertFalse(faellig(vor(40.minutes), jetzt, 1.hours))
    }

    @Test
    fun wetterNachVerpasstemStundenlaufSchon() {
        // Mit demselben Spiel wie der Stundenjob (0,9).
        assertTrue(faellig(vor(54.minutes), jetzt, 1.hours))
        assertFalse(faellig(vor(53.minutes), jetzt, 1.hours))
    }

    @Test
    fun sechsStundenTeilAbFuenfKommaVier() {
        assertFalse(faellig(vor(5.hours + 23.minutes), jetzt, 6.hours))
        assertTrue(faellig(vor(5.hours + 24.minutes), jetzt, 6.hours))
    }

    @Test
    fun nieGelaufenIstFaellig() {
        assertTrue(faellig(0L, jetzt, 6.hours))
    }

    @Test
    fun nurDerWechselAufVerbundenHoltNach() = runTest {
        val verbunden = MutableStateFlow(false)
        var laeufe = 0
        val beobachter = launch { nachholenBeimVerbinden(verbunden, beiFehler = {}) { laeufe++ } }
        runCurrent()
        assertEquals(0, laeufe)
        verbunden.value = true
        runCurrent()
        // Zweite Uhr dazu: bleibt "verbunden", kein zweites Nachholen.
        verbunden.value = true
        runCurrent()
        assertEquals(1, laeufe)
        beobachter.cancel()
    }

    @Test
    fun fehlerBeendetDenBeobachterNicht() = runTest {
        val verbunden = MutableStateFlow(false)
        val fehler = mutableListOf<Exception>()
        var laeufe = 0
        val beobachter = launch {
            nachholenBeimVerbinden(verbunden, beiFehler = { fehler += it }) {
                laeufe++
                if (laeufe == 1) error("Geocoder kaputt")
            }
        }
        verbinde(verbunden)
        verbinde(verbunden)
        assertEquals(2, laeufe)
        assertEquals(1, fehler.size)
        assertTrue(beobachter.isActive)
        beobachter.cancel()
    }

    @Test
    fun haengendesNachholenBlockiertDasNaechsteNicht() = runTest {
        val verbunden = MutableStateFlow(false)
        val nie = CompletableDeferred<Unit>()
        var laeufe = 0
        val beobachter = launch {
            nachholenBeimVerbinden(verbunden, beiFehler = {}) {
                laeufe++
                if (laeufe == 1) nie.await()
            }
        }
        verbinde(verbunden)
        verbinde(verbunden)
        assertEquals(2, laeufe)
        beobachter.cancel()
    }

    private fun TestScope.verbinde(verbunden: MutableStateFlow<Boolean>) {
        verbunden.value = false
        runCurrent()
        verbunden.value = true
        runCurrent()
    }
}
