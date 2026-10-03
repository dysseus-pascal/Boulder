package coredevices.pebble.kiesel.ansicht

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import coredevices.pebble.kiesel.Formate
import java.util.Locale

/*
 * DER SEITENBAUKASTEN - die eine Art, eine Boulder-Seite zu bauen. Vorher gab
 * es zwei: Health, Herz, Nacht, Body und Sport setzten den Kopf in die Karte,
 * Trends, Trainingsdetail, Akku und Sicherung eine Ueberschrift darueber und
 * die Einheit gleich gross wie die Zahl. Dasselbe Training stand so einmal
 * als "128 bpm", einmal als "128 Avg HR".
 *
 * Die Regeln:
 * - Eine Seite ist eine [Seite] (oder eine LazyColumn mit [SEITENRAND] und
 *   [KARTENABSTAND]) aus Kaesten. Jeder Abschnitt ist ein [Kasten] mit
 *   [Kopf] darin - meist als [Abschnitt]. Kein Titel ueber der Karte.
 * - Zahlen fett, die Einheit klein und grau daneben ([zahlMitEinheit]):
 *   eine Hauptzahl als [Hauptwert], mehrere als [Kennzahl] in [Kennzahlen].
 * - Zeilen ([Zeile]) mit Chevron fuehren eine Ebene tiefer, ohne Ziel kein
 *   Chevron. Eine Tat auf der Zeile ("Back up now", "Allow") ist eine
 *   [Knopfzeile], ein Schalter eine [Schaltzeile].
 * - Text: ein Satz unter einem Wert ist eine [Unterzeile] (grau), ein
 *   Absatz oder eine Notiz [Fliesstext], das Ergebnis einer Tat eine
 *   [Meldung]. Die Seite eines einzelnen Dings beginnt mit [Detailkopf].
 * - Leerzustaende sind eine [Leerzeile] - ein kurzer Satz, keine Erklaerung.
 * - Abstaende: 14 dp innen, 10 dp zwischen Karten, Zeilen 64 dp (in
 *   Blaettern 48-56), Trenner bis zum Text eingerueckt.
 * - Farben aus Farben.kt, Zahlen und Zeiten aus kiesel/Formate.kt.
 *   Prozent steht ohne Abstand ("97%"): [zahlMitEinheit] setzt vor "%"
 *   keinen, die Angabe kommt aus Formate.prozentTeile.
 *
 * SYMBOLE: es gilt EINE Familie, Boulders eigene Strichzeichen [Zeichen] -
 * 24er Raster, 2er Strich, runde Enden, ungefuellt. Fehlt eines, kommt es
 * als Pfad dazu. Material-Symbole nur in der Outlined-Fassung und nur, bis
 * es den Pfad gibt (heute die Sportarten). Nie Textglyphen (‹ › ✓ ×) als
 * Symbol: sie folgen der Schrift, nicht dem Strich.
 *
 * GROSSE SCHRIFT UND TALKBACK: Bilder messen ihren Fuss aus der Schrift
 * ([fussraum], [hoeheMitSchrift]), jedes Canvas sagt in einem Satz, was es
 * zeigt ([bildBeschreibung]), Zeilen mit langem Druck sind mindestens 48 dp
 * hoch und nennen ihre Tat ([langDruck]). Feste Hoehen nur ohne Text -
 * sonst heightIn.
 */

/** Der Rand links und rechts jeder Seite. */
internal val SEITENRAND = 12.dp

/** Der Abstand zwischen zwei Karten. */
internal val KARTENABSTAND = 10.dp

/** Der Innenabstand einer Karte zu ihrem Inhalt. */
internal val INNEN = 14.dp

// --- Seite --------------------------------------------------------------------------

/** Eine scrollende Seite aus Kaesten, mit Rand und Abstaenden des Baukastens. */
@Composable
internal fun Seite(modifier: Modifier = Modifier, inhalt: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = SEITENRAND),
        verticalArrangement = Arrangement.spacedBy(KARTENABSTAND),
    ) {
        Spacer(Modifier.height(2.dp))
        inhalt()
        Spacer(Modifier.height(12.dp))
    }
}

