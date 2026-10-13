package coredevices.pebble.ui

import io.rebble.libpebblecommon.locker.SystemApps
import io.rebble.libpebblecommon.locker.wrap
import io.rebble.libpebblecommon.metadata.WatchType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Boulder: die Kachel TimeStyle Bedrock unter Apps > Faces - wann sie
 * kompatibel ist, wann sie einen Settings-Knopf hat und dass sie bei zu alter
 * oder unbekannter Firmware MIT HINWEIS stehen bleibt, statt still zu fehlen.
 * Der Weg ist der echte: SystemApps.wrap -> asCommonApp -> imLockerZeigen/hasSettings.
 */
class TsBedrockKachelTest {
    private fun kachel(build: Int?, uhr: WatchType = WatchType.EMERY) =
        SystemApps.TimeStyleBedrock.wrap(0).asCommonApp(uhr, null, null, build)

    private fun system(app: CommonApp) = app.commonAppType as CommonAppType.System

    @Test
    fun jeFirmwareStand() {
        // (kompatibel, Settings-Knopf, "Firmware fehlt") je Build der Uhr.
        fun drei(build: Int?) = kachel(build).let { Triple(it.isCompatible, it.hasSettings(), system(it).firmwareFehlt) }
        // Unbekannt (offizielle Firmware, Entwicklungsbau ohne -bN, nie verbunden): gesperrt, mit Hinweis.
        assertEquals(Triple(false, false, true), drei(null))
        assertEquals(Triple(false, false, true), drei(21))
        // b22 hat das Zifferblatt, nimmt aber keine Einstellungen an.
        assertEquals(Triple(true, false, false), drei(22))
        assertEquals(Triple(true, true, false), drei(23))
        assertEquals(Triple(true, true, false), drei(24))
    }

    @Test
    fun inkompatibleFirmwareBleibtMitHinweisStehen() {
        // "Show incompatible" aus (die Vorgabe): Bedrock vor b22 bleibt trotzdem in der Liste.
        assertTrue(kachel(21).imLockerZeigen(showIncompatible = false, showScaled = true))
        assertTrue(kachel(null).imLockerZeigen(showIncompatible = false, showScaled = true))
        assertTrue(kachel(22).imLockerZeigen(showIncompatible = false, showScaled = true))
    }

    @Test
    fun andereUhrBleibtWeg() {
        // Auf einer Uhr ohne emery-Plattform gibt es Bedrock gar nicht: kein Hinweis, weg wie bisher.
        val basalt = kachel(24, WatchType.BASALT)
        assertFalse(basalt.isCompatible)
        assertFalse(system(basalt).firmwareFehlt)
        assertFalse(basalt.imLockerZeigen(showIncompatible = false, showScaled = true))
        assertTrue(basalt.imLockerZeigen(showIncompatible = true, showScaled = true))
    }

    @Test
    fun boulderV2OhneKachel() {
        // V2 hat Bedrock nicht mehr: weg wie auf einer anderen Uhr, ohne Hinweis auf eine Firmware.
        val v2 = SystemApps.TimeStyleBedrock.wrap(0).asCommonApp(WatchType.EMERY, null, null, null, ohneBedrock = true)
        assertFalse(v2.isCompatible)
        assertFalse(v2.hasSettings())
        assertFalse(system(v2).firmwareFehlt)
        assertNull(v2.firmwareHinweis())
        assertFalse(v2.imLockerZeigen(showIncompatible = false, showScaled = true))
        assertEquals("Not Compatible with Pebble Time 2", v2.aufDerUhrText("Pebble Time 2", laeuft = false))
    }

    @Test
    fun aktivesZifferblattStehtAuchImKarussell() {
        // Der Fall vom Telefon des Nutzers: "nach Nutzung sortiert", Bedrock aktiv und
        // darum VORN - Gravel liess genau diesen Eintrag weg.
        val bedrock = kachel(24)
        val tictoc = SystemApps.Tictoc.wrap(1).asCommonApp(WatchType.EMERY, null, null, 24)
        val kickstart = SystemApps.Kickstart.wrap(2).asCommonApp(WatchType.EMERY, null, null, 24)
        val einstellungenApp = SystemApps.Settings.wrap(3).asCommonApp(WatchType.EMERY, null, null, 24)
        val liste = listOf(bedrock, tictoc, kickstart)
        assertEquals(liste, karussellEintraege(liste))
        // Gilt fuer jedes Zifferblatt: auch Kickstart vorn bleibt stehen.
        val andersrum = listOf(kickstart, tictoc, bedrock)
        assertEquals(andersrum, karussellEintraege(andersrum))
        // System-Apps (keine Zifferblaetter) bleiben wie bei Gravel draussen.
        assertEquals(liste, karussellEintraege(liste + einstellungenApp))
    }

    @Test
    fun firmwareHinweisStattNichtKompatibel() {
        // Zu alte oder unbekannte Firmware: der Hinweis, auf der App-Seite UND beim Sperrsymbol.
        assertEquals("Needs Boulder firmware b22 or newer", kachel(21).firmwareHinweis())
        assertEquals("Needs Boulder firmware b22 or newer", kachel(null).firmwareHinweis())
        assertEquals("Needs Boulder firmware b22 or newer", kachel(21).aufDerUhrText("Pebble Time 2", laeuft = false))
        // Ab b22 kein Hinweis; eine andere Uhr ist wirklich inkompatibel.
        assertNull(kachel(22).firmwareHinweis())
        val basalt = kachel(24, WatchType.BASALT)
        assertNull(basalt.firmwareHinweis())
        assertEquals("Not Compatible with Pebble Time", basalt.aufDerUhrText("Pebble Time", laeuft = false))
        // Kompatibel und auf der Uhr (Systemzifferblatt): wie bei Gravel.
        assertEquals("On Watch Pebble Time 2", kachel(24).aufDerUhrText("Pebble Time 2", laeuft = false))
        assertEquals("Running On Watch Pebble Time 2", kachel(24).aufDerUhrText("Pebble Time 2", laeuft = true))
    }

    @Test
    fun andereSystemZifferblaetterUnveraendert() {
        val kickstart = SystemApps.Kickstart.wrap(0).asCommonApp(WatchType.EMERY, null, null, null)
        assertTrue(kickstart.isCompatible)
        assertFalse(kickstart.hasSettings())
        assertFalse(system(kickstart).firmwareFehlt)
        assertTrue(kickstart.imLockerZeigen(showIncompatible = false, showScaled = false))
    }
}
