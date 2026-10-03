package coredevices.pebble.kiesel.ansicht

import android.widget.Toast
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.NutritionRecord
import coredevices.pebble.kiesel.GesundheitWidget
import coredevices.pebble.kiesel.Akteneintraege
import coredevices.pebble.kiesel.Kaffeesorten
import kotlinx.coroutines.launch
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coredevices.pebble.R
import coredevices.pebble.kiesel.GesundheitsStand
import coredevices.pebble.kiesel.Formate
import coredevices.pebble.kiesel.SupCycle
import java.time.Instant
import java.time.LocalTime
import kotlin.math.roundToInt

/** Was ein langer Druck mit einem Ereignis tun kann - nur bei Boulders eigenen Eintraegen. */
internal sealed class Bearbeitung {
    abstract val titel: String
    abstract val zeit: Instant
    data class Getraenk(override val titel: String, override val zeit: Instant, val id: String) : Bearbeitung()
    data class Glas(override val titel: String, override val zeit: Instant, val id: String) : Bearbeitung()
    /** [kennungen]: an ihnen erfaehrt SupCycles Stand, welche Plaetze nicht mehr als genommen zaehlen. */
    data class Praeparate(override val titel: String, override val zeit: Instant, val ids: List<String>, val kennungen: List<String?>) : Bearbeitung()
}

