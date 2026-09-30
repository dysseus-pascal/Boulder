package coredevices.pebble.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class WatchappSettingsUrlNormalizationTest {
    @Test
    fun rewritesLegacyRawGitUrlsToRawGitHack() {
        val rawGitUrl = "https://cdn.rawgit.com/groyoh/minimalin/ffd0da5fb45f0722dee6e59eb4b05fa63ca82136/config/index.html"

        assertEquals(
            "https://raw.githack.com/groyoh/minimalin/ffd0da5fb45f0722dee6e59eb4b05fa63ca82136/config/index.html",
            normalizeWatchappSettingsUrl(rawGitUrl)
        )
    }

    @Test
    fun keepsNonRawGitUrlsAsIs() {
        val url = "https://example.com/config/index.html?foo=bar"

        assertEquals(url, normalizeWatchappSettingsUrl(url))
    }

    @Test
    fun upgradesCleartextSettingsPagesToHttps() {
        // TimeStyle aus dem Pebble-Store: Klartext ist in Boulder gesperrt.
        assertEquals(
            "https://freakified.github.io/TimeStylePebble/config_emery.html?appversion=9",
            normalizeWatchappSettingsUrl("http://freakified.github.io/TimeStylePebble/config_emery.html?appversion=9"),
        )
        assertEquals(
            "https://example.com/config#a=1",
            normalizeWatchappSettingsUrl("http://example.com:80/config#a=1"),
        )
    }

    @Test
    fun keepsExplicitPortsAndOtherSchemes() {
        assertEquals("https://example.com:8080/c", normalizeWatchappSettingsUrl("http://example.com:8080/c"))
        assertEquals("data:text/html,hi", normalizeWatchappSettingsUrl("data:text/html,hi"))
    }
}