/** Solange eine Seite laedt: der Kreis in der Mitte. */
@Composable
internal fun Ladekreis() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

/** Eine Seite, die nichts zu zeigen hat ("No night recorded") - ein Satz in der Mitte. */
@Composable
internal fun Leerseite(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 15.sp, lineHeight = 21.sp, color = Farben.neutral(), textAlign = TextAlign.Center)
    }
}

// --- Karte ----------------------------------------------------------------------------

/** Die Karte: 12 dp Rundung, dunkler als die Seite, feiner Rand. Mit [onClick] ist sie als Ganzes ein Ziel. */
@Composable
internal fun Kasten(onClick: (() -> Unit)? = null, inhalt: @Composable ColumnScope.() -> Unit) {
    val form = RoundedCornerShape(12.dp)
    // Die klickbare Variante: eine gewoehnliche Card nimmt Beruehrungen selbst an.
    if (onClick != null) Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = form, colors = kartenFarben(), border = kartenRand()) { inhalt() }
    else Card(Modifier.fillMaxWidth(), shape = form, colors = kartenFarben(), border = kartenRand()) { inhalt() }
}

/**
 * Der Kopf einer Karte: Name in Versalien, grauer Zusatz, rechts ein Link
 * mit Chevron. DEN TRENNER SETZT DER KOPF: der [zusatz] kommt ohne "·" -
 * stand er mal im String und mal im Code, kamen "· ·" oder gar keiner heraus.
 * Ein mitgegebenes "·" am Anfang wird darum uebergangen. Ein langer Kopf
 * ("TYPICAL WEEK · CAFFEINE · LAST 90 DAYS") BRICHT UM, statt abzuschneiden -
 * mit grosser Schrift fehlte sonst der Zeitraum.
 */
@Composable
internal fun Kopf(titel: String, zusatz: String? = null, link: String? = null, onLink: (() -> Unit)? = null) {
    val farben = MaterialTheme.colorScheme
    val rest = zusatz?.trimStart('·', ' ', ' ')?.takeIf { it.isNotEmpty() }
    // Mindesthoehe statt fester: bei grosser Schrift waechst der Kopf, statt zu beschneiden.
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(start = INNEN, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.Medium, letterSpacing = 0.08.em, color = farben.onSurface)) {
                    append(titel.uppercase(Locale.getDefault()))
                }
                if (rest != null) withStyle(SpanStyle(color = farben.onSurfaceVariant)) { append(" · $rest") }
            },
            Modifier.weight(1f).padding(vertical = 6.dp), fontSize = 12.sp, lineHeight = 16.sp,
        )
        if (link != null && onLink != null) Weiterlink(link, onLink)
    }
}

/** Ein gruener Link mit Chevron, wie rechts im [Kopf] - 48 dp hoch, damit er ein sicheres Ziel ist. */
@Composable
internal fun Weiterlink(text: String, onClick: () -> Unit) {
    val farbe = MaterialTheme.colorScheme.primary
    Row(
        Modifier.heightIn(min = 48.dp).clickable(onClick = onClick).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = farbe, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Icon(Zeichen.weiter, null, Modifier.size(18.dp), tint = farbe)
    }
}

/**
 * Ein Abschnitt der Seite: Karte, Kopf und der Inhalt eingerueckt darunter
 * (14 dp, 10 dp Abstand). Der Ersatz fuer Ueberschrift plus Karte.
 */
@Composable
internal fun Abschnitt(
    titel: String,
    zusatz: String? = null,
    link: String? = null,
    onLink: (() -> Unit)? = null,
    inhalt: @Composable ColumnScope.() -> Unit,
) {
    Kasten {
        Kopf(titel, zusatz, link, onLink)
        Column(
            Modifier.fillMaxWidth().padding(start = INNEN, end = INNEN, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp), content = inhalt,
        )
    }
}

/** Die Linie zwischen zwei Zeilen; eingerueckt bis zum Text, wenn links ein Zeichen steht. */
@Composable
internal fun Trenner(einzug: Dp = 64.dp) {
    Box(Modifier.padding(start = einzug).fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)))
}

// --- Werte ----------------------------------------------------------------------------

