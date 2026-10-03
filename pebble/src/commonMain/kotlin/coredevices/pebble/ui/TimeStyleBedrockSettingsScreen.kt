package coredevices.pebble.ui

import androidx.compose.runtime.Composable

/**
 * Boulder: die Einstellungen von TimeStyle Bedrock, geoeffnet ueber den
 * Settings-Knopf des Zifferblatts (Apps > Faces). Die Seite baut auf
 * Boulders Seitenbaukasten, der nur auf Android steht - darum expect/actual.
 */
@Composable
expect fun TimeStyleBedrockSettingsScreen(topBarParams: TopBarParams, nav: NavBarNav)
