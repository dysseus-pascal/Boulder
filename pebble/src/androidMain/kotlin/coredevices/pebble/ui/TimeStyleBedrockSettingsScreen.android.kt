package coredevices.pebble.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.russhwolf.settings.Settings
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
import coredevices.pebble.kiesel.ansicht.flaeche
import coredevices.pebble.kiesel.ansicht.ton
import coredevices.pebble.rememberLibPebble
import coredevices.pebble.timestyle.TS_BEDROCK_SCHRIFTEN_AB_BUILD
import coredevices.pebble.timestyle.TS_FARBVORLAGEN
import coredevices.pebble.timestyle.TS_SPRACHEN
import coredevices.pebble.timestyle.TS_SPRACHEN_BIS_B23
import coredevices.pebble.timestyle.TS_VERSAETZE
import coredevices.pebble.timestyle.TS_WIDGETS
import coredevices.pebble.timestyle.TsBedrockEinstellungen
import coredevices.pebble.timestyle.TsBedrockNeusetzen
import coredevices.pebble.timestyle.TsFarbvorlage
import coredevices.pebble.timestyle.TsWidget
import coredevices.pebble.timestyle.alsHex
import coredevices.pebble.timestyle.alsVorlage
import coredevices.pebble.timestyle.bild.TsSchriftwahl
import coredevices.pebble.timestyle.gleicheFarben
import coredevices.pebble.timestyle.mitEigener
import coredevices.pebble.timestyle.mitVorlage
import coredevices.pebble.timestyle.ohneStelle
import coredevices.pebble.timestyle.tsAusHex
import coredevices.pebble.timestyle.tsBedrockFaehigkeit
import coredevices.pebble.timestyle.TsSprachpaketMerker
import coredevices.pebble.timestyle.tsSprachpaketHinweis
import coredevices.pebble.timestyle.tsEigeneAlsText
import coredevices.pebble.timestyle.tsEigeneAusText
import coredevices.pebble.timestyle.tsNameEingabe
import io.rebble.libpebblecommon.connection.ConnectedPebbleDevice
import io.rebble.libpebblecommon.database.dao.WatchPreference
import io.rebble.libpebblecommon.database.entity.BytesWatchPref
import io.rebble.libpebblecommon.timeline.TimelineColor
import io.rebble.libpebblecommon.timeline.argbColor
import io.rebble.libpebblecommon.timeline.toPebbleColor
import io.rebble.libpebblecommon.util.toProtocolNumber
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import org.koin.compose.koinInject

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

/** Die eigenen Farbvorlagen in den Einstellungen des Telefons (tsEigeneAlsText). */
private const val EIGENE_VORLAGEN = "ts_bedrock_eigene_vorlagen"

/** Die Schriften in der Reihenfolge des Originals (Default, LECO, Bold, Bold Hour, Bold Minute). */
private val SCHRIFTEN = with(TsSchriftwahl) { listOf(SANS, LECO, FETT, FETTE_STUNDE, FETTE_MINUTE) }

/** Die Intervall-Vibration in der Reihenfolge des Originals: aus, alle 30 Minuten, jede Stunde. */
private val VIBRATIONEN = listOf(0, 2, 1)