/**
 * Die Zahl fett, die Einheit klein und grau daneben - so steht jede Zahl mit
 * Einheit in Boulder. Vor "%" kein Abstand ("97%", Formate.abstandVor).
 */
@Composable
internal fun zahlMitEinheit(zahl: String, einheit: String?, groesse: Int = 20, einheitGroesse: Int = 13): AnnotatedString {
    val grau = MaterialTheme.colorScheme.onSurfaceVariant
    return buildAnnotatedString {
        withStyle(SpanStyle(fontSize = groesse.sp, fontWeight = FontWeight.SemiBold)) { append(zahl) }
        if (einheit != null) {
            withStyle(SpanStyle(fontSize = einheitGroesse.sp, fontWeight = FontWeight.Medium, color = grau)) { append(Formate.abstandVor(einheit) + einheit) }
        }
    }
}

/** Dasselbe aus einer [Formate.Angabe] - fuer Distanz, Tempo, Gewicht, Prozent. */
@Composable
internal fun zahlMitEinheit(angabe: Formate.Angabe, groesse: Int = 20, einheitGroesse: Int = 13): AnnotatedString =
    zahlMitEinheit(angabe.zahl, angabe.einheit, groesse, einheitGroesse)

/** Die Hauptzahl einer Karte gross, darunter ein grauer Satz mit Bezug ("Sep 28 · −0.6 kg in 30 days"). */
@Composable
internal fun Hauptwert(
    zahl: String,
    einheit: String?,
    unter: String? = null,
    unterFarbe: Color? = null,
    groesse: Int = 30,
    einheitGroesse: Int = 16,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(zahlMitEinheit(zahl, einheit, groesse, einheitGroesse), lineHeight = (groesse + 6).sp)
        if (unter != null) Unterzeile(unter, unterFarbe)
    }
}

