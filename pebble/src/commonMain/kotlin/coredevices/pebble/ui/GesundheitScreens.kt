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

// Boulder: der Puls des Tages mit Ruhepuls, HRV und SpO2 - aus der Gesundheitsseite.
@Composable
expect fun HerzScreen(topBarParams: TopBarParams, nav: NavBarNav)

// Boulder 0.51: Water - die Menge von heute, die Glaeser und die Woche - aus der Gesundheitsseite.
@Composable
expect fun WasserScreen(topBarParams: TopBarParams, nav: NavBarNav)

// Boulder 0.51: Stack - Praeparate und Koffein - aus der Gesundheitsseite.
@Composable
expect fun StackScreen(topBarParams: TopBarParams, nav: NavBarNav)

// Boulder: Gewicht und Zusammensetzung einer Waage aus Health Connect - aus der Gesundheitsseite.
@Composable
expect fun KoerperScreen(topBarParams: TopBarParams, nav: NavBarNav)

@Composable
expect fun SicherungScreen(topBarParams: TopBarParams, nav: NavBarNav)

@Composable
expect fun KieselsportScreen(topBarParams: TopBarParams, nav: NavBarNav)

// Boulder 0.53: Schritt- und Schlafziel, auch fuer die Uhr - aus den Einstellungen (Health).
@Composable
expect fun ZieleScreen(topBarParams: TopBarParams, nav: NavBarNav)

@Composable
expect fun ProtokollScreen(topBarParams: TopBarParams, nav: NavBarNav)

// Boulder: die Minuten eines Zeitraums als CSV - voruebergehend, aus dem Debug-Teil (F33).
@Composable
expect fun MinutenexportScreen(topBarParams: TopBarParams, nav: NavBarNav)

// Boulder: der Akku der Uhr aus den Naechten der Firmware - an der Stelle
// der Akkuseite, die Gravel mit der Telemetrie herausnahm.
@Composable
expect fun AkkuScreen(topBarParams: TopBarParams, nav: NavBarNav)
