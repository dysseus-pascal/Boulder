package coredevices.pebble.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import coredevices.pebble.R
import coredevices.pebble.health.frageAlleAkteRechte
import coredevices.pebble.kiesel.Formate
import coredevices.pebble.kiesel.Gesundheitsziele
import coredevices.pebble.kiesel.Herzgrenzen
import coredevices.pebble.kiesel.KieselEinstellungen
import coredevices.pebble.kiesel.Kieselstrasse
import coredevices.pebble.kiesel.Nachtexport
import coredevices.pebble.kiesel.Sichern
import coredevices.pebble.kiesel.SpurDienst
import coredevices.pebble.kiesel.TrainingsDaten
import coredevices.pebble.kiesel.versuche
import coredevices.util.CoreConfigHolder
import coredevices.util.Permission
import coredevices.util.PermissionRequester
import io.rebble.libpebblecommon.connection.HealthDataApi
import io.rebble.libpebblecommon.connection.LibPebble
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Die Einstellungen der Kiesel-Teile als Eintraege in Gravels Liste: unter
 * Health die Rechte der Akte - die EINE Stelle, an der Boulder sie in den
 * Einstellungen anbietet - und, solange Gravel ihn verbirgt, der Schalter
 * fuer die Einheiten; unter Sport Kieselsport und die Routen, unter
 * Navigation OsmAnd und die Kartenlinks, unter Boulder Sicherung und
 * Protokoll: beide betreffen die ganze App, nicht nur Health oder Sport.
 */
