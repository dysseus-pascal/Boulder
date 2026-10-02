package coredevices.pebble.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coreapp.pebble.generated.resources.Res
import coreapp.pebble.generated.resources.health
import coredevices.pebble.R
import coredevices.pebble.kiesel.ansicht.AkkuAnsicht
import coredevices.pebble.kiesel.ansicht.GesundheitAnsicht
import coredevices.pebble.kiesel.ansicht.Gesundheitswege
import coredevices.pebble.kiesel.ansicht.HerzAnsicht
import coredevices.pebble.kiesel.ansicht.KieselsportAnsicht
import coredevices.pebble.kiesel.ansicht.KoerperAnsicht
import coredevices.pebble.kiesel.ansicht.NachtAnsicht
import coredevices.pebble.kiesel.ansicht.ProtokollAnsicht
import coredevices.pebble.kiesel.ansicht.SicherungAnsicht
import coredevices.pebble.kiesel.ansicht.TrendAnsicht
import coredevices.pebble.kiesel.ansicht.heuteText
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.res.stringResource as androidString

@Composable
actual fun GesundheitScreen(topBarParams: TopBarParams, nav: NavBarNav) {
    val titel = stringResource(Res.string.health)
    val context = LocalContext.current
    // Neu mit jedem Stand der Seite: nach Mitternacht spraeche sie sonst von gestern.
    var heute by remember { mutableStateOf(heuteText(context)) }
    LaunchedEffect(Unit) {
        topBarParams.title(titel)
        topBarParams.searchAvailable(null)
        // Das Datum rechts neben dem Titel, wie im Entwurf - die Seite spricht von "heute".
        topBarParams.actions {
            Text(
                heute, Modifier.padding(end = 16.dp),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    GesundheitAnsicht(
        Gesundheitswege(
            nacht = { nav.navigateTo(PebbleNavBarRoutes.NachtRoute(it)) },
            trend = { nav.navigateTo(PebbleNavBarRoutes.TrendRoute(it)) },
            herz = { nav.navigateTo(PebbleNavBarRoutes.HerzRoute) },
            training = { nav.navigateTo(PebbleNavBarRoutes.TrainingDetailRoute(it)) },
            akku = { nav.navigateTo(PebbleNavBarRoutes.AkkuRoute) },
            koerper = { nav.navigateTo(PebbleNavBarRoutes.KoerperRoute) },
        ),
        geladen = { heute = heuteText(context) },
    )
}

@Composable
actual fun HerzScreen(topBarParams: TopBarParams, nav: NavBarNav) {
    val titel = androidString(R.string.ks_h_herz_heute)
    LaunchedEffect(Unit) {
        topBarParams.title(titel)
        topBarParams.searchAvailable(null)
        topBarParams.actions {}
    }
    HerzAnsicht(
        oeffneNacht = { nav.navigateTo(PebbleNavBarRoutes.NachtRoute(it)) },
        oeffneTrend = { nav.navigateTo(PebbleNavBarRoutes.TrendRoute("herz")) },
    )
}

@Composable
actual fun KoerperScreen(topBarParams: TopBarParams, nav: NavBarNav) =
    EinfacheSeite(topBarParams, R.string.ks_kp_titel) { KoerperAnsicht() }

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
    val titel = androidString(R.string.ks_b_titel)
    LaunchedEffect(Unit) {
        topBarParams.title(titel)
        topBarParams.searchAvailable(null)
        topBarParams.actions {}
    }
    SicherungAnsicht()
}

@Composable
private fun EinfacheSeite(topBarParams: TopBarParams, titelId: Int, inhalt: @Composable () -> Unit) {
    val titel = androidString(titelId)
    LaunchedEffect(Unit) {
        topBarParams.title(titel)
        topBarParams.searchAvailable(null)
        topBarParams.actions {}
    }
    inhalt()
}

@Composable
actual fun KieselsportScreen(topBarParams: TopBarParams, nav: NavBarNav) =
    EinfacheSeite(topBarParams, R.string.ks_n_einstellungen) { KieselsportAnsicht() }

@Composable
actual fun ProtokollScreen(topBarParams: TopBarParams, nav: NavBarNav) =
    EinfacheSeite(topBarParams, R.string.ks_n_protokoll) { ProtokollAnsicht() }

@Composable
actual fun AkkuScreen(topBarParams: TopBarParams, nav: NavBarNav) =
    EinfacheSeite(topBarParams, R.string.ks_akku_titel) { AkkuAnsicht() }