/**
 * Boulder: die Einstellungen von TimeStyle Bedrock. Oben fest die Vorschau -
 * dasselbe Bild, das die Uhr zeichnet (timestyle/bild) -, darunter die
 * Abschnitte. Jede Aenderung geht nach [ENTPRELLEN_MS] selbst zur Uhr (kein
 * Speichern-Knopf) - als EIN Wert "tsBedrock" ueber die
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
    val umgebung = rememberTsUmgebung()
    val faehig = tsBedrockFaehigkeit(umgebung.build)
    val telefon: Settings = koinInject()
    // Das Sprachpaket der Uhr: verbunden das gemeldete, getrennt das zuletzt gemerkte (TsSprachpaketMerker).
    val uhr = lastConnectedWatch()
    val verbundeneUhr = uhr as? ConnectedPebbleDevice
    val paketWissen = remember(telefon, uhr) {
        TsSprachpaketMerker(telefon).wissen(uhr?.serial, verbundeneUhr != null, verbundeneUhr?.installedLanguagePack?.isoLocal)
    }
    var eigene by remember { mutableStateOf(tsEigeneAusText(telefon.getStringOrNull(EIGENE_VORLAGEN))) }
    fun eigeneSetzen(neu: List<TsFarbvorlage>) {
        eigene = neu
        telefon.putString(EIGENE_VORLAGEN, tsEigeneAlsText(neu))
    }

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
    var vorlagenOffen by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        // Fest ueber der Liste: jede Aenderung weiter unten ist hier sofort zu sehen.
        TsZifferblattBild(
            stand, umgebung, stringResource(R.string.ts_vorschau),
            Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 10.dp),
            wunschHoehe = 160.dp,
        )
        Seite(Modifier.weight(1f)) {
            if (!konfig.watchConfig.enableWatchSettingsSync) Kasten {
                Schaltzeile(stringResource(R.string.ts_sync), an = false) { an ->
                    libPebble.updateConfig(konfig.copy(watchConfig = konfig.watchConfig.copy(enableWatchSettingsSync = an)))
                }
            }
            Uhrabschnitt(stand, faehig.schriften, aendere)
            Leistenabschnitt(stand, aendere)
            Widgetabschnitt(stand, aendere)
            Farbabschnitt(stand, oeffne = { blatt = it }, vorlagen = { vorlagenOffen = true })
            Kasten {
                // Bis b23 nimmt die Uhr nur die 29 Sprachen ohne Sprachpaket an.
                val sprachen = if (faehig.schriften) TS_SPRACHEN else TS_SPRACHEN.filter { it.first in TS_SPRACHEN_BIS_B23 }
                val paket = stringResource(R.string.ts_sprachpaket)
                Auswahlzeile(
                    stringResource(R.string.ts_k_sprache), stand.languageId, sprachen.map { it.first },
                    text = { id -> TS_SPRACHEN.first { it.first == id }.second },
                    unter = paket.takeIf { tsSprachpaketHinweis(stand.languageId, paketWissen) },
                    zusatz = { id -> paket.takeIf { tsSprachpaketHinweis(id, paketWissen) } },
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
    if (vorlagenOffen) {
        Vorlagenblatt(
            stand = stand,
            eigene = eigene,
            schliesse = { vorlagenOffen = false },
            waehle = { aendere(stand.mitVorlage(it)) },
            speichere = { eigeneSetzen(eigene.mitEigener(stand.alsVorlage())) },
            loesche = { eigeneSetzen(eigene.ohneStelle(it)) },
        )
    }
}

@Composable
private fun schriftName(id: Int): String = stringResource(
    when (id) {
        TsSchriftwahl.SANS -> R.string.ts_s_regular
        TsSchriftwahl.FETT -> R.string.ts_s_bold
        TsSchriftwahl.FETTE_STUNDE -> R.string.ts_s_bold_h
        TsSchriftwahl.FETTE_MINUTE -> R.string.ts_s_bold_m
        else -> R.string.ts_s_leco
    },
)

@Composable
private fun Uhrabschnitt(s: TsBedrockEinstellungen, schriften: Boolean, aendere: (TsBedrockEinstellungen) -> Unit) = Kasten {
    Kopf(stringResource(R.string.ts_k_uhr))
    val namen = SCHRIFTEN.associateWith { schriftName(it) }
    // Bis b23 zeichnet die Uhr immer LECO: dann steht hier, was sie zeigt, und die Wahl ist gesperrt.
    Auswahlzeile(
        stringResource(R.string.ts_schrift), if (schriften) s.clockFontId else TsSchriftwahl.LECO, SCHRIFTEN,
        text = { namen[it] ?: "" },
        unter = if (schriften) null else stringResource(R.string.ts_ab_build, TS_BEDROCK_SCHRIFTEN_AB_BUILD),
        aktiv = schriften,
    ) { aendere(s.copy(clockFontId = it)) }
    Schaltzeile(stringResource(R.string.ts_null_vorn), an = s.showLeadingZero) { aendere(s.copy(showLeadingZero = it)) }
    val stufen = mapOf(
        0 to stringResource(R.string.ts_vibe_aus),
        1 to stringResource(R.string.ts_vibe_stunde),
        2 to stringResource(R.string.ts_vibe_halb),
    )
    Auswahlzeile(stringResource(R.string.ts_stundenvibe), s.hourlyVibe, VIBRATIONEN, text = { stufen[it] ?: "" }) {
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
        // Wie das Original: ein Widget gibt es nur einmal, sein alter Platz wird leer.
        Auswahlzeile(stringResource(titel), s.widgets[platz], TS_WIDGETS, text = { namen[it] ?: "" }) { w ->
            aendere(s.mitWidget(platz, w))
        }
    }
    // Wie das Original: Akku in Prozent immer (er gilt auch fuer die Akku-Warnung),
    // die Warnung nur ohne Akku-Widget, die Zeilen der uebrigen Widgets nur mit ihnen.
    Schaltzeile(stringResource(R.string.ts_akku_prozent), an = s.showBatteryPct) { aendere(s.copy(showBatteryPct = it)) }
    if (!s.zeigt(TsWidget.AKKU)) {
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
    }
    if (s.zeigt(TsWidget.SCHLAF)) {
        Zweierwahl(stringResource(R.string.ts_schlaf_zeigen), stringResource(R.string.ts_schlaf_gesamt), stringResource(R.string.ts_schlaf_ruhig), rechtsGewaehlt = s.healthUseRestfulSleep) {
            aendere(s.copy(healthUseRestfulSleep = it))
        }
    }
    Zweierwahl(stringResource(R.string.ts_dezimal), ".", ",", rechtsGewaehlt = s.decimalSeparator == ',') {
        aendere(s.copy(decimalSeparator = if (it) ',' else '.'))
    }
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun Farbabschnitt(s: TsBedrockEinstellungen, oeffne: (Farbfeld) -> Unit, vorlagen: () -> Unit) = Kasten {
    Kopf(stringResource(R.string.ts_k_farben))
    Zeile(
        stringResource(R.string.ts_vorlagen), minHoehe = 56.dp, chevron = true,
        rechts = { Vorlagenkachel(s.alsVorlage(), 40.dp) },
        onClick = vorlagen,
    )
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
 * Eine Farbvorlage im Kleinen wie im Original (Ziffern in der Zeitfarbe auf
 * dem Hintergrund, rechts die Leiste mit einem Punkt in der Schriftfarbe).
 * Ein Farbmuster, kein Bild der Uhr - das zeigt die Vorschau oben.
 */
