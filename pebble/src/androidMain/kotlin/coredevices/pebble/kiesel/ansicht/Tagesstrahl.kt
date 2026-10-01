package coredevices.pebble.kiesel.ansicht

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coredevices.pebble.R
import coredevices.pebble.kiesel.GesundheitsStand
import coredevices.pebble.kiesel.Gesundheitsrechnung
import coredevices.pebble.kiesel.Nacht
import coredevices.pebble.kiesel.Praeparatlage
import coredevices.pebble.kiesel.SCHRITTZIEL
import coredevices.pebble.kiesel.TrainingEintrag
import coredevices.pebble.kiesel.praeparatzeile
import coredevices.pebble.kiesel.pulsFrisch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

/*
 * Die Health-Seite nach Konzept D2: der Tag als Zeitstrahl, das Jetzt oben,
 * darunter rueckwaerts, was heute war, ganz unten die Nacht. Wer die Seite
 * oeffnet, sieht zuerst, wie es gerade steht - nicht eine Achse.
 */

// Breit genug fuer "11:22 AM" in einer Zeile.
private val SPALTE_ZEIT = 62.dp
private val SPALTE_STRAHL = 22.dp

private fun liter(ml: Int): String = if (ml == 0) "0" else Gesundheitsrechnung.literKurz(ml)

private fun zahl(n: Long): String = String.format(Locale.getDefault(), "%,d", n)

/** Drei Zahlen oben: geschlafen, Schritte, Wasser - je ein Tipp zur Seite dahinter. */
@Composable
internal fun Kurzkarte(s: GesundheitsStand, nacht: () -> Unit, bewegung: () -> Unit, wasser: () -> Unit) {
    Kasten {
        Row(Modifier.fillMaxWidth().padding(6.dp)) {
            val n = s.nacht
            Kurzwert(
                if (n != null) stundenMinuten(n.schlafMinuten) else "–", if (n != null) "h" else null,
                stringResource(R.string.ks_d_geschlafen), Modifier.weight(1f), if (n != null) nacht else null,
            )
            Kurzwert(zahl(s.schritte), null, stringResource(R.string.ks_d_schritte), Modifier.weight(1f), bewegung)
            // Wasser nur mit Drinktervall auf der Uhr (UhrApps): sonst stuende hier "0 l" fuer jeden.
            if (s.mitDrinktervall) Kurzwert(liter(s.wasserMl), "l", stringResource(R.string.ks_d_wasser), Modifier.weight(1f), wasser)
        }
    }
}

