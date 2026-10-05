package coredevices.pebble.firmware

import io.rebble.libpebblecommon.connection.FirmwareUpdateCheckResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Boulder: was die Uhrenseite unter der Uhr zeigt, wenn es kein Angebot gibt.
 * Eine Firmware, die nur auf ein App-Update wartet, ist kein Fehler: neutral,
 * ein kurzes Label - die rote Karte bleibt echten Fehlern.
 */
class FirmwareHinweisTest {
    @Test
    fun eineFirmwareFuerEineNeuereAppIstNurEinStand() {
        assertEquals(
            FirmwareHinweis.Stand("Firmware update needs Boulder 0.50.0"),
            firmwareHinweis(FirmwareUpdateCheckResult.BrauchtNeuereApp("0.50.0")),
        )
    }

    @Test
    fun eineGescheitertePruefungBleibtEinFehler() {
        assertEquals(FirmwareHinweis.Fehler("Rate limited"), firmwareHinweis(FirmwareUpdateCheckResult.UpdateCheckFailed("Rate limited")))
    }

    @Test
    fun ohneHinweisNichts() {
        assertNull(firmwareHinweis(FirmwareUpdateCheckResult.FoundNoUpdate))
        assertNull(firmwareHinweis(null))
    }
}
