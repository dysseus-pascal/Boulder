package coredevices.pebble.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import coreapp.pebble.generated.resources.Res
import coreapp.pebble.generated.resources.health
import org.jetbrains.compose.resources.stringResource
import coredevices.pebble.kiesel.ansicht.GesundheitAnsicht
import coredevices.pebble.kiesel.ansicht.NachtAnsicht
import coredevices.pebble.kiesel.ansicht.SicherungAnsicht
import coredevices.pebble.kiesel.ansicht.TrendAnsicht

@Composable
actual fun GesundheitScreen(topBarParams: TopBarParams, nav: NavBarNav) {
    val titel = stringResource(Res.string.health)
    LaunchedEffect(Unit) {
        topBarParams.title(titel)
        topBarParams.searchAvailable(null)
        topBarParams.actions {}
    }
    GesundheitAnsicht(
        oeffneNacht = { nav.navigateTo(PebbleNavBarRoutes.NachtRoute(it)) },
        oeffneTrend = { nav.navigateTo(PebbleNavBarRoutes.TrendRoute(it)) },
    )
}

@Composable
actual fun NachtScreen(topBarParams: TopBarParams, nav: NavBarNav, beginnUtc: Long) {
    LaunchedEffect(Unit) {
        topBarParams.searchAvailable(null)
        topBarParams.actions {}
    }
    NachtAnsicht(beginnUtc, { topBarParams.title(it) }) { nav.navigateTo(PebbleNavBarRoutes.TrendRoute("schlaf")) }
}

@Composable
actual fun TrendScreen(topBarParams: TopBarParams, nav: NavBarNav, gruppe: String) {
    LaunchedEffect(Unit) {
        topBarParams.searchAvailable(null)
        topBarParams.actions {}
    }
    TrendAnsicht(gruppe) { topBarParams.title(it) }
}

@Composable
actual fun SicherungScreen(topBarParams: TopBarParams, nav: NavBarNav) {
    val titel = androidx.compose.ui.res.stringResource(coredevices.pebble.R.string.ks_b_titel)
    LaunchedEffect(Unit) {
        topBarParams.title(titel)
        topBarParams.searchAvailable(null)
        topBarParams.actions {}
    }
    SicherungAnsicht()
}

@Composable
private fun EinfacheSeite(topBarParams: TopBarParams, titelId: Int, inhalt: @Composable () -> Unit) {
    val titel = androidx.compose.ui.res.stringResource(titelId)
    LaunchedEffect(Unit) {
        topBarParams.title(titel)
        topBarParams.searchAvailable(null)
        topBarParams.actions {}
    }
    inhalt()
}

@Composable
actual fun KieselsportScreen(topBarParams: TopBarParams, nav: NavBarNav) =
    EinfacheSeite(topBarParams, coredevices.pebble.R.string.ks_n_einstellungen) { coredevices.pebble.kiesel.ansicht.KieselsportAnsicht() }

@Composable
actual fun ProtokollScreen(topBarParams: TopBarParams, nav: NavBarNav) =
    EinfacheSeite(topBarParams, coredevices.pebble.R.string.ks_n_protokoll) { coredevices.pebble.kiesel.ansicht.ProtokollAnsicht() }

@Composable
actual fun AkkuScreen(topBarParams: TopBarParams, nav: NavBarNav) =
    EinfacheSeite(topBarParams, coredevices.pebble.R.string.ks_akku_titel) { coredevices.pebble.kiesel.ansicht.AkkuAnsicht() }
