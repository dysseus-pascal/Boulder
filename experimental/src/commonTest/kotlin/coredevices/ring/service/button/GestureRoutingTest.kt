package coredevices.ring.service.button

import com.russhwolf.settings.MapSettings
import coredevices.ring.database.MusicControlMode
import coredevices.ring.database.PreferencesImpl
import coredevices.ring.database.SecondaryMode
import coredevices.ring.service.ButtonPress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GestureRoutingTest {

    private fun routing(
        musicControlMode: MusicControlMode? = null,
        secondaryMode: SecondaryMode? = null,
        mcpGroupId: Long? = null,
        stored: MapSettings = MapSettings(),
    ): GestureRoutingPreferences {
        val legacy = MapSettings()
        musicControlMode?.let { legacy.putInt("music_control_mode", it.id) }
        secondaryMode?.let { legacy.putInt("ring_secondary_mode", it.id) }
        mcpGroupId?.let { legacy.putLong("ring_secondary_mode_mcp_group", it) }
        return GestureRoutingPreferences(stored, PreferencesImpl(legacy))
    }

    @Test
    fun sequencesResolveToGestures() {
        assertEquals(RingGesture.Click, RingGesture.forSequence(listOf(ButtonPress.Short)))
        assertEquals(
            RingGesture.DoubleClick,
            RingGesture.forSequence(listOf(ButtonPress.Short, ButtonPress.Short))
        )
        assertEquals(
            RingGesture.TripleClick,
            RingGesture.forSequence(listOf(ButtonPress.Short, ButtonPress.Short, ButtonPress.Short))
        )
        assertEquals(RingGesture.Hold, RingGesture.forSequence(listOf(ButtonPress.Long)))
        assertEquals(
            RingGesture.ClickHold,
            RingGesture.forSequence(listOf(ButtonPress.Short, ButtonPress.Long))
        )
        assertNull(RingGesture.forSequence(emptyList()))
        assertNull(RingGesture.forSequence(listOf(ButtonPress.Long, ButtonPress.Long)))
        assertNull(RingGesture.forSequence(listOf(ButtonPress.Long, ButtonPress.Short)))
    }

    @Test
    fun typicalLegacyInstallMigratesEveryGesture() {
        val routes = routing(MusicControlMode.DoubleClick, SecondaryMode.Search).routes.value

        assertEquals(GestureDestination.Nothing, routes[RingGesture.Click])
        assertEquals(GestureDestination.PlayPause, routes[RingGesture.DoubleClick])
        assertEquals(GestureDestination.NextTrack, routes[RingGesture.TripleClick])
        assertEquals(GestureDestination.IndexAgent, routes[RingGesture.Hold])
        assertEquals(GestureDestination.WebSearch, routes[RingGesture.ClickHold])
    }

    @Test
    fun musicControlModeDisabledMigratesToNothing() {
        val routes = routing(MusicControlMode.Disabled).routes.value

        assertEquals(GestureDestination.Nothing, routes[RingGesture.Click])
        assertEquals(GestureDestination.Nothing, routes[RingGesture.DoubleClick])
        assertEquals(GestureDestination.Nothing, routes[RingGesture.TripleClick])
    }

    @Test
    fun musicControlModeSingleClickMigratesToClickPlayPause() {
        val routes = routing(MusicControlMode.SingleClick).routes.value

        assertEquals(GestureDestination.PlayPause, routes[RingGesture.Click])
        assertEquals(GestureDestination.NextTrack, routes[RingGesture.DoubleClick])
        assertEquals(GestureDestination.Nothing, routes[RingGesture.TripleClick])
    }

    @Test
    fun secondaryModeMigratesToClickHoldRoute() {
        assertEquals(
            GestureDestination.IndexAgent,
            routing(secondaryMode = SecondaryMode.Disabled).destinationFor(RingGesture.ClickHold)
        )
        assertEquals(
            GestureDestination.WebSearch,
            routing(secondaryMode = SecondaryMode.Search).destinationFor(RingGesture.ClickHold)
        )
        assertEquals(
            GestureDestination.McpSandbox(7L),
            routing(secondaryMode = SecondaryMode.McpSandbox, mcpGroupId = 7L)
                .destinationFor(RingGesture.ClickHold)
        )
        assertEquals(
            GestureDestination.McpSandbox(null),
            routing(secondaryMode = SecondaryMode.McpSandbox).destinationFor(RingGesture.ClickHold)
        )
    }

    @Test
    fun holdMigratesToIndexAgent() {
        MusicControlMode.entries.forEach { music ->
            SecondaryMode.entries.forEach { secondary ->
                assertEquals(
                    GestureDestination.IndexAgent,
                    routing(music, secondary).destinationFor(RingGesture.Hold),
                    "hold route for $music/$secondary"
                )
            }
        }
    }

    @Test
    fun defaultsMatchLegacyDefaults() {
        assertEquals(
            migratedRoutes(MusicControlMode.DoubleClick, SecondaryMode.Search, null),
            routing().routes.value
        )
    }

    @Test
    fun onlyMatchingDestinationKindsAreAccepted() {
        val music = listOf(RingGesture.Click, RingGesture.DoubleClick, RingGesture.TripleClick)
        val recording = listOf(RingGesture.Hold, RingGesture.ClickHold)

        music.forEach {
            assertTrue(it.accepts(GestureDestination.PlayPause))
            assertTrue(it.accepts(GestureDestination.NextTrack))
            assertTrue(it.accepts(GestureDestination.Nothing))
            assertFalse(it.accepts(GestureDestination.IndexAgent))
            assertFalse(it.accepts(GestureDestination.WebSearch))
            assertFalse(it.accepts(GestureDestination.McpSandbox(1L)))
        }
        recording.forEach {
            assertTrue(it.accepts(GestureDestination.IndexAgent))
            assertTrue(it.accepts(GestureDestination.WebSearch))
            assertTrue(it.accepts(GestureDestination.McpSandbox(1L)))
            assertTrue(it.accepts(GestureDestination.Nothing))
            assertFalse(it.accepts(GestureDestination.PlayPause))
            assertFalse(it.accepts(GestureDestination.NextTrack))
        }
    }

    @Test
    fun settingAnInvalidDestinationFails() {
        val routing = routing()

        assertFailsWith<IllegalArgumentException> {
            routing.setRoute(RingGesture.Click, GestureDestination.IndexAgent)
        }
        assertFailsWith<IllegalArgumentException> {
            routing.setRoute(RingGesture.Hold, GestureDestination.PlayPause)
        }
    }

    @Test
    fun routesRoundTripThroughSettings() {
        val stored = MapSettings()
        routing(stored = stored).apply {
            setRoute(RingGesture.Click, GestureDestination.PlayPause)
            setRoute(RingGesture.Hold, GestureDestination.McpSandbox(42L))
        }

        val reloaded = routing(MusicControlMode.Disabled, SecondaryMode.Search, stored = stored)

        assertEquals(GestureDestination.PlayPause, reloaded.destinationFor(RingGesture.Click))
        assertEquals(GestureDestination.McpSandbox(42L), reloaded.destinationFor(RingGesture.Hold))
        assertEquals(GestureDestination.WebSearch, reloaded.destinationFor(RingGesture.ClickHold))
        assertEquals(GestureDestination.Nothing, reloaded.destinationFor(RingGesture.DoubleClick))
    }

    @Test
    fun storedRouteOfTheWrongKindFallsBackToMigration() {
        val stored = MapSettings("ring_gesture_route_Hold" to "play_pause")

        assertEquals(
            GestureDestination.IndexAgent,
            routing(stored = stored).destinationFor(RingGesture.Hold)
        )
    }

    @Test
    fun recordingDestinationFallsBackToIndexAgentForNonRecordingGestures() {
        val routing = routing(MusicControlMode.SingleClick)

        assertEquals(GestureDestination.IndexAgent, routing.recordingDestinationFor(null))
        assertEquals(GestureDestination.IndexAgent, routing.recordingDestinationFor(RingGesture.Click))
        assertEquals(
            GestureDestination.IndexAgent,
            routing.recordingDestinationFor(RingGesture.DoubleClick)
        )
    }
}
