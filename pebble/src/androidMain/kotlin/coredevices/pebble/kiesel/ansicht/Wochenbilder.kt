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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import coredevices.pebble.R
import coredevices.pebble.kiesel.Anteil
import coredevices.pebble.kiesel.Formate
import coredevices.pebble.kiesel.Sport
import coredevices.pebble.kiesel.Trainingstag
import coredevices.pebble.kiesel.Trainingswoche
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.WeekFields
import kotlin.math.sqrt

/** Die ISO-Kalenderwoche als Text - ueber Formate, damit sie in den Ziffern der Sprache steht wie jede andere Zahl. */
private fun kw(tag: LocalDate): String = Formate.zahl(tag.get(WeekFields.ISO.weekOfWeekBasedYear()).toLong())

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
    val kwTexte = (0 until wochen).map { stringResource(R.string.ks_n_kw, kw(start.plusWeeks(it.toLong()))) }
    val tagNamen = DayOfWeek.entries.map { Formate.wochentagKurz(it) }
    // Die leeren Tage blass, aber deckend gemischt - mit Alpha laegen sie fleckig auf dem Grund.
    val zukunft = zart.aufKarte(0.12f)
    val leer = zart.aufKarte(0.28f)
    val dunkel = Farben.dunkel()
    // Fuer TalkBack die Trainingstage als Text - im Bild stehen sie nur als Kreise.
    val namen = Sport.entries.associateWith { stringResource(it.nameId) }
    val satz = (0 until wochen * 7).map { start.plusDays(it.toLong()) }.filter { !it.isAfter(heute) }.mapNotNull { tag ->
        tage[tag]?.let { t ->
            stringResource(R.string.ks_sp_bild_wert, Formate.datumMitWochentag(tag), (t.arten.map { namen.getValue(it) } + Formate.dauer(t.minuten)).joinToString(", "))
        }
    }.ifEmpty { listOf(stringResource(R.string.ks_sp_keine)) }.joinToString("; ")

    // Die Hoehe waechst mit der Schrift der Wochentage, die Zeilen der Punkte bleiben gleich.
    Canvas(Modifier.fillMaxWidth().height(hoeheMitSchrift(160.dp)).padding(top = 4.dp).bildBeschreibung(satz)) {
        val kopf = fussraum(messer, klein, luft = 4.dp)
        val zeile = 34.dp.toPx()
        // Die Spalte der Kalenderwochen so breit wie ihr Text: "Wk 40" lief mit grosser Schrift in die Punkte.
        val randLinks = maxOf(38.dp.toPx(), kwTexte.maxOf { messer.measure(it, klein).size.width } + 8.dp.toPx())
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
                    datum.isAfter(heute) -> drawCircle(zukunft, 2.dp.toPx(), mitte)
                    tag == null -> drawCircle(leer, 2.5.dp.toPx(), mitte)
                    else -> {
                        // Die Flaeche waechst mit der Zeit, nicht der Radius:
                        // doppelt so lang soll doppelt so viel Farbe sein.
                        val anteil = sqrt((tag.minuten / 120.0).coerceIn(0.0, 1.0)).toFloat()
                        val r = kleinR + (gross - kleinR) * anteil
                        drawCircle(Farben.sport(tag.arten.first()).flaecheIm(dunkel), r, mitte)
                        tag.arten.getOrNull(1)?.let { zweite ->
                            drawCircle(Farben.sport(zweite).flaecheIm(dunkel), r + ring * 1.3f, mitte, style = Stroke(ring))
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
    val leer = Farben.spur()
    val karte = Farben.karte()
    val dunkel = Farben.dunkel()
    // Die Minuten jeder Woche fuer TalkBack - im Bild stehen sie nur ueber den Saeulen.
    val satz = wochen.map { w ->
        stringResource(R.string.ks_sp_bild_wert, stringResource(R.string.ks_n_kw, kw(w.montag)), Formate.dauer(w.minuten))
    }.joinToString(", ")

    // Zwei Zeilen Schrift im Bild (Minuten oben, Woche unten): beide wachsen mit.
    Canvas(Modifier.fillMaxWidth().height(hoeheMitSchrift(160.dp, zeilen = 2)).padding(top = 8.dp).bildBeschreibung(satz)) {
        val fuss = fussraum(messer, klein)
        val kopf = fussraum(messer, klein, luft = 1.dp)
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
                        val flaeche = Farben.sport(art).flaecheIm(dunkel)
                        // Fruehere Wochen etwas blasser, deckend mit der Karte gemischt statt Alpha.
                        drawRect(if (w.montag == diese) flaeche else flaeche.copy(alpha = 0.85f).compositeOver(karte), Offset(links, y - h), Size(balken, h))
                        y -= h
                    }
                }
                // Passt die Einheit mit grosser Schrift nicht mehr ueber die Saeule, steht nur die Zahl.
                val dauer = Formate.dauerTeile(w.minuten)
                val voll = messer.measure(dauer.text, klein)
                val m = if (voll.size.width <= fach - 2.dp.toPx()) voll else messer.measure(dauer.zahl, klein)
                drawText(m, topLeft = Offset(mitte - m.size.width / 2f, oben - 1.dp.toPx() - m.size.height))
            }
            textMittig(
                messer, kw(w.montag), Offset(mitte, size.height - fuss / 2),
                if (w.montag == diese) fett else klein,
            )
        }
    }
}

/**
 * Die Arten eines Vierteljahres: ein Band und darunter eine Zeile je Art.
 *
 * EIN BAND STATT EINES KUCHENS: Anteile liest man an Laengen besser als an
 * Winkeln. Steht direkt im [Kasten] unter dem [Kopf] - die Zeilen bringen
 * ihren Rand selbst mit.
 */
@Composable
internal fun Verteilung(anteile: List<Anteil>) {
    if (anteile.isEmpty()) return
    Row(
        Modifier.fillMaxWidth().padding(start = INNEN, end = INNEN, top = 4.dp, bottom = 4.dp).height(14.dp)
            .clip(RoundedCornerShape(7.dp)).background(Farben.spur()),
    ) {
        anteile.filter { it.minuten > 0 }.forEachIndexed { i, a ->
            if (i > 0) Spacer(Modifier.width(2.dp))
            Box(Modifier.weight(a.minuten.toFloat()).fillMaxHeight().background(Farben.sport(a.sport).flaeche()))
        }
    }
    anteile.forEachIndexed { i, a ->
        if (i > 0) Trenner()
        val dauer = Formate.dauerTeile(a.minuten)
        Zeile(
            stringResource(a.sport.nameId),
            pluralStringResource(R.plurals.ks_sp_anzahl, a.anzahl, a.anzahl) + " · " + Formate.prozent(a.prozent),
            dauer.zahl, dauer.einheit, zeichen = { SportKreis(a.sport) },
        )
    }
}