@Composable
private fun Kurzwert(zahl: String, einheit: String?, name: String, modifier: Modifier, onClick: (() -> Unit)?) {
    Column(
        modifier.clip(RoundedCornerShape(10.dp)).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(zahlMitEinheit(zahl, einheit, 24, 14), lineHeight = 30.sp, maxLines = 1)
        Text(name, fontSize = 13.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Ein Ereignis des Tages auf dem Strahl. */
private data class Ereignis(val zeit: Instant, val farbe: Color, val titel: String, val unter: String?, val onClick: (() -> Unit)?)

/**
 * Der Tag rueckwaerts: Jetzt, dann das Neueste zuerst, die Nacht zuletzt.
 * Getraenke und Praeparate nur mit ihren Uhr-Apps (UhrApps), wie ueberall.
 */
@Composable
internal fun Tagesstrahl(
    s: GesundheitsStand,
    training: TrainingEintrag?,
    wege: Gesundheitswege,
    koffeinDazu: () -> Unit,
    zeigePraeparate: () -> Unit,
) {
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    val heute = LocalDate.now()
    val jetzt = Instant.now()
    val ereignisse = buildList {
        if (training != null && training.sitzung.startTime.atZone(zone).toLocalDate() == heute) {
            val unter = listOfNotNull(if (training.meter > 0) kmText(training.meter) else null, dauerText(training.minuten)).joinToString(" · ")
            add(Ereignis(training.sitzung.startTime, training.sport.ton(), trainingsName(training), unter) { wege.training(training.id) })
        }
        if (s.mitDrinktervall) {
            val glaeser = s.glaeser.sortedDescending()
            glaeser.forEachIndexed { i, g ->
                // Nur beim neuesten Glas der Stand des Tages - sonst wiederholte er sich.
                val unter = if (i == 0) stringResource(R.string.ks_d_heute_liter, liter(s.wasserMl)) else null
                add(Ereignis(g, FLAECHE_WASSER, stringResource(R.string.ks_d_glas), unter) { wege.trend("ernaehrung") })
            }
            s.eintraege.filter { !it.praeparat }.forEach { e ->
                add(Ereignis(e.zeit, FLAECHE_KOFFEIN, e.name, e.koffeinMg?.let { "${it.roundToInt()} mg" }) { wege.trend("ernaehrung") })
            }
        }
        if (s.mitSupCycle) {
            // Was SupCycle in einem Zug abhakte, steht als eine Zeile.
            s.eintraege.filter { it.praeparat }.groupBy { it.zeit.epochSecond / 60 }.values.forEach { gruppe ->
                add(Ereignis(gruppe.first().zeit, FLAECHE_PRAEPARAT, gruppe.joinToString(", ") { it.name }, null, zeigePraeparate))
            }
        }
    }.sortedByDescending { it.zeit }

    Kasten {
        Kopf(stringResource(R.string.ks_d_neueste_zuerst))
        Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 10.dp)) {
            Jetztblock(s, jetzt, koffeinDazu, zeigePraeparate, weiter = ereignisse.isNotEmpty() || s.nacht != null)
            ereignisse.forEachIndexed { i, e ->
                Strahlzeile(
                    uhrzeit(context, e.zeit), e.farbe, e.titel, e.unter,
                    weiter = i < ereignisse.lastIndex || s.nacht != null, onClick = e.onClick,
                )
            }
            val hinweis = herzHinweis(s)
            s.nacht?.let { n -> Nachtblock(n, hinweis) { wege.nacht(n.beginnUtc) } }
        }
    }
}

/** Die Zeitspalte und der Strahl links, der Inhalt rechts - eine Zeile des Strahls. */
@Composable
private fun Strahl(
    zeit: @Composable () -> Unit,
    strahl: @Composable () -> Unit,
    onClick: (() -> Unit)? = null,
    inhalt: @Composable () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Box(Modifier.width(SPALTE_ZEIT).fillMaxHeight()) { zeit() }
        Box(Modifier.width(SPALTE_STRAHL).fillMaxHeight()) { strahl() }
        Box(Modifier.weight(1f).padding(start = 6.dp)) { inhalt() }
    }
}

@Composable
private fun Linie(oben: Dp = 0.dp, gestrichelt: Boolean = false) {
    val farbe = MaterialTheme.colorScheme.outlineVariant
    Canvas(Modifier.fillMaxHeight().width(SPALTE_STRAHL).padding(top = oben)) {
        val x = 11.dp.toPx()
        drawLine(
            farbe, Offset(x, 0f), Offset(x, size.height), 2.dp.toPx(),
            pathEffect = if (gestrichelt) PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())) else null,
        )
    }
}

