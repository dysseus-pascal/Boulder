package coredevices.pebble.ui

import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coredevices.pebble.R
import coredevices.pebble.kiesel.Formate
import coredevices.pebble.kiesel.ansicht.Farben
import coredevices.pebble.kiesel.ansicht.INNEN
import coredevices.pebble.kiesel.ansicht.Kasten
import coredevices.pebble.kiesel.ansicht.Kopf
import coredevices.pebble.kiesel.ansicht.Ladekreis
import coredevices.pebble.kiesel.ansicht.Schaltzeile
import coredevices.pebble.kiesel.ansicht.Seite
import coredevices.pebble.kiesel.ansicht.Zeichen
import coredevices.pebble.kiesel.ansicht.Zeile
import coredevices.pebble.kiesel.ansicht.bildBeschreibung
import coredevices.pebble.kiesel.ansicht.flaeche
import coredevices.pebble.kiesel.ansicht.ton
import coredevices.pebble.rememberLibPebble
import coredevices.pebble.timestyle.TS_SPRACHEN
import coredevices.pebble.timestyle.TS_VERSAETZE
import coredevices.pebble.timestyle.TS_WIDGETS
import coredevices.pebble.timestyle.TsBedrockEinstellungen
import coredevices.pebble.timestyle.TsBedrockNeusetzen
import coredevices.pebble.timestyle.TsWidget
import coredevices.pebble.timestyle.alsHex
import coredevices.pebble.timestyle.tsAusHex
import coredevices.pebble.timestyle.tsNameEingabe
import coredevices.pebble.timestyle.tsZonenText
import io.rebble.libpebblecommon.database.dao.WatchPreference
import io.rebble.libpebblecommon.database.entity.BytesWatchPref
import io.rebble.libpebblecommon.timeline.TimelineColor
import io.rebble.libpebblecommon.timeline.argbColor
import io.rebble.libpebblecommon.timeline.toPebbleColor
import io.rebble.libpebblecommon.util.toProtocolNumber
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlin.time.Clock

/** Der Wert aus der Datenbank; [hex] null = nie gesetzt. Eigener Typ, damit "noch nicht geladen" (null) davon getrennt ist. */
private data class Gelesen(val hex: String?)

/** Die Farben, wie die Leiste sie zeigt: nach r, g, b - 8 Reihen zu 8 (Pebbles Spektrumpalette). */
private val PALETTE: List<TimelineColor> = TimelineColor.entries.sortedBy { it.toPebbleColor().toProtocolNumber() }

private fun TimelineColor.gcolor(): Int = toPebbleColor().toProtocolNumber().toInt()

/** GColor8 (aarrggbb) als Farbe des Telefons - je Kanal 0, 85, 170, 255. */
private fun gfarbe(g: Int): Color = Color(red = (g shr 4 and 3) * 85, green = (g shr 2 and 3) * 85, blue = (g and 3) * 85)

/** Welche Farbe das Blatt gerade waehlt. */
private enum class Farbfeld { Zeit, Hintergrund, Leiste, Leistentext }

/** Warten nach der letzten Aenderung, bevor sie zur Uhr geht - ein Schieben durch die Palette ist EIN Schreiben. */
private const val ENTPRELLEN_MS = 500L