/** Eine Zahl mit Einheit und ihrem Namen klein darunter - die Werte eines Trainings, des Akkus, einer Woche. */
@Composable
internal fun Kennzahl(zahl: String, einheit: String?, name: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(zahlMitEinheit(zahl, einheit), lineHeight = 26.sp, maxLines = 1, softWrap = false)
        Text(name, fontSize = 12.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

/** Mehrere [Kennzahl]en nebeneinander, umbrechend, wenn die Breite nicht reicht (grosse Schrift). */
@Composable
internal fun Kennzahlen(inhalt: @Composable FlowRowScope.() -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(26.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = inhalt)
}

/** Ein grauer Satz unter einem Wert: Bezug, Urteil, Zeitpunkt. Mit [farbe] fuer ein Urteil (Farben.warnung). */
@Composable
internal fun Unterzeile(text: String, farbe: Color? = null) {
    Text(text, fontSize = 13.sp, lineHeight = 19.sp, color = farbe ?: MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * Ein Satz oder eine Notiz in der Schrift der Seite (14 sp, onSurface) -
 * fuer eigenen Text, der kein Bezug eines Werts ist (Trainingsnotiz,
 * "Steps rise on workdays"). Kurz halten: keine Textwaende.
 */
@Composable
internal fun Fliesstext(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, fontSize = 14.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurface)
}

/**
 * Das Ergebnis einer Tat in einem Satz, fett: "Backup saved", "Restore
 * failed". Mit [farbe] fuer ein Urteil (Farben.schlecht.ton() bei Fehlern).
 */
@Composable
internal fun Meldung(text: String, farbe: Color? = null, modifier: Modifier = Modifier) {
    Text(text, modifier, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, color = farbe ?: MaterialTheme.colorScheme.onSurface)
}

/**
 * Der Kopf der Seite eines einzelnen Dings (ein Training): grosses Zeichen,
 * Titel, darunter der Zeitpunkt und grau eine Herkunft. Steht als erstes in
 * einem [Kasten]; die Werte folgen als [Kennzahlen].
 */
@Composable
internal fun Detailkopf(titel: String, unter: String? = null, herkunft: String? = null, zeichen: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(start = INNEN, end = INNEN, top = INNEN, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        zeichen?.invoke()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(titel, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold)
            if (unter != null) Text(unter, fontSize = 14.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurface)
            if (herkunft != null) Text(herkunft, fontSize = 12.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// --- Zeilen ---------------------------------------------------------------------------

/** Der Tonkreis mit dem Zeichen darin: die Flaeche blass, das Zeichen im Ton der Groesse. */
@Composable
internal fun Tonkreis(zeichen: ImageVector, flaeche: Color, strich: Color, alpha: Float = 0.22f, groesse: Dp = 36.dp, bild: Dp = 20.dp) {
    Box(Modifier.size(groesse).background(flaeche.copy(alpha = alpha), CircleShape), contentAlignment = Alignment.Center) {
        Icon(zeichen, null, Modifier.size(bild), tint = strich)
    }
}

/** Der Tonkreis in der Farbe einer Groesse - Flaeche und Ton aus [Farben]. */
@Composable
internal fun Tonkreis(zeichen: ImageVector, farbe: Farbe, alpha: Float = 0.22f, groesse: Dp = 36.dp, bild: Dp = 20.dp) =
    Tonkreis(zeichen, farbe.flaeche(), farbe.ton(), alpha, groesse, bild)

/**
 * Eine Zeile der Karte, 64 dp hoch. Ohne [onClick] steht kein Chevron -
 * was nirgendwohin fuehrt, soll nicht so aussehen.
 */
@Composable
internal fun Zeile(
    titel: String,
    unter: String? = null,
    zahl: String? = null,
    einheit: String? = null,
    zeichen: (@Composable () -> Unit)? = null,
    unterFarbe: Color? = null,
    beschreibung: String? = null,
    minHoehe: Dp = 64.dp,
    modifier: Modifier = Modifier,
    chevron: Boolean? = null,
    rechts: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val grau = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier.fillMaxWidth().heightIn(min = minHoehe)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .then(if (beschreibung != null) Modifier.semantics(mergeDescendants = true) { contentDescription = beschreibung } else Modifier)
            .padding(start = INNEN, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        zeichen?.invoke()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(titel, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)
            if (unter != null) Text(unter, fontSize = 13.sp, lineHeight = 18.sp, color = unterFarbe ?: grau)
        }
        if (zahl != null) Text(zahlMitEinheit(zahl, einheit), lineHeight = 26.sp, maxLines = 1)
        rechts?.invoke()
        if (chevron ?: (onClick != null)) Icon(Zeichen.weiter, null, Modifier.size(16.dp), tint = grau)
    }
}

/**
 * Eine Zeile mit Knopf rechts statt Chevron: eine Tat auf dieser Zeile -
 * etwas beheben ("Allow", "Choose") oder ausloesen ("Back up now",
 * "Restore"). [unter] traegt den Stand ("Last backup 5 minutes ago"). Ohne
 * [onClick] kein Knopf, mit [aktiv] = false blass. Der Knopf hat eine
 * Mindest-, keine feste Hoehe - mit grosser Schrift waechst er mit. Seine
 * Farbe ist das App-Gruen ([Farben.aktion]), nie die einer Groesse.
 */
@Composable
internal fun Knopfzeile(
    titel: String,
    unter: String? = null,
    zeichen: (@Composable () -> Unit)? = null,
    knopf: String,
    onClick: (() -> Unit)?,
    unterFarbe: Color? = null,
    aktiv: Boolean = true,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Zeile(titel, unter, zeichen = zeichen, unterFarbe = unterFarbe, modifier = Modifier.weight(1f))
        if (onClick != null) {
            FilledTonalButton(
                onClick = onClick, enabled = aktiv, modifier = Modifier.padding(end = 10.dp).heightIn(min = 36.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = Farben.aktion.flaeche().copy(alpha = 0.22f), contentColor = Farben.aktion.ton(),
                ),
            ) { Text(knopf) }
        }
    }
}

/** Eine Zeile mit Schalter - die ganze Zeile schaltet, nicht nur der Schalter. */
@Composable
internal fun Schaltzeile(titel: String, unter: String? = null, an: Boolean, aktiv: Boolean = true, setze: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .toggleable(an, enabled = aktiv, role = Role.Switch, onValueChange = setze)
            .padding(start = INNEN, end = INNEN, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(titel, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)
            if (unter != null) Text(unter, fontSize = 13.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = an, onCheckedChange = null, enabled = aktiv)
    }
}

/**
 * Der Leerzustand einer Karte als eine graue Zeile: "No route", "No backup
 * yet". Ein kurzer Satz ohne Erklaerung, warum (keine Textwaende). Steht
 * auf Kastenebene; in einem [Abschnitt] reicht eine [Unterzeile].
 */
@Composable
internal fun Leerzeile(text: String) {
    Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = INNEN, vertical = 12.dp), contentAlignment = Alignment.CenterStart) {
        Text(text, fontSize = 14.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Eine Zeile einer kleinen Tabelle (Kilometer, Saetze). [spalten]: Breite
 * je Spalte bei Schriftgroesse 1,0, null nimmt den Rest. Die letzte Spalte
 * steht rechts; [hervor] hebt eine Spalte fett und farbig hervor - in einem
 * Ton, nie einer Flaeche.
 *
 * GROSSE SCHRIFT: die festen Spalten wachsen mit der Schrift bis 1,3 mit -
 * alle Zeilen gleich, die Spalten bleiben buendig. Was dann noch nicht
 * passt, BRICHT UM statt abgeschnitten zu werden.
 */
@Composable
internal fun Tabellenzeile(zellen: List<String>, spalten: List<Dp?>, kopf: Boolean = false, hervor: Pair<Int, Color>? = null) {
    val farbe = if (kopf) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    // Hoechstens 1,3: darueber laesse die Summe der festen Spalten der freien keinen Platz.
    val schrift = LocalDensity.current.fontScale.coerceIn(1f, 1.3f)
    Row(Modifier.fillMaxWidth().padding(vertical = if (kopf) 2.dp else 6.dp), verticalAlignment = Alignment.CenterVertically) {
        zellen.forEachIndexed { i, z ->
            val breite = spalten[i]
            val h = hervor?.takeIf { it.first == i }
            Text(
                z, if (breite == null) Modifier.weight(1f) else Modifier.width(breite * schrift),
                fontSize = if (kopf) 12.sp else 14.sp, lineHeight = if (kopf) 16.sp else 20.sp,
                color = h?.second ?: farbe,
                fontWeight = if (h != null) FontWeight.Bold else if (kopf) FontWeight.Medium else null,
                textAlign = if (i == zellen.lastIndex) TextAlign.End else TextAlign.Start,
            )
        }
    }
}

/** Eine ganze Tabelle: Kopfzeile, dann die Zeilen mit einer Linie dazwischen. [hervor] je Zeilennummer. */
@Composable
internal fun Tabelle(kopf: List<String>, zeilen: List<List<String>>, spalten: List<Dp?>, hervor: (Int) -> Pair<Int, Color>? = { null }) {
    Column {
        Tabellenzeile(kopf, spalten, kopf = true)
        zeilen.forEachIndexed { i, z ->
            Trenner(0.dp)
            Tabellenzeile(z, spalten, hervor = hervor(i))
        }
    }
}

// --- Legende und Blaettern ------------------------------------------------------------

/** Ein Eintrag einer [Punktlegende]: Farbe und Name, [eckig] fuer Flaechen (ein Band). */
internal data class Legendeneintrag(val text: String, val farbe: Color, val eckig: Boolean = false)

/**
 * Farbpunkte mit ihrem Namen unter einem Bild. UMBRECHEND, nur zwischen den
 * Eintraegen: bei grosser Schrift lief eine feste Reihe rechts aus dem Bild.
 * Nur Legenden, die das Bild braucht - keine Erklaerung dazu.
 */
@Composable
internal fun Punktlegende(eintraege: List<Legendeneintrag>, mittig: Boolean = true) {
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp, if (mittig) Alignment.CenterHorizontally else Alignment.Start),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        eintraege.forEach { e ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(e.farbe, if (e.eckig) RoundedCornerShape(2.dp) else CircleShape))
                Text(
                    e.text, Modifier.padding(start = 6.dp), fontSize = 13.sp, lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false,
                )
            }
        }
    }
}

/** Ein Knopf zum Blaettern (fruehere Nacht, naechste Woche) - mit [Zeichen.zurueck] oder [Zeichen.weiter], ohne Ziel blass. */
@Composable
internal fun Blaetterknopf(text: String, zurueck: Boolean, onClick: (() -> Unit)?) {
    TextButton(onClick = { onClick?.invoke() }, enabled = onClick != null) {
        if (zurueck) Icon(Zeichen.zurueck, null, Modifier.size(18.dp))
        Text(text, Modifier.padding(horizontal = 2.dp))
        if (!zurueck) Icon(Zeichen.weiter, null, Modifier.size(18.dp))
    }
}

// --- Symbole ----------------------------------------------------------------------------

/**
 * Boulders Strichzeichen - die eine Symbolfamilie der App (siehe oben).
 * Eigene statt Materials: Materials Zeichen sind gefuellt oder anders
 * gewichtet und passten nicht zu den duennen Strichen der Vorlage.
 */
internal object Zeichen {
    private fun kreis(cx: Float, cy: Float, r: Float) = "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0"

    private fun bild(breite: Float = 2f, drehung: Float = 0f, vararg pfade: String): ImageVector =
        ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            addGroup(rotate = drehung, pivotX = 12f, pivotY = 12f)
            pfade.forEach {
                addPath(
                    PathParser().parsePathString(it).toNodes(), fill = null, stroke = SolidColor(Color.Black),
                    strokeLineWidth = breite, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
                )
            }
            clearGroup()
        }.build()

    val herz = bild(2f, 0f, "M12 20s-7-4.4-7-10a4 4 0 0 1 7-2.6A4 4 0 0 1 19 10c0 5.6-7 10-7 10z")
    val wasser = bild(2f, 0f, "M12 3.5s-6 6.6-6 10.5a6 6 0 0 0 12 0c0-3.9-6-10.5-6-10.5z")
    val kapsel = bild(2f, -45f, "M7 8.5h10a3.5 3.5 0 0 1 0 7H7a3.5 3.5 0 0 1 0-7z", "M12 8.5v7")
    val tasse = bild(2f, 0f, "M5 9h11v5a5 5 0 0 1-5 5h-1a5 5 0 0 1-5-5z M16 11h1.5a2.5 2.5 0 0 1 0 5H16 M8.5 3.5v2.5 M12.5 3.5v2.5")
    val weiter = bild(2f, 0f, "M9 6l6 6-6 6")
    /** Der Spiegel von [weiter] - zum Blaettern nach frueher. */
    val zurueck = bild(2f, 0f, "M15 6l-6 6 6 6")
    val plus = bild(2f, 0f, "M12 5v14 M5 12h14")
    val haken = bild(2f, 0f, "M5 12.5l4.5 4.5L19 7.5")
    val zu = bild(2f, 0f, "M7 7l10 10M17 7L7 17")
    val ort = bild(2f, 0f, "M12 21s-6.5-6.2-6.5-11.2a6.5 6.5 0 0 1 13 0C18.5 14.8 12 21 12 21z", kreis(12f, 9.8f, 2.3f))
    /** Eine Personenwaage mit Zeiger - die Zeile "Body". */
    val waage = bild(
        2f, 0f,
        "M7 4h10a3 3 0 0 1 3 3v10a3 3 0 0 1-3 3H7a3 3 0 0 1-3-3V7a3 3 0 0 1 3-3z", "M8.5 11a3.5 3.5 0 0 1 7 0", "M12 11l1.6-2.2",
    )
}

