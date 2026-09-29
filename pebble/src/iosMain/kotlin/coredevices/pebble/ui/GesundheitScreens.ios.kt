package coredevices.pebble.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

@Composable
actual fun GesundheitScreen(topBarParams: TopBarParams, nav: NavBarNav) {
    Text("Health is only available on Android.")
}

@Composable
actual fun NachtScreen(topBarParams: TopBarParams, nav: NavBarNav, beginnUtc: Long) {
    Text("Health is only available on Android.")
}

@Composable
actual fun TrendScreen(topBarParams: TopBarParams, nav: NavBarNav, gruppe: String) {
    Text("Health is only available on Android.")
}
