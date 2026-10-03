package coredevices.pebble.ui

import androidx.compose.runtime.Composable

/**
 * Boulder: die Einstellungen der Kiesel-Teile (Health, Sport, Navigation,
 * Boulder) als Eintraege in Gravels Einstellungsliste. Sie haengen an Health
 * Connect, OsmAnd und Android-Rechten - die Umsetzung steht deshalb in androidMain.
 */
@Composable
expect fun boulderSettingsItems(navBarNav: NavBarNav?): List<SettingsItem>

/**
 * Boulder: wie viele Eintraege oben ein "!" tragen - fuer Gravels
 * Sammelabzeichen der Leiste, das sonst nur Gravels Zahlen kennt.
 */
@Composable
expect fun boulderAbzeichen(): Int