/**
 * Boulder: die Einstellungen von TimeStyle Bedrock. Oben fest die Vorschau,
 * darunter die Abschnitte. Jede Aenderung geht nach [ENTPRELLEN_MS] selbst
 * zur Uhr (kein Speichern-Knopf) - als EIN Wert "tsBedrock" ueber die
 * Uhreinstellungen-Synchronisation (siehe TsBedrockFormat).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
actual fun TimeStyleBedrockSettingsScreen(topBarParams: TopBarParams, nav: NavBarNav) {
    val libPebble = rememberLibPebble()
    val titel = stringResource(R.string.ts_titel)
    val gelesen by remember(libPebble) {
        libPebble.watchPrefs.map { Gelesen(TsBedrockNeusetzen.ausListe(it)) }
    }.collectAsState(null)
    val konfig by libPebble.config.collectAsState()
    // Der Stand der Seite, solange er noch nicht in der Datenbank steht. Danach zaehlt wieder die Datenbank.
    var entwurf by remember { mutableStateOf<TsBedrockEinstellungen?>(null) }
    val aktuellGelesen by rememberUpdatedState(gelesen)

    fun schreibe(e: TsBedrockEinstellungen) {
        val hex = e.alsHex()
        // Nichts Doppeltes: jedes Schreiben ist ein neuer Zeitstempel und damit ein Satz zur Uhr.
        if (hex == aktuellGelesen?.hex) return
        libPebble.setWatchPref(WatchPreference(BytesWatchPref.TimeStyleBedrock, hex))
    }

    LaunchedEffect(entwurf) {
        val e = entwurf ?: return@LaunchedEffect
        delay(ENTPRELLEN_MS)
        schreibe(e)
    }
    LaunchedEffect(gelesen) {
        if (entwurf != null && entwurf?.alsHex() == gelesen?.hex) entwurf = null
    }
    // Wer die Seite innerhalb der Wartezeit verlaesst, verliert die letzte Aenderung nicht.
    val letzterEntwurf by rememberUpdatedState(entwurf)
    DisposableEffect(Unit) { onDispose { letzterEntwurf?.let(::schreibe) } }

    LaunchedEffect(Unit) {
        topBarParams.title(titel)
        topBarParams.searchAvailable(null)
        topBarParams.actions {
            Ueberlauf {
                entwurf = TsBedrockEinstellungen.VORGABE
                schreibe(TsBedrockEinstellungen.VORGABE)
            }
        }
    }

    val stand = entwurf ?: gelesen?.let { tsAusHex(it.hex) }
    if (stand == null) {
        Ladekreis()
        return
    }
    val aendere: (TsBedrockEinstellungen) -> Unit = { entwurf = it }
    var blatt by remember { mutableStateOf<Farbfeld?>(null) }

    Column(Modifier.fillMaxSize()) {
        // Fest ueber der Liste: jede Aenderung weiter unten ist hier sofort zu sehen.
        Vorschau(stand, Modifier.align(Alignment.CenterHorizontally).padding(top = 6.dp, bottom = 10.dp).width(128.dp))
        Seite(Modifier.weight(1f)) {
            if (!konfig.watchConfig.enableWatchSettingsSync) Kasten {
                Schaltzeile(stringResource(R.string.ts_sync), an = false) { an ->
                    libPebble.updateConfig(konfig.copy(watchConfig = konfig.watchConfig.copy(enableWatchSettingsSync = an)))
                }
            }
            Uhrabschnitt(stand, aendere)
            Leistenabschnitt(stand, aendere)
            Widgetabschnitt(stand, aendere)
            Farbabschnitt(stand) { blatt = it }
            Kasten {
                Auswahlzeile(
                    stringResource(R.string.ts_k_sprache), stand.languageId, TS_SPRACHEN.map { it.first },
                    text = { id -> TS_SPRACHEN.first { it.first == id }.second },
                ) { aendere(stand.copy(languageId = it)) }
            }
        }
    }

    blatt?.let { feld ->
        val (name, aktuell) = when (feld) {
            Farbfeld.Zeit -> stringResource(R.string.ts_f_zeit) to stand.timeColor
            Farbfeld.Hintergrund -> stringResource(R.string.ts_f_hintergrund) to stand.timeBgColor
            Farbfeld.Leiste -> stringResource(R.string.ts_f_leiste) to stand.sidebarColor
            Farbfeld.Leistentext -> stringResource(R.string.ts_f_leistentext) to stand.sidebarTextColor
        }
        Farbblatt(name, aktuell, schliesse = { blatt = null }) { g ->
            aendere(
                when (feld) {
                    Farbfeld.Zeit -> stand.copy(timeColor = g)
                    Farbfeld.Hintergrund -> stand.copy(timeBgColor = g)
                    Farbfeld.Leiste -> stand.copy(sidebarColor = g)
                    Farbfeld.Leistentext -> stand.copy(sidebarTextColor = g)
                },
            )
            blatt = null
        }
    }
}

@Composable
private fun Uhrabschnitt(s: TsBedrockEinstellungen, aendere: (TsBedrockEinstellungen) -> Unit) = Kasten {
    Kopf(stringResource(R.string.ts_k_uhr))
    Schaltzeile(stringResource(R.string.ts_null_vorn), an = s.showLeadingZero) { aendere(s.copy(showLeadingZero = it)) }
    val stufen = listOf(stringResource(R.string.ts_vibe_aus), stringResource(R.string.ts_vibe_stunde), stringResource(R.string.ts_vibe_halb))
    Auswahlzeile(stringResource(R.string.ts_stundenvibe), s.hourlyVibe, listOf(0, 1, 2), text = { stufen[it] }) {
        aendere(s.copy(hourlyVibe = it))
    }
    Schaltzeile(stringResource(R.string.ts_bt_vibe), an = s.btVibe) { aendere(s.copy(btVibe = it)) }
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun Leistenabschnitt(s: TsBedrockEinstellungen, aendere: (TsBedrockEinstellungen) -> Unit) = Kasten {
    Kopf(stringResource(R.string.ts_k_leiste))
    Zweierwahl(stringResource(R.string.ts_lage), stringResource(R.string.ts_links), stringResource(R.string.ts_rechts), rechtsGewaehlt = !s.sidebarOnLeft) {
        aendere(s.copy(sidebarOnLeft = !it))
    }
    Zweierwahl(stringResource(R.string.ts_groesse), stringResource(R.string.ts_normal), stringResource(R.string.ts_gross), rechtsGewaehlt = s.useLargeFonts) {
        aendere(s.copy(useLargeFonts = it))
    }
    Schaltzeile(stringResource(R.string.ts_bt_symbol), an = s.activateDisconnectIcon) { aendere(s.copy(activateDisconnectIcon = it)) }
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun widgetName(id: Int): String = stringResource(
    when (id) {
        TsWidget.AKKU -> R.string.ts_w_akku
        TsWidget.ZEITZONE -> R.string.ts_w_zeitzone
        TsWidget.DATUM -> R.string.ts_w_datum
        TsWidget.SEKUNDEN -> R.string.ts_w_sekunden
        TsWidget.WOCHE -> R.string.ts_w_woche
        TsWidget.WETTER -> R.string.ts_w_wetter
        TsWidget.VORHERSAGE -> R.string.ts_w_vorhersage
        TsWidget.SCHLAF -> R.string.ts_w_schlaf
        TsWidget.SCHRITTE -> R.string.ts_w_schritte
        TsWidget.BEATS -> R.string.ts_w_beats
        TsWidget.PULS -> R.string.ts_w_puls
        TsWidget.UV -> R.string.ts_w_uv
        else -> R.string.ts_w_keins
    },
)

/** "UTC−5", "UTC±0", "UTC+14" - mit dem echten Minuszeichen wie ueberall in Boulder. */
private fun utc(versatz: Int): String = when {
    versatz < 0 -> "UTC${Formate.MINUS}${-versatz}"
    versatz == 0 -> "UTC±0"
    else -> "UTC+$versatz"
}

