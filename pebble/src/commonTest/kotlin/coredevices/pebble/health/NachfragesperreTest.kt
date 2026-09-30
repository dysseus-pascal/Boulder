package coredevices.pebble.health

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class NachfragesperreTest {
    private val start = Instant.fromEpochSeconds(1_800_000_000)

    @Test
    fun ersteNachfrageDarf() {
        assertTrue(Nachfragesperre(2.minutes).darf(start))
    }

    @Test
    fun innerhalbDesAbstandsGesperrt() {
        val s = Nachfragesperre(2.minutes)
        assertTrue(s.darf(start))
        assertFalse(s.darf(start + 30.seconds))
        assertFalse(s.darf(start + 119.seconds))
        assertTrue(s.darf(start + 2.minutes))
    }

    @Test
    fun gesperrteVersucheVerschiebenDieSperreNicht() {
        val s = Nachfragesperre(2.minutes)
        assertTrue(s.darf(start))
        assertFalse(s.darf(start + 100.seconds))
        assertTrue(s.darf(start + 121.seconds))
    }

    @Test
    fun zurueckgestellteUhrSperrtNicht() {
        val s = Nachfragesperre(2.minutes)
        assertTrue(s.darf(start))
        assertTrue(s.darf(start - 1.minutes))
    }
}
