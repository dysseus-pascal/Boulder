package coredevices.pebble.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coredevices.pebble.R
import coredevices.pebble.kiesel.ansicht.SportAnsicht
import coredevices.pebble.kiesel.ansicht.SportListeAnsicht
import coredevices.pebble.kiesel.ansicht.SportTrendAnsicht
import coredevices.pebble.kiesel.ansicht.Sportwege
import coredevices.pebble.kiesel.ansicht.SpurenAnsicht
import coredevices.pebble.kiesel.ansicht.TrainingDetailAnsicht
import coredevices.pebble.kiesel.ansicht.heuteText
import java.time.LocalDate

/** Der Titel und ein grauer Zusatz rechts daneben, wie das Datum neben "Health". */
@Composable
private fun Seitenkopf(topBarParams: TopBarParams, titel: String, rechts: String?) {
    LaunchedEffect(Unit) {
        topBarParams.title(titel)
        topBarParams.searchAvailable(null)
        topBarParams.actions {
            if (rechts != null) Text(
                rechts, Modifier.padding(end = 16.dp),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
actual fun TrainingScreen(topBarParams: TopBarParams, nav: NavBarNav) {
    Seitenkopf(topBarParams, stringResource(R.string.ks_n_training), heuteText(LocalContext.current))
    SportAnsicht(
        Sportwege(
            detail = { nav.navigateTo(PebbleNavBarRoutes.TrainingDetailRoute(it)) },
            alle = { nav.navigateTo(PebbleNavBarRoutes.TrainingAlleRoute(it?.toString())) },
            trends = { nav.navigateTo(PebbleNavBarRoutes.SportTrendRoute) },
            spuren = { nav.navigateTo(PebbleNavBarRoutes.SpurenRoute) },
        )
    )
}

@Composable
actual fun TrainingAlleScreen(topBarParams: TopBarParams, nav: NavBarNav, tag: String?) {
    Seitenkopf(topBarParams, stringResource(R.string.ks_sp_alle), stringResource(R.string.ks_sp_90_tage))
    SportListeAnsicht(tag?.let { runCatching { LocalDate.parse(it) }.getOrNull() }) {
        nav.navigateTo(PebbleNavBarRoutes.TrainingDetailRoute(it))
    }
}

@Composable
actual fun SportTrendScreen(topBarParams: TopBarParams, nav: NavBarNav) {
    Seitenkopf(topBarParams, stringResource(R.string.ks_h_trends), stringResource(R.string.ks_sp_90_tage))
    SportTrendAnsicht()
}

@Composable
actual fun SpurenScreen(topBarParams: TopBarParams, nav: NavBarNav) {
    Seitenkopf(topBarParams, stringResource(R.string.ks_sp_spuren_titel), null)
    SpurenAnsicht()
}

@Composable
actual fun TrainingDetailScreen(topBarParams: TopBarParams, nav: NavBarNav, id: String) {
    LaunchedEffect(Unit) {
        topBarParams.actions {}
        topBarParams.searchAvailable(null)
    }
    TrainingDetailAnsicht(id) { topBarParams.title(it) }
}