@Composable
private fun Strahlzeile(zeit: String, farbe: Color, titel: String, unter: String?, weiter: Boolean, onClick: (() -> Unit)?) {
    val grau = MaterialTheme.colorScheme.onSurfaceVariant
    val grund = MaterialTheme.colorScheme.surfaceContainer
    Strahl(
        zeit = { Text(zeit, Modifier.padding(top = 2.dp), fontSize = 13.sp, color = grau, maxLines = 1, softWrap = false) },
        strahl = {
            if (weiter) Linie()
            Box(
                Modifier.offset(5.dp, 5.dp).size(12.dp).border(BorderStroke(3.dp, grund), CircleShape)
                    .background(farbe, CircleShape),
            )
        },
        onClick = onClick,
    ) {
        Column(Modifier.padding(bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(titel, fontSize = 15.sp, lineHeight = 20.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (unter != null) Text(unter, fontSize = 13.sp, lineHeight = 18.sp, color = grau)
        }
    }
}

/** Oben auf dem Strahl: wie es gerade steht, mit den Knoepfen fuer das, was man jetzt tut. */
@Composable
private fun Jetztblock(s: GesundheitsStand, jetzt: Instant, koffeinDazu: () -> Unit, zeigePraeparate: () -> Unit, weiter: Boolean) {
    val context = LocalContext.current
    val gruen = MaterialTheme.colorScheme.primary
    val grau = MaterialTheme.colorScheme.onSurfaceVariant
    val grund = MaterialTheme.colorScheme.surfaceContainer
    Strahl(
        zeit = { Text(uhrzeit(context, jetzt), Modifier.padding(top = 3.dp), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = gruen, maxLines = 1, softWrap = false) },
        strahl = {
            if (weiter) Linie(oben = 18.dp)
            Box(Modifier.offset(2.dp, 3.dp).size(18.dp).border(2.dp, gruen, CircleShape).background(grund, CircleShape), contentAlignment = Alignment.Center) {
                Box(Modifier.size(8.dp).background(gruen, CircleShape))
            }
        },
    ) {
        Column(Modifier.padding(bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.ks_d_jetzt), fontSize = 12.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium, color = gruen)
            val letzter = s.puls.maxByOrNull { it.first }
            if (letzter != null && Gesundheitsrechnung.pulsFrisch(letzter.first, jetzt.epochSecond)) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${letzter.second}", fontSize = 30.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.ks_d_puls), Modifier.padding(bottom = 4.dp), fontSize = 14.sp, color = grau)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(stringResource(R.string.ks_d_bewegt, kmText(s.meter), s.aktivMinuten), fontSize = 14.sp, lineHeight = 19.sp)
                if (s.mitDrinktervall) {
                    val aktiv = Gesundheitsrechnung.koffeinAktiv(s.koffein, jetzt).roundToInt()
                    if (aktiv > 0) {
                        val bett = Gesundheitsrechnung.bettzeit(s.nacht?.einschlafen, jetzt, ZoneId.systemDefault())
                        val dann = bett?.let { Gesundheitsrechnung.koffeinAktiv(s.koffein, it).roundToInt() }
                        Zweiteilig(stringResource(R.string.ks_d_koffein, aktiv), dann?.let { stringResource(R.string.ks_d_koffein_bett, it) })
                    }
                }
                val stand = s.supplemente?.takeIf { s.mitSupCycle }
                if (stand != null) {
                    val platz = stringResource(R.string.ks_s_platz_n)
                    val z = Gesundheitsrechnung.praeparatzeile(stand.heute { String.format(platz, it) })
                    if (z.faellig > 0) {
                        val offen = if (z.lage == Praeparatlage.OFFEN || z.lage == Praeparatlage.FAELLIG) z.namen.firstOrNull() else null
                        Zweiteilig(stringResource(R.string.ks_d_praeparate, z.genommen, z.faellig), offen?.let { stringResource(R.string.ks_d_offen, it) })
                    }
                }
            }
            val praeparatOffen = s.mitSupCycle && s.supplemente?.heute { it.toString() }?.any { !it.genommen } == true
            if (s.mitDrinktervall || praeparatOffen) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (s.mitDrinktervall) Knopf(stringResource(R.string.ks_d_koffein_dazu), koffeinDazu)
                    if (praeparatOffen) Knopf(stringResource(R.string.ks_h_praeparate_n), zeigePraeparate)
                }
            }
        }
    }
}

/** Vorn der Wert, hinten grau der Zusatz - in einem Text, damit er umbricht statt abzuschneiden. */
@Composable
private fun Zweiteilig(vorn: String, hinten: String?) {
    val grau = MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        buildAnnotatedString {
            append(vorn)
            if (hinten != null) withStyle(SpanStyle(color = grau)) { append(" $hinten") }
        },
        fontSize = 14.sp, lineHeight = 19.sp,
    )
}

@Composable
private fun Knopf(text: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.heightIn(min = 44.dp)) { Text(text, maxLines = 1) }
}