@Composable
private fun Vorlagenkachel(v: TsFarbvorlage, breite: Dp, modifier: Modifier = Modifier) {
    val form = RoundedCornerShape(4.dp)
    Row(
        modifier.width(breite).aspectRatio(200f / 228f).clip(form).background(gfarbe(v.hintergrund))
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), form),
    ) {
        Box(Modifier.weight(0.75f).fillMaxHeight(), contentAlignment = Alignment.Center) {
            Text(
                "6\n32", color = gfarbe(v.zeit), fontWeight = FontWeight.Black,
                fontSize = (breite.value * 0.26f).sp, lineHeight = (breite.value * 0.28f).sp,
            )
        }
        Box(Modifier.weight(0.25f).fillMaxHeight().background(gfarbe(v.leiste)), contentAlignment = Alignment.Center) {
            Box(Modifier.size(breite * 0.08f).clip(CircleShape).background(gfarbe(v.leistentext)))
        }
    }
}

/**
 * Eine Zeile mit Wert rechts; ein Tipp oeffnet die Auswahl darunter am rechten
 * Rand. Ein Chevron nach unten statt nach rechts: die Zeile fuehrt nicht weiter.
 * [unter] ein kurzes Label unter dem Titel, [zusatz] je Option ein Label rechts
 * in der Auswahl, [aktiv] false sperrt die Auswahl.
 */
