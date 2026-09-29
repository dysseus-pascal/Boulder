package coredevices.pebble.kiesel.ansicht

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import coredevices.pebble.R
import coredevices.pebble.kiesel.Anteil
import coredevices.pebble.kiesel.Trainingstag
import coredevices.pebble.kiesel.Trainingswoche
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle as DatumStil
import java.time.temporal.WeekFields
import java.util.Locale
import kotlin.math.sqrt

private fun kw(tag: LocalDate): Int = tag.get(WeekFields.ISO.weekOfWeekBasedYear())

/** Text mittig um einen Punkt - Compose zeichnet sonst ab der linken oberen Ecke. */
private fun DrawScope.textMittig(messer: TextMeasurer, text: String, mitte: Offset, stil: TextStyle) {
    val m = messer.measure(text, stil)
    drawText(m, topLeft = Offset(mitte.x - m.size.width / 2f, mitte.y - m.size.height / 2f))
}

/**
 * Vier Wochen, ein Punkt je Tag.
 *
 * PUNKTE STATT KACHELN (aus Kiesel-Helper): ein leerer Tag ist ein kleiner
 * Punkt, ein Trainingstag ein Kreis in der Farbe seiner Art, und die Groesse
 * sagt, wie lang. Kam an einem Tag eine zweite Art dazu, liegt ihr Ring
 * aussen.
 */