@Composable
private fun Widgetabschnitt(s: TsBedrockEinstellungen, aendere: (TsBedrockEinstellungen) -> Unit) = Kasten {
    Kopf(stringResource(R.string.ts_k_widgets))
    val namen = TS_WIDGETS.associateWith { widgetName(it) }
    listOf(R.string.ts_oben, R.string.ts_mitte, R.string.ts_unten).forEachIndexed { platz, titel ->
        Auswahlzeile(stringResource(titel), s.widgets[platz], TS_WIDGETS, text = { namen[it] ?: "" }) { w ->
            aendere(s.copy(widgets = s.widgets.toMutableList().also { it[platz] = w }))
        }
    }
    // Die Zeilen zu einem Widget erscheinen nur, wenn es gewaehlt ist; ihr Wert bleibt auch versteckt erhalten.
    if (s.zeigt(TsWidget.AKKU)) {
        Schaltzeile(stringResource(R.string.ts_akku_prozent), an = s.showBatteryPct) { aendere(s.copy(showBatteryPct = it)) }
    } else {
        // Der Schalter heisst "anzeigen", das Feld der Uhr "abschalten" - darum verneint.
        Schaltzeile(stringResource(R.string.ts_akku_warnung), an = !s.disableAutobattery) { aendere(s.copy(disableAutobattery = !it)) }
    }
    if (s.zeigt(TsWidget.ZEITZONE)) {
        Namenzeile(stringResource(R.string.ts_zz_name), s.altclockName) { aendere(s.copy(altclockName = it)) }
        Auswahlzeile(stringResource(R.string.ts_zz_versatz), s.altclockOffset, TS_VERSAETZE.toList(), text = ::utc) {
            aendere(s.copy(altclockOffset = it))
        }
    }
    if (s.zeigt(TsWidget.SCHRITTE)) {
        Zweierwahl(stringResource(R.string.ts_schritte_zeigen), stringResource(R.string.ts_schritte), stringResource(R.string.ts_distanz), rechtsGewaehlt = s.healthUseDistance) {
            aendere(s.copy(healthUseDistance = it))
        }
        Zweierwahl(stringResource(R.string.ts_dezimal), ".", ",", rechtsGewaehlt = s.decimalSeparator == ',') {
            aendere(s.copy(decimalSeparator = if (it) ',' else '.'))
        }
    }
    if (s.zeigt(TsWidget.SCHLAF)) {
        Zweierwahl(stringResource(R.string.ts_schlaf_zeigen), stringResource(R.string.ts_schlaf_gesamt), stringResource(R.string.ts_schlaf_ruhig), rechtsGewaehlt = s.healthUseRestfulSleep) {
            aendere(s.copy(healthUseRestfulSleep = it))
        }
    }
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun Farbabschnitt(s: TsBedrockEinstellungen, oeffne: (Farbfeld) -> Unit) = Kasten {
    Kopf(stringResource(R.string.ts_k_farben))
    listOf(
        Triple(R.string.ts_f_zeit, s.timeColor, Farbfeld.Zeit),
        Triple(R.string.ts_f_hintergrund, s.timeBgColor, Farbfeld.Hintergrund),
        Triple(R.string.ts_f_leiste, s.sidebarColor, Farbfeld.Leiste),
        Triple(R.string.ts_f_leistentext, s.sidebarTextColor, Farbfeld.Leistentext),
    ).forEach { (titel, farbe, feld) ->
        Zeile(
            stringResource(titel), minHoehe = 56.dp, chevron = false,
            rechts = { Farbpunkt(gfarbe(farbe), 28.dp) },
            onClick = { oeffne(feld) },
        )
    }
    Spacer(Modifier.height(4.dp))
}

/** Ein rundes Farbfeld mit feinem Rand - Schwarz und Weiss bleiben auf jeder Karte sichtbar. */
@Composable
private fun Farbpunkt(farbe: Color, groesse: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier.size(groesse).clip(CircleShape).background(farbe)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f), CircleShape),
    )
}

