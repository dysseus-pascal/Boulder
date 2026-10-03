package coredevices.pebble.kiesel.ansicht

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coredevices.pebble.R
import coredevices.pebble.kiesel.GesundheitsStand
import coredevices.pebble.kiesel.Gesundheitsrechnung
import coredevices.pebble.kiesel.Praeparatlage
import coredevices.pebble.kiesel.Formate
import coredevices.pebble.kiesel.praeparatzeile
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * Herz samt Blutdruck, Koerper, Wasser, Praeparate, Koffein als Zeilen. Das
 * Chevron fuehrt zu Detail oder Trend; Eintragen hat sein eigenes "+". Die
 * Koerperzeile liest selbst und nicht mit [s] (siehe [koerperzeile]).
 */
@Composable
internal fun Koerperliste(
    s: GesundheitsStand,
    wege: Gesundheitswege,
    zeigeKoffein: () -> Unit,
    zeigePraeparate: () -> Unit,
) {
    val context = LocalContext.current
    val jetzt = Instant.now()
    val zone = ZoneId.systemDefault()
    val zeilen = buildList<@Composable () -> Unit> {
        add {
            val ruhe = s.nacht?.ruhepuls
            val tag = tagespuls(s)
            val letzter = s.puls.maxByOrNull { it.first }
            // Die Zahl rechts steht nur einmal da, links steht, was sie ist: "Resting
            // 55 · 55 bpm" wiederholte sie. Ohne Tagesschnitt der letzte Wert vor dem
            // Ruhepuls - den nennt der Nachtblock im Strahl schon. Der Ersatztext nur ohne jeden Puls.
            // Die Seite ist der Tag von heute: "Last 6:58 AM", erst ein anderer Tag nennt sich.
            val (wert, puls) = when {
                tag != null -> tag.wachpuls to tag.teile
                letzter != null -> letzter.second to listOf(
                    stringResource(R.string.ks_d_zuletzt, Formate.zeitpunkt(context, Instant.ofEpochSecond(letzter.first), klein = true, heuteOhneTag = true)),
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
        koerperzeile(wege.koerper)?.let { add(it) }
        if (s.mitDrinktervall) add {
            // Gezaehlt wie auf der Uhr: nur ihre Glaeser; die Liter umfassen alles Wasser des Tages.
            val letztes = s.glaeser.maxOrNull()
            val unter = if (letztes == null) stringResource(R.string.ks_h_kein_glas)
            else pluralStringResource(R.plurals.ks_f_glaeser, s.glaeser.size, s.glaeser.size, Formate.uhrzeit(context, letztes))
            Zeile(
                stringResource(R.string.ks_d_wasser), unter, Formate.liter(s.wasserMl), "l",
                zeichen = { Tonkreis(Zeichen.wasser, Farben.wasser) },
            ) { wege.trend("ernaehrung") }
        }
        val stand = s.supplemente?.takeIf { s.mitSupCycle }
        if (stand != null) add {
            val platz = stringResource(R.string.ks_s_platz_n)
            val z = Gesundheitsrechnung.praeparatzeile(stand.heute { String.format(platz, it) })
            val (eins, zwei, mehr) = if (z.lage == Praeparatlage.OFFEN) Triple(R.string.ks_h_p_offen_1, R.string.ks_h_p_offen_2, R.string.ks_h_p_offen_n)
            else Triple(R.string.ks_h_p_faellig_1, R.string.ks_h_p_faellig_2, R.string.ks_h_p_faellig_n)
            val unter = when {
                z.lage == Praeparatlage.ALLE -> stringResource(R.string.ks_h_p_alle)
                z.lage == Praeparatlage.KEINE -> stringResource(R.string.ks_h_p_keine)
                z.weitere > 0 -> stringResource(mehr, z.namen[0], z.namen[1], z.weitere)
                z.namen.size == 2 -> stringResource(zwei, z.namen[0], z.namen[1])
                else -> z.namen.firstOrNull()?.let { stringResource(eins, it) }
            }
            Zeile(
                stringResource(R.string.ks_h_praeparate_n), unter, Formate.zahl(z.genommen.toLong()), stringResource(R.string.ks_h_von_n, z.faellig),
                zeichen = { Tonkreis(Zeichen.kapsel, Farben.praeparat, 0.18f) },
                // Die Warnfarbe nur fuer Versaeumtes: was seine Planzeit hinter sich hat
                // (ohne Plan: was nach dem ersten Haken liegen blieb).
                unterFarbe = if (z.lage == Praeparatlage.OFFEN) Farben.warnung.ton() else null,
                onClick = zeigePraeparate,
            )
        }
        if (s.mitDrinktervall) add {
            // Gross die Menge des Tages wie im Widget; was davon noch wirkt, steht benannt darunter.
            // Was zur Schlafenszeit noch wirkt, steht nur im Strahl bei "Bedtime" - hier
            // doppelt brach die Zeile neben dem "+" auf zwei Zeilen um.
            val heuteMg = Gesundheitsrechnung.koffeinHeute(s.koffein, LocalDate.now(), zone).roundToInt()
            val aktiv = Gesundheitsrechnung.koffeinAktiv(s.koffein, jetzt).roundToInt()
            val unter = if (aktiv <= 0) stringResource(R.string.ks_h_koffein_keins)
            else stringResource(R.string.ks_f_koffein_aktiv, aktiv)
            val dazu = stringResource(R.string.ks_h_koffein_dazu)
            Zeile(
                stringResource(R.string.ks_h_koffein_n), unter, Formate.zahl(heuteMg.toLong()), "mg",
                zeichen = { Tonkreis(Zeichen.tasse, Farben.koffein, 0.30f) },
                rechts = {
                    IconButton(onClick = zeigeKoffein, modifier = Modifier.size(48.dp)) {
                        Icon(Zeichen.plus, dazu, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                },
                onClick = { wege.trend("ernaehrung") },
            )
        }
    }
    Kasten {
        zeilen.forEachIndexed { i, z ->
            if (i > 0) Trenner()
            z()
        }
        Spacer(Modifier.height(4.dp))
    }
}
