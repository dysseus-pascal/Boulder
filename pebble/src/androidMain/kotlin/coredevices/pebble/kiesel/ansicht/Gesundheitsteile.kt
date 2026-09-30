package coredevices.pebble.kiesel.ansicht

import android.content.Context
import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import coredevices.pebble.R
import coredevices.pebble.kiesel.Gesundheitsrechnung
import coredevices.pebble.kiesel.tageZurueck
import java.time.Instant
import java.time.ZoneId

/*
 * Die Bausteine der Gesundheitsseite und von "Heart today" - Karte, Kopf und
 * Zeile im Stil der Akkuseite: Tonkreis mit Zeichen, Titel und Statuszeile,
 * die Zahl fett mit kleiner Einheit, ein Chevron, wenn die Zeile weiterfuehrt.
 */

/**
 * Die Toene fuer Striche und Text. DIE BEREICHSFARBEN SIND FUER FLAECHEN:
 * als Schrift auf dem dunklen Grau waeren Schlafblau und Herzrot zu schwach,
 * darum aufgehellt. Im hellen Schema ist es umgekehrt - dort tragen die
 * dunklen Toene die Schrift.
 */
internal data class Toene(
    val schlaf: Color,
    val schlafPlus: Color,
    val bewegung: Color,
    val herz: Color,
    val wasser: Color,
    val koffein: Color,
    val warnung: Color,
)

private val DUNKEL = Toene(
    schlaf = Color(0xFF7FA7D9), schlafPlus = Color(0xFFC3D6EF), bewegung = Color(0xFF9BD0A6), herz = Color(0xFFE8776C),
    wasser = Color(0xFF6FA9DC), koffein = Color(0xFFD09A62), warnung = Color(0xFFE0A33A),
)
private val HELL = Toene(
    schlaf = Color(0xFF2F5597), schlafPlus = Color(0xFF8FB0DE), bewegung = Color(0xFF3B6E2B), herz = Color(0xFFB03427),
    wasser = Color(0xFF2B6FA8), koffein = Color(0xFF7A4E22), warnung = Color(0xFF8A5F00),
)

@Composable
internal fun toene(): Toene = if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) DUNKEL else HELL

internal val FLAECHE_BEWEGUNG = Color(0xFF4F8A3A)
internal val FLAECHE_HERZ = Color(0xFFC0392B)
internal val FLAECHE_WASSER = Color(0xFF3A86C8)
internal val FLAECHE_KOFFEIN = Color(0xFF8A5A2B)
internal val FLAECHE_PRAEPARAT = Color(0xFF7DBB8A)

internal fun uhrzeit(context: Context, t: Instant): String =
    DateFormat.getTimeFormat(context).format(java.util.Date(t.toEpochMilli()))

/**
 * Eine Uhrzeit, und der Tag davor, wenn sie nicht von heute ist: "9:14 PM",
 * "yesterday 9:14 PM", "Sep 27, 9:14 PM". Ein Stand von gestern soll nicht
 * wie einer von heute aussehen.
 */
internal fun zeitpunkt(context: Context, t: Instant): String =
    when (Gesundheitsrechnung.tageZurueck(t, Instant.now(), ZoneId.systemDefault())) {
        0L -> uhrzeit(context, t)
        1L -> context.getString(R.string.ks_h_gestern_um, uhrzeit(context, t))
        else -> DateUtils.formatDateTime(
            context, t.toEpochMilli(),
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_NO_YEAR or DateUtils.FORMAT_SHOW_TIME,
        )
    }

/** Eine Dauer als "8:20" - ohne Einheit, die steht daneben. */
internal fun stundenMinuten(minuten: Int): String = String.format(java.util.Locale.getDefault(), "%d:%02d", minuten / 60, minuten % 60)

@Composable
internal fun Kasten(inhalt: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = kartenFarben(), border = kartenRand()) { inhalt() }
}

/** Der Kopf einer Karte: Name in Versalien, grauer Zusatz, rechts ein Link mit Chevron. */
@Composable
internal fun Kopf(titel: String, zusatz: String? = null, link: String? = null, onLink: (() -> Unit)? = null) {
    val farben = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().height(44.dp).padding(start = 14.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.Medium, letterSpacing = 0.08.em, color = farben.onSurface)) { append(titel) }
                if (zusatz != null) withStyle(SpanStyle(color = farben.onSurfaceVariant)) { append(" $zusatz") }
            },
            Modifier.weight(1f), fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (link != null && onLink != null) {
            Row(
                Modifier.heightIn(min = 44.dp).clickable(onClick = onLink).padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(link, color = farben.primary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Icon(Zeichen.weiter, null, Modifier.size(18.dp), tint = farben.primary)
            }
        }
    }
}

/** Die Linie zwischen zwei Zeilen; eingerueckt bis zum Text, wenn links ein Zeichen steht. */
@Composable
internal fun Trenner(einzug: Dp = 64.dp) {
    Box(Modifier.padding(start = einzug).fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)))
}

/** Die Zahl fett, die Einheit klein und grau daneben. */
@Composable
internal fun zahlMitEinheit(zahl: String, einheit: String?, groesse: Int = 20, einheitGroesse: Int = 13): AnnotatedString {
    val grau = MaterialTheme.colorScheme.onSurfaceVariant
    return buildAnnotatedString {
        withStyle(SpanStyle(fontSize = groesse.sp, fontWeight = FontWeight.SemiBold)) { append(zahl) }
        if (einheit != null) withStyle(SpanStyle(fontSize = einheitGroesse.sp, fontWeight = FontWeight.Medium, color = grau)) { append(" $einheit") }
    }
}

/** Der Tonkreis mit dem Zeichen darin. */
@Composable
internal fun Tonkreis(zeichen: ImageVector, flaeche: Color, strich: Color, alpha: Float = 0.22f) {
    Box(Modifier.size(36.dp).background(flaeche.copy(alpha = alpha), CircleShape), contentAlignment = Alignment.Center) {
        Icon(zeichen, null, Modifier.size(20.dp), tint = strich)
    }
}

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
    onClick: (() -> Unit)? = null,
) {
    val grau = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier.fillMaxWidth().heightIn(min = minHoehe)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .then(if (beschreibung != null) Modifier.semantics(mergeDescendants = true) { contentDescription = beschreibung } else Modifier)
            .padding(start = 14.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        zeichen?.invoke()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(titel, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)
            if (unter != null) Text(unter, fontSize = 13.sp, lineHeight = 18.sp, color = unterFarbe ?: grau)
        }
        if (zahl != null) Text(zahlMitEinheit(zahl, einheit), lineHeight = 26.sp, maxLines = 1)
        if (chevron ?: (onClick != null)) Icon(Zeichen.weiter, null, Modifier.size(16.dp), tint = grau)
    }
}

/**
 * Text mit <b>…</b> fuer die Begriffe der Fussnote: sie stehen heller und
 * etwas kraeftiger. Mehr Auszeichnung kennt die Seite nicht.
 */
internal fun mitBegriffen(text: String, begriff: SpanStyle): AnnotatedString = buildAnnotatedString {
    var rest = text
    while (true) {
        val a = rest.indexOf("<b>")
        val b = rest.indexOf("</b>", a + 3)
        if (a < 0 || b < 0) {
            append(rest)
            break
        }
        append(rest.substring(0, a))
        withStyle(begriff) { append(rest.substring(a + 3, b)) }
        rest = rest.substring(b + 4)
    }
}