/**
 * Eine Zeile mit Wert rechts; ein Tipp oeffnet die Auswahl darunter am rechten
 * Rand. Ein Chevron nach unten statt nach rechts: die Zeile fuehrt nicht weiter.
 */
@Composable
private fun <T> Auswahlzeile(titel: String, wert: T, optionen: List<T>, text: (T) -> String, waehle: (T) -> Unit) {
    var offen by remember { mutableStateOf(false) }
    val grau = MaterialTheme.colorScheme.onSurfaceVariant
    Box(Modifier.fillMaxWidth()) {
        Zeile(
            titel, minHoehe = 56.dp, chevron = false,
            rechts = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(text(wert), fontSize = 15.sp, color = grau, maxLines = 1)
                    Icon(AUF, null, Modifier.size(18.dp), tint = grau)
                }
            },
            onClick = { offen = true },
        )
        Box(Modifier.align(Alignment.BottomEnd).padding(end = INNEN)) {
            DropdownMenu(expanded = offen, onDismissRequest = { offen = false }) {
                optionen.forEach { o ->
                    val haken: (@Composable () -> Unit)? = if (o == wert) {
                        { Icon(Zeichen.haken, null, Modifier.size(18.dp)) }
                    } else {
                        null
                    }
                    DropdownMenuItem(
                        text = { Text(text(o)) },
                        onClick = {
                            offen = false
                            waehle(o)
                        },
                        trailingIcon = haken,
                    )
                }
            }
        }
    }
}

