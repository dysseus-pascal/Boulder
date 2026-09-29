package coredevices.pebble.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import coreapp.pebble.generated.resources.Res
import coreapp.pebble.generated.resources.health
import org.jetbrains.compose.resources.stringResource
import coredevices.pebble.kiesel.ansicht.GesundheitAnsicht
import coredevices.pebble.kiesel.ansicht.NachtAnsicht

@Composable
actual fun GesundheitScreen(topBarParams: TopBarParams, nav: NavBarNav) {
    val titel = stringResource(Res.string.health)
    LaunchedEffect(Unit) {
        topBarParams.title(titel)
        topBarParams.searchAvailable(null)
        topBarParams.actions {}
    }
    GesundheitAnsicht(oeffneNacht = { nav.navigateTo(PebbleNavBarRoutes.NachtRoute(it)) })
}

@Composable
actual fun NachtScreen(topBarParams: TopBarParams, nav: NavBarNav, beginnUtc: Long) {
    LaunchedEffect(Unit) {
        topBarParams.searchAvailable(null)
        topBarParams.actions {}
    }
    NachtAnsicht(beginnUtc) { topBarParams.title(it) }
}
