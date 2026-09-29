package coredevices.pebble.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import coredevices.pebble.R
import coredevices.pebble.health.HealthConnectPermissionBridge
import coredevices.pebble.kiesel.KIESEL_RECHTE
import coredevices.pebble.kiesel.KieselEinstellungen
import coredevices.pebble.kiesel.Kieselstrasse
import coredevices.pebble.kiesel.Sichern
import coredevices.pebble.kiesel.SpurDienst
import coredevices.pebble.kiesel.TrainingsDaten
import kotlinx.coroutines.launch

/**
 * Die Einstellungen der Kiesel-Teile als Eintraege in Gravels Liste: unter
 * Health die Rechte und die Sicherung, unter Sport Kieselsport, Standort und
 * Protokoll, unter Navigation OsmAnd und die Kartenlinks.
 */
@Composable
actual fun boulderSettingsItems(navBarNav: NavBarNav?): List<SettingsItem> {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Zurueck aus den Systemeinstellungen oder aus OsmAnd: Rechte und Lage neu lesen.
    var neu by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (Kieselstrasse.lage == Kieselstrasse.Lage.FREISCHALTEN) Kieselstrasse.versucheErneut(context)
        neu++
    }
    val fehlend by produceState(emptySet<String>(), neu) { value = TrainingsDaten.fehlendeRechte(context) }
    fun gehe(route: NavBarRoute): () -> Unit = { navBarNav?.navigateTo(route) }
    fun oeffne(intent: Intent): () -> Unit = {
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
    val appSeite = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))

    val k = remember(neu) { KieselEinstellungen.stand(context) }
    val zuletzt = Sichern.zuletzt(context)
    val osmand = Kieselstrasse.osmandPaket(context)
    val lage = Kieselstrasse.lage

    return listOf(
        basicSettingsActionItem(
            title = stringResource(R.string.ks_e_rechte),
            description = if (fehlend.isEmpty()) stringResource(R.string.ks_e_rechte_ok) else stringResource(R.string.ks_e_rechte_fehlen, fehlend.size),
            topLevelType = TopLevelType.Phone,
            section = Section.Health,
            keywords = "health connect permissions water supplements sleep",
            badge = if (fehlend.isNotEmpty()) "!" else null,
            action = { scope.launch { HealthConnectPermissionBridge.request(context, KIESEL_RECHTE); neu++ } },
        ),
        basicSettingsActionItem(
            title = stringResource(R.string.ks_b_titel),
            description = when {
                Sichern.ordnerUri(context) == null -> stringResource(R.string.ks_e_sicherung_kein)
                zuletzt == 0L -> stringResource(R.string.ks_b_nie)
                else -> stringResource(R.string.ks_b_zuletzt, DateUtils.getRelativeTimeSpanString(zuletzt).toString())
            },
            topLevelType = TopLevelType.Phone,
            section = Section.Health,
            keywords = "backup restore kiesel-helper take over",
            action = gehe(PebbleNavBarRoutes.SicherungRoute),
        ),
        basicSettingsActionItem(
            title = stringResource(R.string.ks_n_einstellungen),
            description = stringResource(R.string.ks_e_kieselsport_text, k.maxpuls, k.pause, k.becken),
            topLevelType = TopLevelType.Phone,
            section = Section.Sport,
            keywords = "kieselsport max heart rate pool rest pin",
            action = gehe(PebbleNavBarRoutes.KieselsportRoute),
        ),
        basicSettingsActionItem(
            title = stringResource(R.string.ks_e_standort),
            description = stringResource(
                when {
                    SpurDienst.darfImmerOrten(context) -> R.string.ks_e_standort_immer
                    SpurDienst.darfOrten(context) -> R.string.ks_e_standort_nutzung
                    else -> R.string.ks_e_standort_nicht
                }
            ),
            topLevelType = TopLevelType.Phone,
            section = Section.Sport,
            keywords = "location gps track",
            badge = if (!SpurDienst.darfImmerOrten(context)) "!" else null,
            action = oeffne(appSeite),
        ),
        basicSettingsActionItem(
            title = stringResource(R.string.ks_n_protokoll),
            description = stringResource(R.string.ks_e_protokoll_text),
            topLevelType = TopLevelType.Phone,
            section = Section.Sport,
            keywords = "log kieselsport drinktervall supcycle",
            action = gehe(PebbleNavBarRoutes.ProtokollRoute),
        ),
        basicSettingsActionItem(
            title = stringResource(R.string.ks_e_osmand),
            description = if (osmand == null) stringResource(R.string.ks_e_osmand_fehlt) else Kieselstrasse.lageText(context),
            topLevelType = TopLevelType.Phone,
            section = Section.Navigation,
            keywords = "osmand kieselstrasse navigation turn",
            badge = if (lage == Kieselstrasse.Lage.FREISCHALTEN || lage == Kieselstrasse.Lage.ABO_FEHLER) "!" else null,
            action = when {
                osmand == null -> null
                lage == Kieselstrasse.Lage.FREISCHALTEN -> context.packageManager.getLaunchIntentForPackage(osmand)?.let { oeffne(it) }
                else -> {
                    { Kieselstrasse.versucheErneut(context); neu++; Unit }
                }
            },
        ),
        basicSettingsActionItem(
            title = stringResource(R.string.ks_e_kartenlinks),
            description = stringResource(R.string.ks_e_kartenlinks_text),
            topLevelType = TopLevelType.Phone,
            section = Section.Navigation,
            keywords = "google maps links geo osmand",
            action = oeffne(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, Uri.fromParts("package", context.packageName, null))
                } else appSeite
            ),
        ),
    )
}