/** Zwei Werte nebeneinander rechts in der Zeile. [rechtsGewaehlt] = der rechte ist gewaehlt. */
@Composable
private fun Zweierwahl(titel: String, links: String, rechts: String, rechtsGewaehlt: Boolean, waehle: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = INNEN, end = INNEN, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(titel, Modifier.weight(1f), fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)
        SingleChoiceSegmentedButtonRow {
            listOf(links, rechts).forEachIndexed { i, o ->
                SegmentedButton(
                    selected = (i == 1) == rechtsGewaehlt,
                    onClick = { waehle(i == 1) },
                    shape = SegmentedButtonDefaults.itemShape(i, 2),
                    // Gewaehlt im App-Gruen wie die Knoepfe des Baukastens, ohne Haken (die Zeile ist schmal).
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = Farben.aktion.flaeche().copy(alpha = 0.22f),
                        activeContentColor = Farben.aktion.ton(),
                    ),
                    icon = {},
                    // "." und "," waeren in Labelgroesse kaum zu treffen.
                ) { Text(o, maxLines = 1, fontSize = if (o.length == 1) 22.sp else TextUnit.Unspecified) }
            }
        }
    }
}

/** Der Name der Zeitzone: hoechstens 4 Zeichen, nur was die Uhr zeichnen kann - anderes nimmt das Feld nicht an. */
@Composable
private fun Namenzeile(titel: String, name: String, setze: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = INNEN, end = INNEN, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(titel, Modifier.weight(1f), fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)
        OutlinedTextField(
            value = name,
            onValueChange = { neu -> tsNameEingabe(neu)?.let(setze) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
            modifier = Modifier.width(104.dp),
        )
    }
}

/** Die Palette als Blatt: 8 x 8, die aktive Farbe mit Ring und Haken. Ein Tipp waehlt und schliesst. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Farbblatt(titel: String, aktuell: Int, schliesse: () -> Unit, waehle: (Int) -> Unit) {
    ModalBottomSheet(onDismissRequest = schliesse, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Kopf(titel)
            PALETTE.chunked(8).forEach { reihe ->
                Row(Modifier.fillMaxWidth().padding(horizontal = INNEN), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    reihe.forEach { farbe ->
                        val g = farbe.gcolor()
                        val gewaehlt = g == aktuell
                        val flaeche = Color(farbe.argbColor())
                        Box(
                            Modifier.weight(1f).aspectRatio(1f).clip(CircleShape).background(flaeche)
                                .border(
                                    if (gewaehlt) 3.dp else 1.dp,
                                    if (gewaehlt) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                                    CircleShape,
                                )
                                .clickable { waehle(g) }
                                .semantics {
                                    contentDescription = farbe.displayName
                                    selected = gewaehlt
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (gewaehlt) {
                                Icon(Zeichen.haken, null, Modifier.size(18.dp), tint = if (flaeche.luminance() > 0.4f) Color.Black else Color.White)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Das Menue oben rechts: nur "Reset" (zurueck auf das Foto-Zifferblatt). */
@Composable
private fun Ueberlauf(zuruecksetzen: () -> Unit) {
    var offen by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { offen = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.ts_mehr)) }
        DropdownMenu(expanded = offen, onDismissRequest = { offen = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.ts_reset)) },
                onClick = {
                    offen = false
                    zuruecksetzen()
                },
            )
        }
    }
}

// --- Vorschau ---------------------------------------------------------------------------