@Composable
private fun <T> Auswahlzeile(
    titel: String,
    wert: T,
    optionen: List<T>,
    text: (T) -> String,
    unter: String? = null,
    zusatz: (T) -> String? = { null },
    aktiv: Boolean = true,
    waehle: (T) -> Unit,
) {
    var offen by remember { mutableStateOf(false) }
    val grau = MaterialTheme.colorScheme.onSurfaceVariant
    Box(Modifier.fillMaxWidth()) {
        Zeile(
            titel, unter = unter, minHoehe = 56.dp, chevron = false,
            rechts = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(text(wert), fontSize = 15.sp, color = grau, maxLines = 1)
                    if (aktiv) Icon(AUF, null, Modifier.size(18.dp), tint = grau)
                }
            },
            onClick = if (aktiv) ({ offen = true }) else null,
        )
        Box(Modifier.align(Alignment.BottomEnd).padding(end = INNEN)) {
            DropdownMenu(expanded = offen, onDismissRequest = { offen = false }) {
                optionen.forEach { o ->
                    val haken: (@Composable () -> Unit)? = if (o == wert) {
                        { Icon(Zeichen.haken, null, Modifier.size(18.dp)) }
                    } else {
                        null
                    }
                    val label = zusatz(o)
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(text(o))
                                if (label != null) Text(label, fontSize = 12.sp, color = grau)
                            }
                        },
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

/**
 * Die Farbvorlagen als Blatt: oben die eigenen (speichern, loeschen), darunter
 * die 77 des Originals in seinen Gruppen. Ein Tipp uebernimmt die vier Farben;
 * das Blatt bleibt offen, damit man vergleichen kann (die Vorschau steht darueber).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun Vorlagenblatt(
    stand: TsBedrockEinstellungen,
    eigene: List<TsFarbvorlage>,
    schliesse: () -> Unit,
    waehle: (TsFarbvorlage) -> Unit,
    speichere: () -> Unit,
    loesche: (Int) -> Unit,
) {
    val aktuell = stand.alsVorlage()
    ModalBottomSheet(onDismissRequest = schliesse, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)) {
        Column(Modifier.navigationBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            Kopf(stringResource(R.string.ts_eigene))
            FlowRow(Modifier.padding(horizontal = INNEN), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                eigene.forEachIndexed { i, v ->
                    Box {
                        Vorlagenwahl(v, null, v.gleicheFarben(aktuell)) { waehle(v) }
                        val loeschen = stringResource(R.string.ts_loeschen)
                        IconButton(onClick = { loesche(i) }, modifier = Modifier.align(Alignment.TopEnd).size(28.dp)) {
                            Icon(Icons.Default.Close, loeschen, Modifier.size(16.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface))
                        }
                    }
                }
            }
            if (eigene.none { it.gleicheFarben(aktuell) }) {
                TextButton(onClick = speichere, modifier = Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.ts_speichern)) }
            }
            TS_FARBVORLAGEN.groupBy { it.gruppe }.forEach { (gruppe, liste) ->
                Kopf(gruppe)
                FlowRow(Modifier.padding(horizontal = INNEN), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    liste.forEach { v -> Vorlagenwahl(v, v.name, v.gleicheFarben(aktuell)) { waehle(v) } }
                }
            }
        }
    }
}

/** Eine waehlbare Vorlage mit Namen darunter; die gerade aktive mit Ring. */
@Composable
private fun Vorlagenwahl(v: TsFarbvorlage, name: String?, gewaehlt: Boolean, waehle: () -> Unit) {
    Column(
        Modifier.width(72.dp).clip(RoundedCornerShape(6.dp)).clickable(onClick = waehle)
            .semantics {
                contentDescription = name ?: "Saved"
                selected = gewaehlt
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Vorlagenkachel(
            v, 64.dp,
            Modifier.padding(top = 4.dp).then(
                if (gewaehlt) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, RoundedCornerShape(4.dp)) else Modifier,
            ),
        )
        if (name != null) {
            Text(name, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp, bottom = 2.dp))
        }
    }
}

/**
 * Das Menue oben rechts: nur "Reset to defaults" - ALLE Einstellungen zurueck auf
 * die Vorgabe der Firmware (TsBedrockEinstellungen.VORGABE = settings.c: LECO, weiss
 * auf schwarz, Wetter/Schritte/Datum, Englisch). Nicht "Aenderungen verwerfen";
 * die eigenen Farbvorlagen bleiben.
 */
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

/** Der Chevron nach unten - der Baukasten hat nur die Richtungen zum Blaettern. */
private val AUF: ImageVector = ImageVector.Builder(
    defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
).apply {
    addPath(
        PathParser().parsePathString("M6 9l6 6 6-6").toNodes(), fill = null, stroke = SolidColor(Color.Black),
        strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
    )
}.build()
