package coredevices.pebble.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import coredevices.pebble.kiesel.ansicht.StackAnsicht
import coredevices.pebble.kiesel.ansicht.TrendAnsicht
import coredevices.pebble.kiesel.ansicht.WasserAnsicht
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.res.stringResource as androidString

@Composable
actual fun GesundheitScreen(topBarParams: TopBarParams, nav: NavBarNav) {
    val titel = stringResource(Res.string.health)
    LaunchedEffect(Unit) {
        topBarParams.title(titel)
        topBarParams.searchAvailable(null)
        // Kein Datum mehr rechts (0.51, Entwurf "Erlebnis-Health"). Leer gesetzt, nicht
        // weggelassen: zurueck von Sport stuende sonst dessen Datum neben "Health".
        topBarParams.actions {}
    }
    GesundheitAnsicht(gesundheitswege(nav))
}

/**
 * Wohin Health fuehrt. ALLES AUF DEN STAPEL VON HEALTH, auch Sport: seit
 * 0.51 ist Sport kein Reiter mehr, sondern eine Unterseite - der Pfeil oben
 * und die Zurueck-Taste fuehren so von selbst nach Health zurueck. Ebenso
 * Water und Stack; die Ernaehrungs-Trends, zu denen bis 0.50 die Zeilen
 * Water und Caffeine fuehrten, erreicht man von beiden aus ([ernaehrungstrend]).
 */
internal fun gesundheitswege(nav: NavBarNav) = Gesundheitswege(
    nacht = { nav.navigateTo(PebbleNavBarRoutes.NachtRoute(it)) },
    trend = { nav.navigateTo(PebbleNavBarRoutes.TrendRoute(it)) },
    herz = { nav.navigateTo(PebbleNavBarRoutes.HerzRoute) },
    akku = { nav.navigateTo(PebbleNavBarRoutes.AkkuRoute) },
    sport = { nav.navigateTo(PebbleNavBarRoutes.TrainingRoute) },
    training = { nav.navigateTo(PebbleNavBarRoutes.TrainingDetailRoute(it)) },
    wasser = { nav.navigateTo(PebbleNavBarRoutes.WasserRoute) },
    stack = { nav.navigateTo(PebbleNavBarRoutes.StackRoute) },
)

/** Die Trends von Wasser, Koffein und Praeparaten - die letzte Zeile von Water und Stack. */
internal fun ernaehrungstrend(nav: NavBarNav): () -> Unit = { nav.navigateTo(PebbleNavBarRoutes.TrendRoute("ernaehrung")) }

/**
 * Der Titel der Herzseite: derselbe Name wie ihre Zeile auf Health ("Heart &
 * Body") - wer die Zeile antippt, soll oben dasselbe Wort finden.
 */
internal val HERZ_TITEL = R.string.ks_d_herz

@Composable
actual fun HerzScreen(topBarParams: TopBarParams, nav: NavBarNav) {
    val titel = androidString(HERZ_TITEL)
    LaunchedEffect(Unit) {
        topBarParams.title(titel)
        topBarParams.searchAvailable(null)
        topBarParams.actions {}
    }
    HerzAnsicht(
        oeffneNacht = { nav.navigateTo(PebbleNavBarRoutes.NachtRoute(it)) },
        oeffneTrend = { nav.navigateTo(PebbleNavBarRoutes.TrendRoute("herz")) },
        // Seit 0.52 der Weg zur Koerperseite ("Body >" in der Karte Body); bis dahin fuehrte die Zeile Body auf Health dorthin.
        oeffneKoerper = { nav.navigateTo(PebbleNavBarRoutes.KoerperRoute) },
    )
}

@Composable
actual fun WasserScreen(topBarParams: TopBarParams, nav: NavBarNav) =
    EinfacheSeite(topBarParams, R.string.ks_d_wasser) { WasserAnsicht(ernaehrungstrend(nav)) }

@Composable
actual fun StackScreen(topBarParams: TopBarParams, nav: NavBarNav) =
    EinfacheSeite(topBarParams, R.string.ks_st_titel) { StackAnsicht(ernaehrungstrend(nav)) }

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
