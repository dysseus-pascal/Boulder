package coredevices.pebble.ui

import androidx.compose.runtime.Composable

/**
 * Boulder: die Einstellungen der Kiesel-Teile (Health, Sport, Navigation) als
 * Eintraege in Gravels Einstellungsliste. Sie haengen an Health Connect,
 * OsmAnd und Android-Rechten - die Umsetzung steht deshalb in androidMain.
 */
@Composable
expect fun boulderSettingsItems(navBarNav: NavBarNav?): List<SettingsItem>
