package coredevices.pebble.kiesel.ansicht

import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coredevices.pebble.R
import coredevices.pebble.kiesel.GesundheitsStand
import coredevices.pebble.kiesel.Gesundheitsziele
import coredevices.pebble.kiesel.Formate
import java.time.LocalDate

/**
 * Schritte der Woche als Saeulen, das Ziel durchgezogen und an der Linie
 * beschriftet ("goal 10k") - wie seit 0.26.0 ueberall: durchgezogen das
 * Ziel, gestrichelt ein Schnitt.
 */
@Composable
internal fun Schrittwoche(s: GesundheitsStand, trend: () -> Unit) {
    val grau = Farben.neutral()
    // Die leere Spur neutral: surfaceVariant war im hellen Schema ein Lila, das sonst nirgends vorkommt.
    val leer = Farben.spur()
    val voll = Farben.bewegung.flaeche()
    // Unter dem Ziel blasser - deckend mit der Karte gemischt statt Alpha ueber der Spur.
    val blass = voll.aufKarte(0.75f)
    val heute = LocalDate.now()
    val messer = rememberTextMeasurer()
    // Die Schrift der Bilder (labelSmall) auch fuer Zahlen und Tage um die Saeulen.
    val stil = MaterialTheme.typography.labelSmall.copy(color = grau)
    val titel = stringResource(R.string.ks_d_schritte_woche)
    val zielText = stringResource(R.string.ks_h_ziel_zahl, Formate.schritteKurz(Gesundheitsziele.SCHRITTE))
    // Fuer TalkBack die Woche in einem Satz, mit den genauen Zahlen statt "9.9k".
    val satz = "$titel, $zielText: " + s.wocheSchritte.joinToString(", ") { (tag, n) -> Formate.wochentagLang(tag.dayOfWeek) + " " + Formate.zahl(n) }
    Kasten {
        Kopf(titel, link = stringResource(R.string.ks_h_trends), onLink = trend)
        Row(
            Modifier.fillMaxWidth().clickable(onClick = trend).semantics { contentDescription = satz }.padding(start = 10.dp, end = 10.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val spitze = maxOf(Gesundheitsziele.SCHRITTE, s.wocheSchritte.maxOfOrNull { it.second } ?: 0L).toFloat()
            s.wocheSchritte.forEachIndexed { i, (tag, schritte) ->
                val letzte = i == s.wocheSchritte.lastIndex
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    // Abgerundet wie "goal 10k" daneben und die Trends: 9'960 gerundet als "10.0k" unter der Ziellinie widerspraeche ihr.
                    // Darf ueber die Saeule in die Luecke ragen: bei Schrift 1,3 ist "10.2k" so breit wie die Spalte.
                    Text(
                        if (schritte > 0) Formate.schritteKurz(schritte) else "", Modifier.wrapContentWidth(unbounded = true),
                        style = stil, fontWeight = FontWeight.Normal, maxLines = 1, softWrap = false,
                    )
                    val gruen = if (schritte >= Gesundheitsziele.SCHRITTE) voll else blass
                    // Mit der Schrift hoeher: die Zielbeschriftung steht im Bild und soll die Saeule nicht verdraengen.
                    Canvas(Modifier.fillMaxWidth().height(hoeheMitSchrift(64.dp))) {
                        drawRoundRect(leer, Offset.Zero, size, CornerRadius(4.dp.toPx()))
                        val h = size.height * (schritte / spitze)
                        if (h > 0) drawRoundRect(gruen, Offset(0f, size.height - h), Size(size.width, h), CornerRadius(4.dp.toPx()))
                        val ziel = size.height * (1 - Gesundheitsziele.SCHRITTE / spitze)
                        // Halb in die Luecke zur Nachbarsaeule: so laeuft die Linie durch und liest sich nicht als gestrichelt.
                        drawLine(grau, Offset(-3.dp.toPx(), ziel), Offset(size.width + 3.dp.toPx(), ziel), 1.5.dp.toPx())
                        // Die Beschriftung rechts am Linienende wie in den Trends, ueber der Linie, wenn
                        // sie ins Bild passt, sonst darunter; die letzte Saeule zeichnet zuletzt, also obenauf.
                        if (letzte) {
                            val m = messer.measure(zielText, stil)
                            val abstand = 2.dp.toPx()
                            val oben = (ziel - abstand - m.size.height).takeIf { it >= 0f } ?: (ziel + abstand)
                            drawText(m, topLeft = Offset(size.width + 3.dp.toPx() - m.size.width, oben.coerceAtMost(size.height - m.size.height)))
                        }
                    }
                    Text(
                        Formate.wochentagKurz(tag.dayOfWeek), style = stil,
                        // Normal statt des Medium von labelSmall: sonst hebt sich heute kaum ab.
                        fontWeight = if (tag == heute) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (tag == heute) MaterialTheme.colorScheme.onSurface else grau,
                    )
                }
            }
        }
    }
}