/** Der Chevron nach unten - der Baukasten hat nur die Richtungen zum Blaettern. */
private val AUF: ImageVector = ImageVector.Builder(
    defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
).apply {
    addPath(
        PathParser().parsePathString("M6 9l6 6 6-6").toNodes(), fill = null, stroke = SolidColor(Color.Black),
        strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
    )
}.build()

/** Beispielwerte je Widget, Zeile fuer Zeile. Die Vorschau zeigt den Aufbau, keine echten Daten. */
private fun beispiel(w: Int, s: TsBedrockEinstellungen, zonenStunde: String): List<String> = when (w) {
    TsWidget.WETTER -> listOf("18°")
    TsWidget.VORHERSAGE -> listOf("21/9")
    TsWidget.UV -> listOf("4")
    TsWidget.SCHRITTE -> listOf(if (s.healthUseDistance) "4${s.decimalSeparator}2km" else "5${s.decimalSeparator}5k")
    TsWidget.SCHLAF -> listOf(if (s.healthUseRestfulSleep) "2h" else "7h")
    TsWidget.PULS -> listOf("62")
    TsWidget.AKKU -> if (s.showBatteryPct) listOf("80%") else emptyList()
    TsWidget.ZEITZONE -> listOf(s.altclockName, zonenStunde)
    TsWidget.DATUM -> listOf("SAT", "3", "OCT")
    TsWidget.SEKUNDEN -> listOf(":42")
    TsWidget.WOCHE -> listOf("41")
    TsWidget.BEATS -> listOf("@512")
    else -> emptyList()
}

/**
 * Das Zifferblatt im Kleinen, 200 x 228 wie emery: Zeit 9:41 zweizeilig,
 * die Leiste (34 bzw. 43 Pixel) mit den drei Widgets. NICHT PIXELGENAU -
 * LECO gibt es auf dem Telefon nicht; es soll Farben, Lage und Inhalt zeigen.
 */
@Composable
private fun Vorschau(s: TsBedrockEinstellungen, modifier: Modifier) {
    val messer = rememberTextMeasurer()
    val context = LocalContext.current
    val vierundzwanzig = DateFormat.is24HourFormat(context)
    // Die zweite Zeitzone zur Beispielzeit 9:41 hier - auf die Minute wie die Uhr (halbe Stunden).
    val hier = remember { TimeZone.currentSystemDefault() }
    val heute = remember(hier) { Clock.System.todayIn(hier) }
    val zonenStunde = tsZonenText(heute, hier, s.altclockOffset, vierundzwanzig, s.showLeadingZero)
    val beschreibung = stringResource(R.string.ts_vorschau)
    // Mit feinem Rand: im hellen Schema verschwaende eine weisse Leiste sonst in der Seite.
    val rand = MaterialTheme.colorScheme.outlineVariant
    Canvas(
        modifier.aspectRatio(200f / 228f).clip(RoundedCornerShape(8.dp))
            .border(1.dp, rand, RoundedCornerShape(8.dp)).bildBeschreibung(beschreibung),
    ) {
        val px = size.width / 200f
        drawRect(gfarbe(s.timeBgColor))
        val leiste = (if (s.useLargeFonts) 43f else 34f) * px
        val leisteX = if (s.sidebarOnLeft) 0f else size.width - leiste
        drawRect(gfarbe(s.sidebarColor), Offset(leisteX, 0f), Size(leiste, size.height))

        // Die Zeit: zwei Zeilen, so gross, dass sie das Feld neben der Leiste fuellen.
        val uhrX = if (s.sidebarOnLeft) leiste else 0f
        val uhrBreite = size.width - leiste
        // Pixel in sp ohne die Schriftgroesse des Telefons: die Vorschau ist ein Bild, kein Text.
        // toSp() statt "/ fontScale": ab Android 14 waechst grosse Schrift NICHT linear, die
        // Division machte die Ziffern bei grosser Systemschrift zu klein. toSp() nimmt dieselbe
        // Umrechnung, mit der der TextMeasurer zurueckrechnet - die Pixel stimmen also genau.
        val zeitStil = TextStyle(fontSize = (size.height * 0.44f).toSp(), fontWeight = FontWeight.Black, color = gfarbe(s.timeColor))
        listOf(if (s.showLeadingZero) "09" else "9", "41").forEachIndexed { i, zeile ->
            val m = messer.measure(AnnotatedString(zeile), zeitStil)
            val mitteY = size.height * (if (i == 0) 0.27f else 0.73f)
            drawText(m, topLeft = Offset(uhrX + (uhrBreite - m.size.width) / 2f, mitteY - m.size.height / 2f))
        }

        // Die drei Widgets: oben am Rand, in der Mitte, unten am Rand - wie auf der Uhr.
        val schrift = (if (s.useLargeFonts) 21f else 17f) * px
        val textFarbe = gfarbe(s.sidebarTextColor)
        val randAbstand = 5f * px
        repeat(3) { platz ->
            val w = s.widgets[platz]
            val zeilen = beispiel(w, s, zonenStunde)
            val hoehe = schrift * 1.15f
            val akkuSymbol = w == TsWidget.AKKU
            val gesamt = zeilen.size * hoehe + (if (akkuSymbol) hoehe else 0f)
            var y = when (platz) {
                0 -> randAbstand
                1 -> (size.height - gesamt) / 2f
                else -> size.height - randAbstand - gesamt
            }
            if (akkuSymbol) {
                akku(leisteX + leiste / 2f, y + hoehe / 2f, leiste * 0.6f, hoehe * 0.55f, textFarbe, px)
                y += hoehe
            }
            zeilen.forEachIndexed { i, text ->
                // Das Datum: die Zahl im Kalenderblatt wie auf der Uhr.
                val blatt = w == TsWidget.DATUM && i == 1
                widgetText(messer, text, leisteX, leiste, y, hoehe, schrift, textFarbe, blatt, px)
                y += hoehe
            }
        }
    }
}

