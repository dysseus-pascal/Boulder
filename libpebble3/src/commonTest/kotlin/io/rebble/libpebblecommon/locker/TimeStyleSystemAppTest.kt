package io.rebble.libpebblecommon.locker

import io.rebble.libpebblecommon.metadata.WatchType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

/** Boulder: TimeStyle Bedrock steht unter Apps > Faces wie Tictoc und Kickstart, aber mit Untertitel "Boulder". */
class TimeStyleSystemAppTest {
    @Test
    fun eintrag() {
        val ts = SystemApps.TimeStyleBedrock
        assertEquals(Uuid.parse("d8af11d3-1e92-4f6d-b494-c5572f7bb7df"), ts.uuid)
        assertEquals(AppType.Watchface, ts.type)
        assertEquals(listOf(WatchType.EMERY), ts.compatiblePlatforms)
        assertEquals("Boulder", ts.developerName)
        assertEquals("TimeStyle Bedrock", ts.displayName)
        // Direkt hinter Kickstart: so setzt insertSystemApps es hinter seinen Vorgaenger.
        assertEquals(SystemApps.Kickstart.ordinal + 1, ts.ordinal)
    }

    @Test
    fun untertitelJeApp() {
        assertEquals("Boulder", SystemApps.TimeStyleBedrock.wrap(0).properties.developerName)
        assertEquals("Pebble", SystemApps.Kickstart.wrap(0).properties.developerName)
        assertEquals("Pebble", SystemApps.Tictoc.wrap(0).properties.developerName)
    }

    @Test
    fun reihenfolgeEindeutig() {
        val reihen = SystemApps.entries.map { it.defaultOrder }
        assertEquals(reihen.size, reihen.toSet().size)
    }
}
