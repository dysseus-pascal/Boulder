package coredevices.pebble.kiesel.ansicht

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coredevices.pebble.R
import coredevices.pebble.kiesel.GesundheitsStand
import coredevices.pebble.kiesel.Gesundheitsrechnung
import coredevices.pebble.kiesel.Praeparatlage
import coredevices.pebble.kiesel.Formate
import coredevices.pebble.kiesel.praeparatzeile
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * Heart & Body (mit Blutdruck), Koerper, Water und Stack als Zeilen. Das
 * Chevron fuehrt eine Ebene tiefer. Die Koerperzeile liest selbst und kommt
 * darum fertig herein ([koerper], siehe [koerperzeile]) - null ohne Waage.
 */
@Composable
internal fun Koerperliste(
    s: GesundheitsStand,
    jetzt: Instant,
    wege: Gesundheitswege,
    koerper: (@Composable () -> Unit)?,
) {
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    val zeilen = buildList<@Composable () -> Unit> {
        add {
            val ruhe = s.nacht?.ruhepuls
            val tag = tagespuls(s)
            val letzter = s.puls.maxByOrNull { it.first }
            // Die Zahl rechts steht nur einmal da, links steht, was sie ist: "Resting
            // 55 · 55 bpm" wiederholte sie. Ohne Tagesschnitt der letzte Wert vor dem
            // Ruhepuls - den nennt der Schlaftext im Band schon. Der Ersatztext nur ohne jeden Puls.
            // Die Seite ist der Tag von heute: "Last 6:58 AM", erst ein anderer Tag nennt sich.
            val (wert, puls) = when {
                tag != null -> tag.wachpuls to tag.teile
                letzter != null -> letzter.second to listOf(
                    stringResource(R.string.ks_d_zuletzt, Formate.zeitpunkt(context, Instant.ofEpochSecond(letzter.first), jetzt, klein = true, heuteOhneTag = true)),
                )
                ruhe != null -> ruhe to listOf(stringResource(R.string.ks_d_ruhe))
                else -> null to listOf(stringResource(R.string.ks_h_kein_puls_kurz))
            }
            // Der Blutdruck hinten an, auch neben zwei Pulsteilen - selbst dann bleibt die Zeile auf 412 dp zweizeilig.
            val druck = Gesundheitsrechnung.blutdruckFuerHerzzeile(s.blutdruck, jetzt, zone)
                ?.let { stringResource(R.string.ks_d_blutdruck_kurz, it.oben, it.unten) }
            val unter = (puls + listOfNotNull(druck)).joinToString(" · ")
            Zeile(
                stringResource(R.string.ks_d_herz), unter, wert?.let { Formate.zahl(it.toLong()) }, if (wert != null) "bpm" else null,
                zeichen = { Tonkreis(Zeichen.herz, Farben.puls) }, onClick = wege.herz,
            )
        }
        // Nur wer sich wiegt, bekommt die Zeile - ohne Waage waere sie immer leer.
        koerper?.let { add(it) }
        if (s.mitDrinktervall) add {
            // Gezaehlt wie auf der Uhr: nur ihre Glaeser; die Liter umfassen alles Wasser des Tages.
            val letztes = s.glaeser.maxOrNull()
            val unter = if (letztes == null) stringResource(R.string.ks_h_kein_glas)
            else pluralStringResource(R.plurals.ks_f_glaeser, s.glaeser.size, s.glaeser.size, Formate.uhrzeit(context, letztes))
            Zeile(
                stringResource(R.string.ks_d_wasser), unter, Formate.liter(s.wasserMl), "l",
                zeichen = { Tonkreis(Zeichen.wasser, Farben.wasser) }, onClick = wege.wasser,
            )
        }
        // Stack fasst Praeparate und Koffein; ohne beide Uhr-Apps entfaellt die Zeile wie vorher ihre zwei.
        if (s.mitDrinktervall || (s.mitSupCycle && s.supplemente != null)) add { Stackzeile(s, jetzt, wege.stack) }
    }
    Kasten {
        zeilen.forEachIndexed { i, z ->
            if (i > 0) Trenner()
            z()
        }
        Spacer(Modifier.height(4.dp))
    }
}

/**
 * Die Zeile Stack: oben wie jede Zeile die offenen Praeparate (Bernstein
 * erst, wenn ihre Planzeit verstrichen ist), darunter drei Werte - wie
 * viele genommen sind, wie viel Koffein jetzt wirkt und zur Schlafenszeit
 * noch wirken wird. Was keine Daten hat, entfaellt: ohne SupCycle die
 * Praeparate, ohne Drinktervall oder ohne Koffein in den letzten 24 Stunden
 * das Koffein, nach der Schlafenszeit ihr Wert.
 */
