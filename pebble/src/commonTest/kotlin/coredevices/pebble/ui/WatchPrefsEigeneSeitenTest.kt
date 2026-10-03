package coredevices.pebble.ui

import io.rebble.libpebblecommon.database.dao.WatchPreference
import io.rebble.libpebblecommon.database.entity.BoolWatchPref
import io.rebble.libpebblecommon.database.entity.BytesWatchPref
import io.rebble.libpebblecommon.database.entity.WatchPref
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Boulder: TimeStyle Bedrock hat eine eigene Seite und darf in den Uhreinstellungen nirgends auftauchen. */
class WatchPrefsEigeneSeitenTest {
    private val alle: List<WatchPreference<*>> = WatchPref.enumeratePrefs().map { WatchPreference(it, null) }

    @Test
    fun uhreinstellungenOhneBytesWerte() {
        val gezeigt = alle.ohneEigeneSeiten()
        assertTrue(gezeigt.none { it.pref is BytesWatchPref })
        assertEquals(alle.size - BytesWatchPref.entries.size, gezeigt.size)
    }

    @Test
    fun resetLaesstBytesWerteAus() {
        val geaendert = listOf(
            WatchPreference(BytesWatchPref.TimeStyleBedrock, "01f4c1dfff02010203090dfb2c8f03004e594300000000000000000000000000"),
            WatchPreference(BoolWatchPref.Clock24h, true),
            // Schon auf der Vorgabe: bleibt wie bei Gravel unangetastet.
            WatchPreference(BoolWatchPref.Backlight, true),
        )
        assertEquals(listOf<WatchPref<*>>(BoolWatchPref.Clock24h), geaendert.zumZuruecksetzen().map { it.pref })
    }

    @Test
    fun abschnittFuerJedenWert() {
        // Das when in section() ist erschoepfend: jeder Wert hat einen Abschnitt, auch der nie gezeigte.
        WatchPref.enumeratePrefs().forEach { it.section() }
    }
}
