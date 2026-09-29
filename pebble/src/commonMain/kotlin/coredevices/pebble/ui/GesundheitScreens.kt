package coredevices.pebble.ui

import androidx.compose.runtime.Composable

/**
 * Boulder: die Gesundheitsuebersicht aus Kiesel-Helper. Sie liest Health
 * Connect - das gibt es nur auf Android; dort steht die Umsetzung
 * (coredevices.pebble.kiesel.ansicht).
 */
@Composable
expect fun GesundheitScreen(topBarParams: TopBarParams, nav: NavBarNav)

@Composable
expect fun NachtScreen(topBarParams: TopBarParams, nav: NavBarNav, beginnUtc: Long)

@Composable
expect fun TrendScreen(topBarParams: TopBarParams, nav: NavBarNav, gruppe: String)

@Composable
expect fun SicherungScreen(topBarParams: TopBarParams, nav: NavBarNav)