@Composable
private fun Stackzeile(s: GesundheitsStand, jetzt: Instant, oeffne: () -> Unit) {
    val zone = ZoneId.systemDefault()
    val platz = stringResource(R.string.ks_s_platz_n)
    val stand = s.supplemente?.takeIf { s.mitSupCycle }
    val z = stand?.let { st -> Gesundheitsrechnung.praeparatzeile(st.heute { String.format(platz, it) }, jetzt.atZone(zone).toLocalTime()) }
    val unter = z?.let {
        val (eins, zwei, mehr) = if (it.lage == Praeparatlage.OFFEN) Triple(R.string.ks_h_p_offen_1, R.string.ks_h_p_offen_2, R.string.ks_h_p_offen_n)
        else Triple(R.string.ks_h_p_faellig_1, R.string.ks_h_p_faellig_2, R.string.ks_h_p_faellig_n)
        when {
            it.lage == Praeparatlage.ALLE -> stringResource(R.string.ks_h_p_alle)
            it.lage == Praeparatlage.KEINE -> stringResource(R.string.ks_h_p_keine)
            it.weitere > 0 -> stringResource(mehr, it.namen[0], it.namen[1], it.weitere)
            it.namen.size == 2 -> stringResource(zwei, it.namen[0], it.namen[1])
            else -> it.namen.firstOrNull()?.let { n -> stringResource(eins, n) }
        }
    }
    // Ohne Getraenk mit Koffein (s.koffein: die letzten 24 Stunden) gibt es keinen Koffeinwert:
    // "0 mg caffeine now" und "≈ 0 mg at bedtime" waeren dann Fuellung, keine Daten.
    val mitKoffein = s.mitDrinktervall && s.koffein.isNotEmpty()
    val aktiv = if (mitKoffein) Gesundheitsrechnung.koffeinAktiv(s.koffein, jetzt).roundToInt() else null
    // Die Schlafenszeit wie bisher im Strahl: nur solange sie kommt - danach hiesse "at bedtime" morgen.
    val bett = if (mitKoffein) Gesundheitsrechnung.bettzeit(s.nacht?.einschlafen, jetzt, zone) else null
    val bettMg = bett?.let { Gesundheitsrechnung.koffeinAktiv(s.koffein, it).roundToInt() }
    val titel = stringResource(R.string.ks_st_titel)
    val praeparate = stringResource(R.string.ks_st_praeparate)
    val koffeinJetzt = stringResource(R.string.ks_st_koffein_jetzt)
    val beimBett = stringResource(R.string.ks_st_bett)
    val von = z?.takeIf { it.faellig > 0 }?.let { stringResource(R.string.ks_h_von_n, it.faellig) }
    // Ungefaehr wie ueberall in der App (Formate.ungefaehr): die Halbwertszeit ist ein Mittel, kein Messwert.
    val ungefaehr = bettMg?.let { Formate.ungefaehr(Formate.zahl(it.toLong())) }
    // Fuer TalkBack ein Satz aus denselben Teilen, wie sie dastehen.
    val beschreibung = listOfNotNull(
        titel, unter,
        if (z != null && von != null) "${Formate.zahl(z.genommen.toLong())} $von $praeparate" else null,
        aktiv?.let { "${Formate.zahl(it.toLong())} mg $koffeinJetzt" },
        ungefaehr?.let { "$it mg $beimBett" },
    ).joinToString(", ")
    Column(
        Modifier.fillMaxWidth().clickable(onClick = oeffne).semantics(mergeDescendants = true) { contentDescription = beschreibung },
    ) {
        Zeile(
            titel, unter, zeichen = { Tonkreis(Zeichen.kapsel, Farben.praeparat, 0.18f) },
            // Die Warnfarbe nur fuer Versaeumtes: was seine Planzeit hinter sich hat
            // (ohne Plan: was nach dem ersten Haken liegen blieb).
            unterFarbe = if (z?.lage == Praeparatlage.OFFEN) Farben.warnung.ton() else null,
            minHoehe = 56.dp, chevron = true,
        )
        // Eingerueckt bis zum Text wie der Trenner; drei gleich breite Faecher wie im
        // Entwurf. Fehlt ein Wert, ruecken die anderen nach links, die Faecher bleiben gleich breit.
        val faecher = listOfNotNull<@Composable (Modifier) -> Unit>(
            if (z != null && von != null) { m -> Kennzahl(Formate.zahl(z.genommen.toLong()), von, praeparate, m) } else null,
            aktiv?.let { a -> { m -> Kennzahl(Formate.zahl(a.toLong()), "mg", koffeinJetzt, m) } },
            ungefaehr?.let { u -> { m -> Kennzahl(u, "mg", beimBett, m) } },
        )
        if (faecher.isNotEmpty()) Row(
            Modifier.fillMaxWidth().padding(start = 64.dp, end = 12.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            faecher.forEach { it(Modifier.weight(1f)) }
            repeat(3 - faecher.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}
