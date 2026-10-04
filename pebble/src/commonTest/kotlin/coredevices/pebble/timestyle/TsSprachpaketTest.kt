package coredevices.pebble.timestyle

import io.rebble.libpebblecommon.connection.FakeConnectedDevice
import io.rebble.libpebblecommon.connection.FirmwareUpdateCheckState
import io.rebble.libpebblecommon.connection.endpointmanager.FirmwareUpdater
import io.rebble.libpebblecommon.connection.fakeWatch
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TsSprachpaketTest {
    private class Speicher {
        val werte = mutableMapOf<String, String>()
        var geschrieben = 0
        val merker = TsSprachpaketMerker(
            lies = { werte[it] },
            schreibe = { serie, paket ->
                werte[serie] = paket
                geschrieben++
            },
        )
    }

    @Test
    fun hinweisNurWennBekanntFehlt() {
        // Russisch (17) braucht ein ru- oder bg-Paket, Deutsch (2) keines.
        assertTrue(tsSprachpaketHinweis(17, TsPaketWissen.Bekannt(null)))
        assertTrue(tsSprachpaketHinweis(17, TsPaketWissen.Bekannt("de_DE")))
        assertFalse(tsSprachpaketHinweis(17, TsPaketWissen.Bekannt("ru_RU")))
        assertFalse(tsSprachpaketHinweis(2, TsPaketWissen.Bekannt(null)))
        // Unbekannt heisst nicht "fehlt": kein Label (bis zur Nacharbeit stand es dann an allen acht).
        for (id in TS_SPRACHPAKET.keys) assertFalse(tsSprachpaketHinweis(id, TsPaketWissen.Unbekannt), "Sprache $id")
    }

    @Test
    fun getrenntZaehltDasGemerktePaket() {
        val s = Speicher()
        s.merker.merke("S1", "ru_RU")
        s.merker.merke("S2", null)
        // Getrennt: das zuletzt gemerkte - nicht "kein Paket".
        assertEquals(TsPaketWissen.Bekannt("ru_RU"), s.merker.wissen("S1", verbunden = false, paketJetzt = null))
        assertEquals(TsPaketWissen.Bekannt(null), s.merker.wissen("S2", verbunden = false, paketJetzt = null))
        // Nie gesehen oder ganz ohne Uhr: unbekannt.
        assertEquals(TsPaketWissen.Unbekannt, s.merker.wissen("S3", verbunden = false, paketJetzt = null))
        assertEquals(TsPaketWissen.Unbekannt, s.merker.wissen(null, verbunden = false, paketJetzt = null))
        // Verbunden zaehlt, was die Uhr JETZT meldet, nicht das Gemerkte.
        assertEquals(TsPaketWissen.Bekannt(null), s.merker.wissen("S1", verbunden = true, paketJetzt = null))
        assertEquals(TsPaketWissen.Bekannt("el_GR"), s.merker.wissen("S2", verbunden = true, paketJetzt = "el_GR"))
    }

    @Test
    fun beobachtetJedeVerbindungEinmal() = runTest {
        val s = Speicher()
        s.merker.beobachte(
            flowOf(null, "S1" to "ru_RU", "S1" to "ru_RU", null, "S2" to null, "S1" to "el_GR"),
        )
        assertEquals(mapOf("S1" to "el_GR", "S2" to ""), s.werte)
        // ("S1", ru_RU) zweimal hintereinander ist EIN Schreiben; "keine Uhr" schreibt nichts.
        assertEquals(3, s.geschrieben)
    }

    @Test
    fun paketDerVerbundenenUhr() {
        val getrennt = fakeWatch(connected = false)
        val verbunden = FakeConnectedDevice(
            identifier = getrennt.identifier,
            firmwareUpdateAvailable = FirmwareUpdateCheckState(false, null),
            firmwareUpdateState = FirmwareUpdater.FirmwareUpdateStatus.NotInProgress.Idle(),
            name = "Pebble",
            nickname = null,
            serial = "S9",
            connectionFailureInfo = null,
        )
        assertNull(tsVerbundenesPaket(listOf(getrennt)))
        assertNull(tsVerbundenesPaket(emptyList()))
        // Die Attrappe meldet kein Paket (installedLanguagePack = null).
        assertEquals("S9" to null, tsVerbundenesPaket(listOf(getrennt, verbunden)))
    }
}