// --- Grosse Schrift und TalkBack (G5) -------------------------------------------------

/**
 * Der Fussraum eines Bildes fuer [zeilen] Beschriftungszeilen in [stil], in
 * Pixeln - GEMESSEN, NICHT FEST: ein fester Fuss von 16 dp passte nur bis
 * zur Schriftgroesse 1,0; darueber liefen Stunden und Daten ins Bild. Bei
 * 1,0 ergibt labelSmall genau die bisherigen 16 dp.
 */
internal fun DrawScope.fussraum(messer: TextMeasurer, stil: TextStyle, zeilen: Int = 1, luft: Dp = 0.dp): Float =
    messer.measure("0", stil).size.height.toFloat() * zeilen + luft.toPx()

/**
 * Die Spalte rechts fuer die Zahlen einer Achse, in Pixeln: so breit wie die
 * breiteste Zahl plus Luft, NIE SCHMALER ALS 30 DP - so breit war sie auf der
 * Herzseite, und mit grosser Schrift ragte "100" sonst in die Kurve. Health
 * und Trends rechneten das je fuer sich.
 */
internal fun DrawScope.achsSpalte(zahlen: List<TextLayoutResult>): Float =
    maxOf(30.dp.toPx(), (zahlen.maxOfOrNull { it.size.width } ?: 0) + 6.dp.toPx())