/** Ganz unten: die Nacht, oben aufgewacht, unten eingeschlafen - die Zeit laeuft nach unten zurueck. */
@Composable
private fun Nachtblock(n: Nacht, hinweis: String?, oeffne: () -> Unit) {
    val context = LocalContext.current
    val grau = MaterialTheme.colorScheme.onSurfaceVariant
    Strahl(
        zeit = {
            Column(Modifier.fillMaxHeight().padding(top = 2.dp, bottom = 4.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Text(uhrzeit(context, n.aufwachen), fontSize = 13.sp, color = grau, maxLines = 1, softWrap = false)
                Text(uhrzeit(context, n.einschlafen), fontSize = 13.sp, color = grau, maxLines = 1, softWrap = false)
            }
        },
        strahl = {
            Box(Modifier.padding(start = 8.dp, top = 4.dp, bottom = 4.dp).width(6.dp).fillMaxHeight().background(PHASENFARBEN[2], RoundedCornerShape(3.dp)))
        },
        onClick = oeffne,
    ) {
        Column(Modifier.padding(bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.ks_d_aufgewacht), fontSize = 15.sp, lineHeight = 20.sp)
            if (n.mitPhasen) {
                val phasen = listOf(3 to R.string.ks_h_rem, 1 to R.string.ks_h_leicht, 2 to R.string.ks_h_tief)
                Anteilsbalken(phasen.map { (p, _) -> n.phasen[p] to PHASENFARBEN[p] })
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    phasen.forEach { (p, name) ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            Box(Modifier.size(8.dp).background(PHASENFARBEN[p], CircleShape))
                            Text(stringResource(name) + " " + stundenMinuten(n.phasen[p]), fontSize = 12.sp, lineHeight = 16.sp, color = grau)
                        }
                    }
                }
            }
            if (n.ruhepuls != null && n.hrv != null) {
                // Mit dem Urteil gegen die letzten Naechte, wenn es eins gibt.
                Text(stringResource(R.string.ks_d_ruhe_hrv, n.ruhepuls, n.hrv) + (hinweis?.let { " · $it" } ?: ""), fontSize = 13.sp, lineHeight = 18.sp, color = grau)
            }
            Text(stringResource(R.string.ks_d_eingeschlafen), fontSize = 15.sp, lineHeight = 20.sp)
        }
    }
}

/** Schritte der Woche als Saeulen, das Ziel gestrichelt. */
@Composable
internal fun Schrittwoche(s: GesundheitsStand, trend: () -> Unit) {
    val grau = MaterialTheme.colorScheme.onSurfaceVariant
    val leer = MaterialTheme.colorScheme.surfaceVariant
    val heute = LocalDate.now()
    Kasten {
        Kopf(stringResource(R.string.ks_d_schritte_woche), "· " + stringResource(R.string.ks_h_ziel_zahl, zahl(SCHRITTZIEL)), stringResource(R.string.ks_h_trends), trend)
        Row(Modifier.fillMaxWidth().clickable(onClick = trend).padding(start = 10.dp, end = 10.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val spitze = maxOf(SCHRITTZIEL, s.wocheSchritte.maxOfOrNull { it.second } ?: 0L).toFloat()
            s.wocheSchritte.forEach { (tag, schritte) ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (schritte > 0) String.format(Locale.getDefault(), "%.1fk", schritte / 1000.0) else "", fontSize = 11.sp, lineHeight = 14.sp, color = grau, maxLines = 1)
                    val gruen = if (schritte >= SCHRITTZIEL) FLAECHE_BEWEGUNG else FLAECHE_BEWEGUNG.copy(alpha = 0.75f)
                    Canvas(Modifier.fillMaxWidth().height(64.dp)) {
                        drawRoundRect(leer, Offset.Zero, size, CornerRadius(4.dp.toPx()))
                        val h = size.height * (schritte / spitze)
                        if (h > 0) drawRoundRect(gruen, Offset(0f, size.height - h), Size(size.width, h), CornerRadius(4.dp.toPx()))
                        val ziel = size.height * (1 - SCHRITTZIEL / spitze)
                        drawLine(grau, Offset(0f, ziel), Offset(size.width, ziel), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 4.dp.toPx())))
                    }
                    Text(
                        tag.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()).take(2), fontSize = 12.sp, lineHeight = 16.sp,
                        fontWeight = if (tag == heute) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (tag == heute) MaterialTheme.colorScheme.onSurface else grau,
                    )
                }
            }
        }
    }
}

