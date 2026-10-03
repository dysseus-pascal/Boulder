package coredevices.pebble.kiesel.ansicht

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coredevices.pebble.R
import coredevices.pebble.kiesel.Akkurechnung
import coredevices.pebble.kiesel.GesundheitsStand
import coredevices.pebble.kiesel.Gesundheitsrechnung
import coredevices.pebble.kiesel.Praeparatlage
import coredevices.pebble.kiesel.Formate
import coredevices.pebble.kiesel.Schlafurteil
import coredevices.pebble.kiesel.Tageszeit
import coredevices.pebble.kiesel.Vergleich
import coredevices.pebble.kiesel.praeparatzeile
import coredevices.pebble.kiesel.ruhepulsUrteil
import coredevices.pebble.kiesel.schlafurteil
import coredevices.pebble.kiesel.tageszeit
import java.time.LocalDate
import java.time.LocalTime

/**
 * Eine Vorlage mit %1$s, %2$s ... einsetzen und die eingesetzten Werte
 * hervorheben - so stehen die Zahlen im Satz heraus, und jede Sprache behaelt
 * ihre eigene Wortfolge.
 */
private fun AnnotatedString.Builder.hervor(vorlage: String, vararg werte: String, farbe: Color) {
    val muster = Regex("%(\\d+)\\\$[sd]")
    var i = 0
    muster.findAll(vorlage).forEach { m ->
        append(vorlage.substring(i, m.range.first))
        val wert = werte.getOrNull(m.groupValues[1].toInt() - 1).orEmpty()
        withStyle(SpanStyle(color = farbe, fontWeight = FontWeight.Medium)) { append(wert) }
        i = m.range.last + 1
    }
    append(vorlage.substring(i))
}

/** Oben: ein Gruss und ein Satz zum Tag - mit dem Uhrenakku, der zur Akkuansicht fuehrt. */
@Composable
internal fun Tagessatz(s: GesundheitsStand, akkuJetzt: Int?, akkuRest: Akkurechnung.Restzeit?, oeffneAkku: () -> Unit) {
    val hell = MaterialTheme.colorScheme.onSurface
    val grau = MaterialTheme.colorScheme.onSurfaceVariant
    val gruss = stringResource(
        when (Gesundheitsrechnung.tageszeit(LocalTime.now())) {
            Tageszeit.MORGEN -> R.string.ks_f_morgen
            Tageszeit.TAG -> R.string.ks_f_tag
            Tageszeit.ABEND -> R.string.ks_f_abend
        }
    )
    val n = s.nacht
    val urteilWort = n?.let {
        stringResource(
            when (Gesundheitsrechnung.schlafurteil(it.schlafMinuten)) {
                Schlafurteil.AUSGERUHT -> R.string.ks_h_urteil_ausgeruht
                Schlafurteil.KURZ -> R.string.ks_h_urteil_kurz
                Schlafurteil.SEHR_KURZ -> R.string.ks_h_urteil_sehr_kurz
            }
        )
    }
    val ruhe = n?.let { Gesundheitsrechnung.ruhepulsUrteil(it.ruhepuls, s.ruhepulsWoche) }
    val vSchlaf = stringResource(R.string.ks_f_schlaf)
    val vRuhe = when (ruhe?.urteil) {
        Vergleich.WIE_IMMER -> stringResource(R.string.ks_f_ruhe_ok)
        Vergleich.HOEHER -> stringResource(R.string.ks_f_ruhe_hoch, ruhe.abstand)
        Vergleich.TIEFER -> stringResource(R.string.ks_f_ruhe_tief, ruhe.abstand)
        null -> null
    }
    val schritteWert = pluralStringResource(R.plurals.ks_f_schritte_wert, s.schritte.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), Formate.zahl(s.schritte))
    val vSchritte = stringResource(if (n != null) R.string.ks_f_schritte else R.string.ks_f_schritte_allein)
    val platz = stringResource(R.string.ks_s_platz_n)
    val offen = s.supplemente?.takeIf { s.mitSupCycle }?.let { stand ->
        val z = Gesundheitsrechnung.praeparatzeile(stand.heute { String.format(platz, it) })
        // Nur Versaeumtes gehoert in den Satz - was erst spaeter ansteht, steht in der Liste.
        if (z.lage == Praeparatlage.OFFEN) z.namen.firstOrNull() else null
    }
    val vHaerte = when (Gesundheitsrechnung.tagesurteil(s.tagesherz, LocalDate.now())?.haerte) {
        Vergleich.HOEHER -> stringResource(R.string.ks_f_tag_haerter)
        Vergleich.TIEFER -> stringResource(R.string.ks_f_tag_leichter)
        else -> null
    }
    val vOffen = stringResource(R.string.ks_f_offen)
    val vAkkuVor = stringResource(R.string.ks_f_akku_vor)
    val vAkkuNach = when {
        akkuRest == null -> "."
        akkuRest.stunden -> stringResource(R.string.ks_f_akku_stunden, akkuRest.wert)
        else -> pluralStringResource(R.plurals.ks_f_akku_tage, akkuRest.wert, akkuRest.wert)
    }
    // Die Stufe des Akkus (gruen, gelb, rot) als Ton: das alte Rot ohne Hell-Variante kam auf Weiss auf 2,5:1.
    val akkuTon = akkuJetzt?.let { Farben.akkuStufe(it).ton() }

    val text = buildAnnotatedString {
        if (n != null && urteilWort != null) {
            hervor(vSchlaf, urteilWort, Formate.dauer(n.schlafMinuten.toLong()), farbe = hell)
            if (vRuhe != null) append(vRuhe)
        }
        hervor(vSchritte, schritteWert, farbe = hell)
        // Ein gewoehnlicher Tag braucht kein Wort - nur die Abweichung der Belastung zaehlt
        if (vHaerte != null) append(vHaerte)
        if (offen != null) hervor(vOffen, offen, farbe = hell)
        append(".")
        if (akkuJetzt != null && akkuTon != null) {
            append(" $vAkkuVor ")
            // Nur die Zahl, in der Farbe des Stands, als Link zur Akkuansicht -
            // ein Symbol davor stach im Satz zu sehr heraus.
            withLink(LinkAnnotation.Clickable("akku", TextLinkStyles(SpanStyle(color = akkuTon, fontWeight = FontWeight.Medium))) { oeffneAkku() }) {
                append(Formate.prozent(akkuJetzt))
            }
            append(vAkkuNach)
        }
    }
    Column(Modifier.padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(gruss, fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold)
        Text(text, fontSize = 18.sp, lineHeight = 26.sp, color = grau)
    }
}
