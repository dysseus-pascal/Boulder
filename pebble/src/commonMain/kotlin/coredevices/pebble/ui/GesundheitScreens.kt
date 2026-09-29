package coredevices.pebble.ui

import androidx.compose.runtime.Composable

/**
 * Boulder: die Gesundheitsuebersicht aus Kiesel-Helper. Sie liest Health
 * Connect - das gibt es nur auf Android; dort steht die Umsetzung
 * (coredevices.pebble.kiesel.ansicht).
 */
@Composable
expect fun GesundheitScreen(topBarParams: TopBarParams, nav: NavBarNav)