/** Die Einzelheiten als Zeilen - jede fuehrt eine Ebene tiefer. */
@Composable
internal fun Detailkarte(
    s: GesundheitsStand,
    wege: Gesundheitswege,
    zeigeTrends: () -> Unit,
    zeigePraeparate: () -> Unit,
) {
    val context = LocalContext.current
    val jetzt = Instant.now()
    val zeilen = buildList<@Composable () -> Unit> {
        s.nacht?.let { n ->
            add {
                val tief = if (n.mitPhasen) ", " + stringResource(R.string.ks_d_tief_kurz, stundenMinuten(n.phasen[2])) else ""
                Punktzeile(
                    PHASENFARBEN[2], stringResource(R.string.ks_d_letzte_nacht),
                    stringResource(R.string.ks_h_zeitraum, uhrzeit(context, n.einschlafen), uhrzeit(context, n.aufwachen)) + tief,
                    stundenMinuten(n.schlafMinuten), "h",
                ) { wege.nacht(n.beginnUtc) }
            }
        }
        add {
            val letzter = s.puls.maxByOrNull { it.first }
            val n = s.nacht
            val unter = listOfNotNull(
                n?.ruhepuls?.let { stringResource(R.string.ks_d_ruhe, it) },
                n?.hrv?.let { "HRV $it ms" },
            ).joinToString(", ").ifEmpty { null }
            Punktzeile(FLAECHE_HERZ, stringResource(R.string.ks_d_herz), unter, letzter?.second?.toString(), if (letzter != null) "bpm" else null, wege.herz)
        }
        if (s.spo2.isNotEmpty()) add {
            val mittel = s.spo2.map { it.second }.average().roundToInt()
            Punktzeile(
                Color(0xFF2E7C8A), stringResource(R.string.ks_d_spo2),
                stringResource(R.string.ks_d_spo2_tiefster, s.spo2.minOf { it.second }), "$mittel", "%", wege.herz,
            )
        }
        add {
            Punktzeile(
                FLAECHE_BEWEGUNG, stringResource(R.string.ks_d_bewegung),
                stringResource(R.string.ks_d_bewegung_unter, kmText(s.meter), s.aktivMinuten, s.kcal), zahl(s.schritte), null,
            ) { wege.trend("bewegung") }
        }
        if (s.mitDrinktervall) add {
            val ziel = s.wasserZiel
            val unter = if (ziel != null) stringResource(R.string.ks_d_glaeser_von, s.glaeser.size, ziel.glaeser) else stringResource(R.string.ks_h_kein_ziel)
            Punktzeile(FLAECHE_WASSER, stringResource(R.string.ks_d_wasser), unter, liter(s.wasserMl), "l") { wege.trend("ernaehrung") }
        }
        val stand = s.supplemente?.takeIf { s.mitSupCycle }
        if (stand != null) add {
            val platz = stringResource(R.string.ks_s_platz_n)
            val z = Gesundheitsrechnung.praeparatzeile(stand.heute { String.format(platz, it) })
            val unter = when (z.lage) {
                Praeparatlage.ALLE -> stringResource(R.string.ks_h_p_alle)
                Praeparatlage.KEINE -> stringResource(R.string.ks_h_p_keine)
                else -> z.namen.firstOrNull()?.let { stringResource(R.string.ks_d_noch_offen, it) }
            }
            Punktzeile(FLAECHE_PRAEPARAT, stringResource(R.string.ks_h_praeparate_n), unter, "${z.genommen}", stringResource(R.string.ks_h_von_n, z.faellig), zeigePraeparate)
        }
        if (s.mitDrinktervall) add {
            val heute = LocalDate.now()
            val zone = ZoneId.systemDefault()
            val dosen = s.koffein.filter { it.first.atZone(zone).toLocalDate() == heute }
            val unter = dosen.maxByOrNull { it.first }?.let {
                stringResource(R.string.ks_d_koffein_unter, dosen.sumOf { d -> d.second }.roundToInt(), uhrzeit(context, it.first))
            } ?: stringResource(R.string.ks_h_koffein_keins)
            Punktzeile(FLAECHE_KOFFEIN, stringResource(R.string.ks_h_koffein_n), unter, "${Gesundheitsrechnung.koffeinAktiv(s.koffein, jetzt).roundToInt()}", "mg") { wege.trend("ernaehrung") }
        }
    }
    Kasten {
        Kopf(stringResource(R.string.ks_d_details), link = stringResource(R.string.ks_h_trends), onLink = zeigeTrends)
        zeilen.forEachIndexed { i, z ->
            if (i > 0) Trenner(14.dp)
            z()
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun Punktzeile(farbe: Color, titel: String, unter: String?, zahl: String?, einheit: String?, onClick: (() -> Unit)?) {
    Zeile(
        titel, unter, zahl, einheit, minHoehe = 60.dp,
        zeichen = { Box(Modifier.size(10.dp).background(farbe, CircleShape)) },
        onClick = onClick,
    )
}