/**
 * Dieselbe Spalte, an einem Muster gemessen statt an den Zahlen selbst: alle
 * Bilder einer Karte sind dann gleich breit, und dieselbe Uhrzeit steht in
 * jedem senkrecht uebereinander (Nacht: Hypnogramm, Puls, SpO2).
 */
internal fun DrawScope.achsSpalte(messer: TextMeasurer, stil: TextStyle, muster: String = "000"): Float =
    achsSpalte(listOf(messer.measure(muster, stil)))

/**
 * Die Hoehe eines Bildes, um das gewachsen, was seine Beschriftung mit
 * grosser Schrift mehr braucht: so bleibt die Flaeche fuer die Werte gleich
 * gross, statt dem Fuss zu weichen. Bei Schriftgroesse 1,0 = [hoehe].
 */
@Composable
internal fun hoeheMitSchrift(hoehe: Dp, zeilen: Int = 1, stil: TextStyle = MaterialTheme.typography.labelSmall): Dp {
    val zeile = if (stil.lineHeight.isSp) stil.lineHeight else (stil.fontSize.value * 1.45f).sp
    val gross = with(LocalDensity.current) { zeile.toDp() }
    return hoehe + (gross - zeile.value.dp).coerceAtLeast(0.dp) * zeilen
}

/**
 * Was ein Bild zeigt, fuer TalkBack - ein kurzer Satz mit den Zahlen, die
 * man sonst nur sieht ("Steps, Sep 19 to Oct 2, avg 8.2k, goal 10k").
 * Ohne Text bleibt das Bild stumm wie bisher.
 */