/** Eine Zeile Leistentext, mittig; zu breit (grosse Schrift des Telefons, "4.2km") wird sie kleiner. */
private fun DrawScope.widgetText(
    messer: TextMeasurer, text: String, leisteX: Float, leiste: Float, y: Float, hoehe: Float,
    schrift: Float, farbe: Color, blatt: Boolean, px: Float,
) {
    if (text.isEmpty()) return
    // Pixel -> sp ueber toSp(), wie in der Vorschau oben (nichtlineare Schriftskalierung).
    var stil = TextStyle(fontSize = schrift.toSp(), fontWeight = FontWeight.Bold, color = farbe)
    var m = messer.measure(AnnotatedString(text), stil)
    val platz = leiste * 0.9f
    if (m.size.width > platz) {
        // In Pixeln verkleinern: die Breite waechst mit den Pixeln linear, nicht mit sp.
        stil = stil.copy(fontSize = (schrift * platz / m.size.width).toSp())
        m = messer.measure(AnnotatedString(text), stil)
    }
    val x = leisteX + (leiste - m.size.width) / 2f
    val oben = y + (hoehe - m.size.height) / 2f
    drawText(m, topLeft = Offset(x, oben))
    if (blatt) {
        val rand = 3f * px
        drawRect(farbe, Offset(leisteX + leiste * 0.2f, y + rand / 2f), Size(leiste * 0.6f, hoehe - rand), style = Stroke(width = 1.5f * px))
    }
}

/** Das Akkusymbol: Umriss mit Pol und halber Fuellung. */
private fun DrawScope.akku(mitteX: Float, mitteY: Float, breite: Float, hoehe: Float, farbe: Color, px: Float) {
    val links = mitteX - breite / 2f
    val oben = mitteY - hoehe / 2f
    drawRect(farbe, Offset(links, oben), Size(breite * 0.9f, hoehe), style = Stroke(width = 1.5f * px))
    drawRect(farbe, Offset(links + breite * 0.9f, mitteY - hoehe * 0.2f), Size(breite * 0.1f, hoehe * 0.4f))
    drawRect(farbe, Offset(links + 2.5f * px, oben + 2.5f * px), Size((breite * 0.9f - 5f * px) * 0.8f, hoehe - 5f * px))
}