@Composable
actual fun boulderSettingsItems(navBarNav: NavBarNav?): List<SettingsItem> {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val api = koinInject<HealthDataApi>()
    val libPebble = koinInject<LibPebble>()
    val gesundheit by remember(libPebble) { libPebble.healthSettings }.collectAsState(null)
    // Zurueck aus den Systemeinstellungen oder aus OsmAnd: Rechte und Lage neu lesen.
    var neu by remember { mutableIntStateOf(0) }
    val osmand = Kieselstrasse.osmandPaket(context)
    var zuOsmand by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        // Nur aus OsmAnd zurueck erneut: dort wurde vielleicht gerade freigeschaltet.
        if (osmand != null) {
            if (zuOsmand) Kieselstrasse.pruefe(context) else Kieselstrasse.pruefeEinmal(context)
        }
        zuOsmand = false
        neu++
    }
    val mahnung = mahnungen(context, neu)
    val fehlend = mahnung.fehlendeRechte
    fun gehe(route: NavBarRoute): () -> Unit = { navBarNav?.navigateTo(route) }
    fun oeffne(intent: Intent): () -> Unit = {
        versuche("Einstellungen", "Seite ${intent.action} nicht geoeffnet") { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
    val appSeite = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))

    val k = remember(neu) { KieselEinstellungen.stand(context) }
    val ziele = remember(neu) { Gesundheitsziele.ziele(context) }
    // Der Maximalpuls, mit dem gerechnet wird: ohne Einstellung der nach dem Alter, nicht die 190 der Uhr.
    val maximum by produceState<Herzgrenzen.Maximum?>(null, neu) { value = Herzgrenzen.maximum(context, null) }
    val zuletzt = Sichern.zuletzt(context)
    val strasse by Kieselstrasse.stand.collectAsState()
    val lage = strasse.lage
    val keineNacht = stringResource(R.string.ks_e_nachtexport_keine)

    return listOfNotNull(
        basicSettingsActionItem(
            title = stringResource(R.string.ks_e_rechte),
            description = if (fehlend.isEmpty()) stringResource(R.string.ks_e_rechte_ok)
            else pluralStringResource(R.plurals.ks_e_rechte_fehlen, fehlend.size, fehlend.size),
            topLevelType = TopLevelType.Phone,
            section = Section.Health,
            keywords = "health connect permissions water supplements sleep",
            badge = if (mahnung.rechte) "!" else null,
            action = { scope.launch { frageAlleAkteRechte(context); neu++ } },
        ),
        basicSettingsActionItem(
            title = stringResource(R.string.ks_z_titel),
            description = stringResource(
                R.string.ks_z_zeile, Formate.zahl(ziele.schritte.toLong()), Formate.dauer(ziele.schlafMin.toLong()),
            ),
            topLevelType = TopLevelType.Phone,
            section = Section.Health,
            keywords = "goals target steps sleep",
            action = gehe(PebbleNavBarRoutes.ZieleRoute),
        ),
        // Gravels "Imperial Units" steht nur bei eingeschaltetem Tracking da, wirkt seit
        // Formate aber auf Sport und Widget. Ist er verborgen, steht derselbe Schalter
        // an derselben Stelle - nie beide zugleich, und nie ein Wert ohne Weg zurueck.
        gesundheit?.let { g ->
            basicSettingsToggleItem(
                title = stringResource(R.string.ks_e_imperial),
                description = stringResource(R.string.ks_e_imperial_text),
                topLevelType = TopLevelType.Phone,
                section = Section.Health,
                checked = g.imperialUnits,
                keywords = "imperial metric units miles km feet pounds",
                show = { !g.trackingEnabled },
                onCheckChanged = { libPebble.updateHealthSettings(g.copy(imperialUnits = it)) },
            )
        },
        basicSettingsActionItem(
            title = stringResource(R.string.ks_n_einstellungen),
            description = stringResource(
                R.string.ks_e_kieselsport_text,
                maximum?.let { if (it.nachAlter) stringResource(R.string.ks_e_maxpuls_alter, it.wert) else Formate.zahl(it.wert.toLong()) }
                    ?: Formate.FEHLT,
                k.pause, k.becken,
            ),
            topLevelType = TopLevelType.Phone,
            section = Section.Sport,
            keywords = "kieselsport max heart rate pool rest pin",
            action = gehe(PebbleNavBarRoutes.KieselsportRoute),
        ),
        // Dieselben Strings wie die Zeile auf dem Sport-Reiter, nicht nur derselbe Wortlaut:
        // ein Recht, eine Bezeichnung - benennt Sport sie um, folgen die Einstellungen.
        basicSettingsActionItem(
            title = stringResource(R.string.ks_sp_routen),
            description = stringResource(
                when {
                    SpurDienst.darfImmerOrten(context) -> R.string.ks_e_standort_immer
                    SpurDienst.darfOrten(context) -> R.string.ks_sp_nur_nutzung
                    else -> R.string.ks_ei_standort_nicht
                }
            ),
            topLevelType = TopLevelType.Phone,
            section = Section.Sport,
            keywords = "location gps track routes",
            badge = if (mahnung.standort) "!" else null,
            action = oeffne(appSeite),
        ),
        basicSettingsActionItem(
            title = stringResource(R.string.ks_e_osmand),
            description = if (osmand == null) stringResource(R.string.ks_e_osmand_fehlt) else Kieselstrasse.lageText(context, strasse),
            topLevelType = TopLevelType.Phone,
            section = Section.Navigation,
            keywords = "osmand kieselstrasse navigation turn",
            badge = if (mahnung.osmand) "!" else null,
            action = when {
                osmand == null -> null
                lage == Kieselstrasse.Lage.FREISCHALTEN -> context.packageManager.getLaunchIntentForPackage(osmand)?.let { intent ->
                    { zuOsmand = true; oeffne(intent)() }
                }
                else -> {
                    { Kieselstrasse.pruefe(context); neu++ }
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
        basicSettingsActionItem(
            title = stringResource(R.string.ks_b_titel),
            description = when {
                Sichern.ordnerUri(context) == null -> stringResource(R.string.ks_e_sicherung_kein)
                zuletzt == 0L -> stringResource(R.string.ks_b_nie)
                else -> stringResource(R.string.ks_b_zuletzt, Formate.relativ(context, zuletzt))
            },
            topLevelType = TopLevelType.Phone,
            section = Section.Boulder,
            keywords = "backup restore kiesel-helper take over",
            action = gehe(PebbleNavBarRoutes.SicherungRoute),
        ),
        basicSettingsActionItem(
            title = stringResource(R.string.ks_n_protokoll),
            description = stringResource(R.string.ks_e_protokoll_text),
            topLevelType = TopLevelType.Phone,
            section = Section.Boulder,
            keywords = "log kieselsport drinktervall supcycle",
            action = gehe(PebbleNavBarRoutes.ProtokollRoute),
        ),
        // Voruebergehend, bis die Schlafregeln neben Fitbit passen (Nachtexport.kt) - nur mit
        // Gravels "Show debug options", es ist ein Werkzeug, keine Einstellung.
        basicSettingsActionItem(
            title = stringResource(R.string.ks_e_nachtexport),
            description = stringResource(R.string.ks_e_nachtexport_text),
            topLevelType = TopLevelType.Phone,
            section = Section.Debug,
            keywords = "export night sleep csv minutes",
            isDebugSetting = true,
            action = {
                scope.launch {
                    // Ohne Nacht in den letzten drei Tagen sagt der Tipp es, statt still nichts zu tun.
                    val datei = Nachtexport.csv(api)
                    if (datei == null) Toast.makeText(context, keineNacht, Toast.LENGTH_SHORT).show()
                    else Nachtexport.teile(context, datei.first, datei.second)
                }
            },
        ),
        // Voruebergehend wie der Nachtexport, bis die Velo-Erkennung an echten Fahrten steht (F33).
        basicSettingsActionItem(
            title = stringResource(R.string.ks_m_titel),
            description = stringResource(R.string.ks_m_text),
            topLevelType = TopLevelType.Phone,
            section = Section.Debug,
            keywords = "export minutes csv period training ride",
            isDebugSetting = true,
            action = gehe(PebbleNavBarRoutes.MinutenexportRoute),
        ),
    )
}

/**
 * Boulders "!" im Abzeichen der Leiste. Dieselbe Rechnung wie die Eintraege
 * oben, mit eigenem Auffrischen beim Zurueckkehren - die Leiste steht auch,
 * wenn die Einstellungen nie offen waren. OsmAnd wird hier nicht geweckt:
 * es zaehlt der Stand der letzten Pruefung.
 */
@Composable
actual fun boulderAbzeichen(): Int {
    val context = LocalContext.current
    var neu by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { neu++ }
    return mahnungen(context, neu).zahl
}

/** Was in Boulders Eintraegen ein "!" traegt; [fehlendeRechte] auch fuer die Zahl im Text. */
private class Mahnungen(val fehlendeRechte: Set<String>, val rechte: Boolean, val standort: Boolean, val osmand: Boolean) {
    val zahl: Int get() = listOf(rechte, standort, osmand).count { it }
}

/**
 * EINE Rechnung fuer Eintrag, Sektion und Leiste: zaehlte die Leiste anders
 * als die Liste, zeigte sie eine Zahl, zu der man kein "!" findet. Wer in
 * Gravels Rechteseite die Warnungen ausblendet, sieht auch Boulders
 * Rechte-"!" nicht - OsmAnd ist kein Recht und bleibt. Ein Android-Recht
 * traegt hoechstens EIN Abzeichen: was Gravel schon zaehlt, zaehlt Boulder nicht.
 */
@Composable
private fun mahnungen(context: Context, neu: Int): Mahnungen {
    val config by koinInject<CoreConfigHolder>().config.collectAsState()
    val leise = config.hidePermissionWarningBadges
    // Gravel fordert den Standort selbst, ab Android 13 auch "immer" (Background Location).
    // Fehlt er dort, steht er schon mit Zahl unter About - ein "!" an Routes zaehlte ihn doppelt.
    val gravelFehlt by koinInject<PermissionRequester>().missingPermissions.collectAsState()
    val gravelOrtet = Permission.Location in gravelFehlt || Permission.BackgroundLocation in gravelFehlt
    // Dieselbe Menge, die die Aktion fragt - Zahl und Abzeichen verschwinden mit dem Dialog.
    val fehlend by produceState(emptySet<String>(), neu) { value = TrainingsDaten.fehlendeRechte(context) }
    val immer = remember(neu) { SpurDienst.darfImmerOrten(context) }
    val strasse by Kieselstrasse.stand.collectAsState()
    return Mahnungen(
        fehlendeRechte = fehlend,
        rechte = fehlend.isNotEmpty() && !leise,
        standort = !immer && !leise && !gravelOrtet,
        osmand = strasse.lage == Kieselstrasse.Lage.FREISCHALTEN || strasse.lage == Kieselstrasse.Lage.ABO_FEHLER,
    )
}