internal fun Modifier.bildBeschreibung(text: String?): Modifier =
    if (text == null) this else semantics { contentDescription = text; role = Role.Image }

/**
 * Eine Zeile, die nur auf langen Druck etwas tut (Eintrag aendern oder
 * loeschen): mindestens [mindesthoehe] hoch, mit Ruettler, und TalkBack
 * nennt die Tat ([label], "Edit"). OHNE KLICK: ein combinedClickable mit
 * leerem onClick meldete einen Doppeltipp, der nichts tat.
 *
 * Als aeusserster Modifier vor einer IntrinsicSize-Hoehe setzen - danach
 * gesetzt koennte die Mindesthoehe die feste Hoehe nicht mehr heben.
 */
internal fun Modifier.langDruck(label: String, mindesthoehe: Dp = 48.dp, aktion: () -> Unit): Modifier = composed {
    val haptik = LocalHapticFeedback.current
    val quelle = remember { MutableInteractionSource() }
    val aktuell by rememberUpdatedState(aktion)
    heightIn(min = mindesthoehe)
        .indication(quelle, LocalIndication.current)
        .pointerInput(quelle) {
            detectTapGestures(
                onPress = { stelle ->
                    // Die Welle beim Druecken wie bei jedem Ziel - sonst wirkte der lange Druck tot.
                    val druck = PressInteraction.Press(stelle)
                    quelle.emit(druck)
                    quelle.emit(if (tryAwaitRelease()) PressInteraction.Release(druck) else PressInteraction.Cancel(druck))
                },
                onLongPress = {
                    haptik.performHapticFeedback(HapticFeedbackType.LongPress)
                    aktuell()
                },
            )
        }
        .semantics(mergeDescendants = true) { onLongClick(label) { aktuell(); true } }
}
