package coredevices.pebble.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.stringResource
import coredevices.pebble.R
import coredevices.pebble.kiesel.ansicht.TrainingDetailAnsicht
import coredevices.pebble.kiesel.ansicht.TrainingListe

@Composable
actual fun TrainingScreen(topBarParams: TopBarParams, nav: NavBarNav) {
    val titel = stringResource(R.string.ks_n_training)
    LaunchedEffect(Unit) {
        topBarParams.title(titel)
        topBarParams.searchAvailable(null)
    }
    TrainingListe(
        setzeAktionen = { aktionen -> topBarParams.actions { aktionen() } },
        oeffne = { id -> nav.navigateTo(PebbleNavBarRoutes.TrainingDetailRoute(id)) },
    )
}

@Composable
actual fun TrainingDetailScreen(topBarParams: TopBarParams, nav: NavBarNav, id: String) {
    LaunchedEffect(Unit) {
        topBarParams.actions {}
        topBarParams.searchAvailable(null)
    }
    TrainingDetailAnsicht(id) { topBarParams.title(it) }
}