/**
 * Das Blatt nach langem Druck: ein Getraenk umstellen oder loeschen, ein Glas
 * oder Praeparate loeschen. Falsch gewaehlt ist auf der Uhr schnell - hier
 * wird es richtig.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun Bearbeitungsblatt(b: Bearbeitung, onDismiss: () -> Unit, fertig: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val fehler = stringResource(R.string.ks_b_fehler)
    fun ausfuehren(tat: suspend () -> Boolean) {
        scope.launch {
            if (!tat()) Toast.makeText(context, fehler, Toast.LENGTH_LONG).show()
            GesundheitWidget.stosseAn(context)
            fertig()
        }
    }
    // Gleich ganz offen: halb offen lagen Speichern und Loeschen unter dem Rand.
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            // Mit dem Tag, wenn es nicht heute war: auch ein Eintrag von gestern laesst sich hier aendern.
            // Das Blatt gehoert zum Strahl von heute, darum "9:14 PM" statt "today, 9:14 PM".
            Kopf(b.titel, Formate.zeitpunkt(context, b.zeit, klein = true, heuteOhneTag = true))
            val start = remember(b) { (b as? Bearbeitung.Getraenk)?.let { Kaffeesorten.erkenne(context, it.titel) } }
            // Ein eigenes Getraenk der Uhr ("Mate") ist keine der Sorten - es laesst sich nur loeschen, nicht zu Kaffee machen.
            if (b is Bearbeitung.Getraenk && start != null) {
                var sorte by remember(b) { mutableIntStateOf(start.sorte) }
                var milch by remember(b) { mutableStateOf(start.milch) }
                var zucker by remember(b) { mutableStateOf(start.zucker) }
                var koffeinfrei by remember(b) { mutableStateOf(start.koffeinfrei) }
                val sorten = listOf(
                    Kaffeesorten.ESPRESSO to R.string.ks_h_espresso, Kaffeesorten.KAFFEE to R.string.ks_h_kaffee,
                    Kaffeesorten.TEE to R.string.ks_h_tee, Kaffeesorten.ENERGY to R.string.ks_h_energy_drink,
                )
                sorten.forEach { (k, name) ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 52.dp).selectable(sorte == k, role = Role.RadioButton) { sorte = k }.padding(horizontal = INNEN),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = sorte == k, onClick = null)
                        Text(stringResource(name), Modifier.padding(start = 12.dp), fontSize = 16.sp)
                    }
                }
                Trenner(INNEN)
                // Milch und koffeinfrei zu allem ausser dem Energy-Drink - wie auf der Uhr.
                val mitMilch = sorte != Kaffeesorten.ENERGY
                if (mitMilch) Haken(stringResource(R.string.ks_b_koffeinfrei), koffeinfrei) { koffeinfrei = it }
                if (mitMilch) Haken(stringResource(R.string.ks_b_milch), milch) { milch = it }
                Haken(stringResource(R.string.ks_b_zucker), zucker) { zucker = it }
                val art = Kaffeesorten.art(sorte, milch && mitMilch, zucker, koffeinfrei && mitMilch)
                val p = Kaffeesorten.portion(art)
                if (p != null) Box(Modifier.padding(horizontal = INNEN, vertical = 8.dp)) {
                    Unterzeile(stringResource(R.string.ks_b_werte, p.koffeinMg.roundToInt(), p.kcal.roundToInt()))
                }
                // Unveraendert gespeichert schriebe die Werte der Sorte ueber die eingetragenen.
                val geaendert = art != Kaffeesorten.art(start.sorte, start.milch, start.zucker, start.koffeinfrei)
                Row(Modifier.fillMaxWidth().padding(horizontal = INNEN, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { ausfuehren { Akteneintraege.loesche(context, NutritionRecord::class, listOf(b.id)) } }) {
                        Text(stringResource(R.string.ks_b_loeschen), color = MaterialTheme.colorScheme.error)
                    }
                    Spacer(Modifier.weight(1f))
                    Button(enabled = geaendert, onClick = { ausfuehren { Akteneintraege.aendereGetraenk(context, b.id, art) } }) {
                        Text(stringResource(R.string.ks_b_speichern))
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth().padding(horizontal = INNEN, vertical = 8.dp)) {
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = {
                            ausfuehren {
                                when (b) {
                                    is Bearbeitung.Glas -> Akteneintraege.loesche(context, HydrationRecord::class, listOf(b.id))
                                    // Liste und Quote zaehlen es dann nicht mehr, die Uhr nimmt den Haken zurueck (verwirf).
                                    is Bearbeitung.Praeparate -> Akteneintraege.loesche(context, NutritionRecord::class, b.ids)
                                        .also { if (it) SupCycle.verwirf(context, b.kennungen) }
                                    is Bearbeitung.Getraenk -> Akteneintraege.loesche(context, NutritionRecord::class, listOf(b.id))
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                    ) { Text(stringResource(R.string.ks_b_loeschen)) }
                }
            }
        }
    }
}

@Composable
private fun Haken(text: String, an: Boolean, setze: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).toggleable(an, role = Role.Checkbox, onValueChange = setze).padding(horizontal = INNEN),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = an, onCheckedChange = null)
        Text(text, Modifier.padding(start = 12.dp), fontSize = 16.sp)
    }
}

/**
 * Koffein von Hand eintragen - darunter, was gestern spaet eingetragen
 * wurde: der Strahl zeigt nur heute, und ein Fehleintrag um 23:50 soll sich
 * auch nach Mitternacht noch aendern lassen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun KoffeinBlatt(s: GesundheitsStand, onDismiss: () -> Unit, bearbeite: (Bearbeitung) -> Unit, trinke: (Int) -> Unit) {
    val context = LocalContext.current
    // Dieselben Portionen wie von der Uhr: eine Tasse hat ueberall gleich viel.
    val getraenke = Kaffeesorten.WAEHLBAR.mapNotNull { sorte -> Kaffeesorten.portion(sorte)?.let { sorte to it } }
    val glas = stringResource(R.string.ks_d_glas)
    val gestern = (
        s.gestern.map { e -> Bearbeitung.Getraenk(e.name, e.zeit, e.id) to e.koffeinMg?.let { Formate.menge(it, "mg") } } +
            s.gesternGlaeser.map { g -> Bearbeitung.Glas(glas, g.zeit, g.id) to Formate.menge(g.ml.toLong(), "ml") }
        ).sortedBy { it.first.zeit }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            Kopf(stringResource(R.string.ks_h_koffein_dazu))
            getraenke.forEachIndexed { i, (sorte, p) ->
                if (i > 0) Trenner()
                val name = Kaffeesorten.name(context, p)
                val mg = p.koffeinMg.roundToInt()
                Zeile(
                    name, null, Formate.zahl(mg.toLong()), "mg", zeichen = { Tonkreis(Zeichen.tasse, Farben.koffein, 0.30f) },
                    beschreibung = stringResource(R.string.ks_h_getraenk, name, mg), onClick = { trinke(sorte) }, chevron = false,
                )
            }
            if (gestern.isNotEmpty()) {
                // Dasselbe "Yesterday" wie jede Zeitangabe der App (Formate).
                Kopf(stringResource(R.string.ks_fmt_gestern))
                gestern.forEachIndexed { i, (b, wert) ->
                    if (i > 0) Trenner()
                    Zeile(
                        b.titel, Formate.uhrzeit(context, b.zeit), wert?.zahl, wert?.einheit,
                        zeichen = {
                            if (b is Bearbeitung.Glas) Tonkreis(Zeichen.wasser, Farben.wasser)
                            else Tonkreis(Zeichen.tasse, Farben.koffein, 0.30f)
                        },
                        onClick = { bearbeite(b) }, chevron = false,
                    )
                }
            }
        }
    }
}

/** Die Praeparate des Tages, abgehakt oder offen - die Liste, die frueher auf der Karte stand. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PraeparatBlatt(s: GesundheitsStand, onDismiss: () -> Unit) {
    val stand = s.supplemente ?: return
    val context = LocalContext.current
    val rand = MaterialTheme.colorScheme.outline
    val platz = stringResource(R.string.ks_s_platz_n)
    val liste = stand.heute { String.format(platz, it) }
    val jetzt = LocalTime.now()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            // Der Zusatz ohne "·": den Trenner setzt der Kopf.
            Kopf(
                stringResource(R.string.ks_h_praeparate_heute),
                Formate.zahl(liste.count { it.genommen }.toLong()) + " " + stringResource(R.string.ks_h_von_n, liste.size),
            )
            if (liste.isEmpty()) Leerzeile(stringResource(R.string.ks_h_p_keine))
            liste.forEachIndexed { i, e ->
                if (i > 0) Trenner()
                // Die Planzeit darunter - in Warnfarbe erst, wenn sie ohne Haken verstrichen ist.
                val versaeumt = !e.genommen && e.zeit?.let { !jetzt.isBefore(it) } == true
                Zeile(
                    e.name,
                    unter = e.zeit?.let { Formate.uhrzeit(context, it) },
                    unterFarbe = if (versaeumt) Farben.warnung.ton() else null,
                    zeichen = {
                        if (e.genommen) Tonkreis(Zeichen.haken, Farben.praeparat, 0.18f)
                        else Box(Modifier.size(36.dp).padding(9.dp).border(1.6.dp, rand, CircleShape))
                    },
                )
            }
        }
    }
}