@Composable
internal fun Kalender(tage: Map<LocalDate, Trainingstag>, heute: LocalDate) {
    val wochen = 4
    val start = heute.with(DayOfWeek.MONDAY).minusWeeks((wochen - 1).toLong())
    val messer = rememberTextMeasurer()
    val zart = MaterialTheme.colorScheme.onSurfaceVariant
    val klein = MaterialTheme.typography.labelSmall.copy(color = zart)
    val fett = klein.copy(color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
    val kwTexte = (0 until wochen).map { stringResource(R.string.ks_n_kw, kw(start.plusWeeks(it.toLong())).toString()) }
    val tagNamen = DayOfWeek.entries.map { it.getDisplayName(DatumStil.SHORT, Locale.getDefault()).take(2) }

    Canvas(Modifier.fillMaxWidth().height((20 + wochen * 34).dp).padding(top = 4.dp)) {
        val kopf = 20.dp.toPx()
        val zeile = 34.dp.toPx()
        val randLinks = 38.dp.toPx()
        val fach = (size.width - randLinks) / 7f
        val gross = minOf(fach, zeile) / 2f - 3.dp.toPx()
        val kleinR = gross * 0.42f
        val ring = 2.dp.toPx()

        tagNamen.forEachIndexed { i, name ->
            val stil = if (DayOfWeek.entries[i] == heute.dayOfWeek) fett else klein
            textMittig(messer, name, Offset(randLinks + fach * i + fach / 2, kopf / 2), stil)
        }
        for (w in 0 until wochen) {
            val montag = start.plusWeeks(w.toLong())
            val my = kopf + w * zeile + zeile / 2
            // Die Kalenderwoche links, klein: wer plant, plant in Wochen.
            val m = messer.measure(kwTexte[w], klein)
            drawText(m, topLeft = Offset(0f, my - m.size.height / 2f))

            for (t in 0 until 7) {
                val datum = montag.plusDays(t.toLong())
                val mitte = Offset(randLinks + fach * t + fach / 2, my)
                val tag = tage[datum]
                when {
                    datum.isAfter(heute) -> drawCircle(zart.copy(alpha = 0.12f), 2.dp.toPx(), mitte)
                    tag == null -> drawCircle(zart.copy(alpha = 0.28f), 2.5.dp.toPx(), mitte)
                    else -> {
                        // Die Flaeche waechst mit der Zeit, nicht der Radius:
                        // doppelt so lang soll doppelt so viel Farbe sein.
                        val anteil = sqrt((tag.minuten / 120.0).coerceIn(0.0, 1.0)).toFloat()
                        val r = kleinR + (gross - kleinR) * anteil
                        drawCircle(Color(tag.arten.first().farbe), r, mitte)
                        tag.arten.getOrNull(1)?.let { zweite ->
                            drawCircle(Color(zweite.farbe), r + ring * 1.3f, mitte, style = Stroke(ring))
                        }
                    }
                }
                if (datum == heute) drawCircle(zart, gross + 1.5.dp.toPx(), mitte, style = Stroke(1.5.dp.toPx()))
            }
        }
    }
}

/**
 * Acht Wochen, je eine Saeule, gestapelt nach Art.
 *
 * GESTAPELT UND NICHT NEBENEINANDER: die Frage ist zuerst "wie viel war es",
 * dann "woraus bestand es". Die Hoehe beantwortet die erste, die Farben die
 * zweite.
 */
@Composable
internal fun Wochenstapel(wochen: List<Trainingswoche>, heute: LocalDate) {
    if (wochen.isEmpty()) return
    val diese = heute.with(DayOfWeek.MONDAY)
    val messer = rememberTextMeasurer()
    val klein = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val fett = klein.copy(color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
    val leer = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)

    Canvas(Modifier.fillMaxWidth().height(160.dp).padding(top = 8.dp)) {
        val fuss = 16.dp.toPx()
        val kopf = 16.dp.toPx()
        val boden = size.height - fuss
        val hoehe = boden - kopf
        // Eine Stunde als Mindesthoehe: sonst fuellte eine einzige kurze
        // Woche die ganze Hoehe und saehe nach viel aus.
        val spitze = maxOf(wochen.maxOf { it.minuten }, 60L).toFloat()
        val fach = size.width / wochen.size
        val balken = minOf(fach * 0.55f, 26.dp.toPx())
        val ecke = CornerRadius(4.dp.toPx())

        wochen.forEachIndexed { i, w ->
            val mitte = fach * i + fach / 2
            val links = mitte - balken / 2
            // Die leere Spur zeigt, dass die Woche da war - auch ohne Training.
            drawRoundRect(leer, Offset(links, kopf), Size(balken, hoehe), ecke)

            if (w.minuten > 0) {
                val oben = boden - hoehe * (w.minuten / spitze)
                // Erst rund ausschneiden, dann die Schichten hinein: so hat
                // der ganze Stapel runde Ecken und nicht jede Schicht.
                val form = Path().apply { addRoundRect(RoundRect(links, oben, links + balken, boden, ecke)) }
                clipPath(form) {
                    var y = boden
                    w.teile.forEach { (art, minuten) ->
                        val h = hoehe * (minuten / spitze)
                        drawRect(
                            Color(art.farbe).copy(alpha = if (w.montag == diese) 1f else 0.85f),
                            Offset(links, y - h), Size(balken, h),
                        )
                        y -= h
                    }
                }
                textMittig(messer, dauerText(w.minuten), Offset(mitte, oben - 9.dp.toPx()), klein)
            }
            textMittig(
                messer, kw(w.montag).toString(), Offset(mitte, size.height - fuss / 2),
                if (w.montag == diese) fett else klein,
            )
        }
    }
}

/**
 * Die Arten eines Vierteljahres: ein Band und darunter die Legende.
 *
 * EIN BAND STATT EINES KUCHENS: Anteile liest man an Laengen besser als an
 * Winkeln.
 */
@Composable
internal fun Verteilung(anteile: List<Anteil>) {
    if (anteile.isEmpty()) return
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp).height(14.dp).clip(RoundedCornerShape(7.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)),
    ) {
        anteile.forEachIndexed { i, a ->
            if (i > 0) Spacer(Modifier.width(2.dp))
            Box(Modifier.weight(a.minuten.toFloat()).fillMaxHeight().background(Color(a.sport.farbe)))
        }
    }
    anteile.forEach { a ->
        Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            SportZeichen(a.sport, 30)
            Text(stringResource(a.sport.nameId), Modifier.weight(1f).padding(start = 10.dp))
            Text(
                "${a.anzahl}×  ·  ${dauerText(a.minuten)}  ·  ${a.prozent} %",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
