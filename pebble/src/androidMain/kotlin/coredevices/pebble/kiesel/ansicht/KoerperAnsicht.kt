package coredevices.pebble.kiesel.ansicht

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import coredevices.pebble.R
import coredevices.pebble.kiesel.Koerperdaten
import coredevices.pebble.kiesel.Koerperrechnung
import coredevices.pebble.kiesel.Koerperrechnung.FENSTER_TAGE
import coredevices.pebble.kiesel.Koerperstand
import coredevices.pebble.kiesel.Koerpertag
import coredevices.pebble.kiesel.Koerperzeile
import coredevices.pebble.kiesel.Zusammensetzung
import io.rebble.libpebblecommon.connection.LibPebble
import org.koin.compose.koinInject
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Die Koerperdaten aus der Akte, neu gelesen mit jedem [anlass]. Bis dahin
 * der zuletzt gelesene Stand, null nur beim allerersten Laden.
 */
@Composable
private fun koerperstand(anlass: Any?): Koerperstand? {
    val context = LocalContext.current
    val stand by produceState(Koerperdaten.zuletzt, anlass) { value = Koerperdaten.lies(context) }
    return stand
}

/** Gravels Schalter "Imperial Units" (Health-Einstellungen der Uhr): lb und Fuss statt kg und cm. */
@Composable
private fun imperial(): Boolean {
    val libPebble = koinInject<LibPebble>()
    val einstellungen by remember(libPebble) { libPebble.healthSettings }.collectAsState(null)
    return einstellungen?.imperialUnits == true
}

private fun masseEinheit(imperial: Boolean): String = if (imperial) "lb" else "kg"

/** "Today", "Yesterday", sonst das Datum - mit Jahr nur, wenn es ein anderes ist: die Groesse kann Jahre alt sein. */
private fun messtag(context: Context, tag: LocalDate, heute: LocalDate): String = when (tag) {
    heute -> context.getString(R.string.ks_kp_heute)
    heute.minusDays(1) -> context.getString(R.string.ks_kp_gestern)
    else -> DateUtils.formatDateTime(
        context, tag.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH,
    )
}

private fun monat(tag: LocalDate): String = tag.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())

/** "−0.6 kg in 30 days" - [einheit] samt Abstand, weil "%" ohne kommt. */
@Composable
private fun veraenderungText(betrag: Double, einheit: String): String =
    stringResource(R.string.ks_kp_in_tagen, Koerperrechnung.mitVorzeichen(betrag) + einheit, Koerperrechnung.VERAENDERUNG_TAGE.toInt())

/**
 * Die Zeile "Body" fuer die Liste der Gesundheitsseite, oder null: ohne
 * Gewicht und Fett der letzten 90 Tage gibt es keine.
 * Sie liest selbst, neu mit jedem [anlass] - der GesundheitsStand bleibt so,
 * wie das Widget ihn kennt.
 */
@Composable
internal fun koerperzeile(anlass: Any?, oeffne: () -> Unit): (@Composable () -> Unit)? {
    val stand = koerperstand(anlass)
    val imperial = imperial()
    val z = remember(stand) { stand?.let { Koerperrechnung.zeile(it, LocalDate.now(), ZoneId.systemDefault()) } } ?: return null
    return { KoerperZeile(z, imperial, oeffne) }
}

@Composable
private fun KoerperZeile(z: Koerperzeile, imperial: Boolean, oeffne: () -> Unit) {
    val context = LocalContext.current
    val masse = masseEinheit(imperial)
    val zahl = Koerperrechnung.eineStelle(if (z.istGewicht) Koerperrechnung.masse(z.wert.wert, imperial) else z.wert.wert)
    val unter = listOfNotNull(
        // Das Datum nur bei alten Messungen - das Gewicht von vor Wochen laese sich sonst als das heutige.
        if (z.alt) messtag(context, z.wert.tag, LocalDate.now()) else null,
        z.fett?.let { stringResource(R.string.ks_kp_fett_anteil, Koerperrechnung.eineStelle(it.wert)) },
        z.veraenderung?.let {
            if (z.istGewicht) veraenderungText(Koerperrechnung.masse(it, imperial), " $masse") else veraenderungText(it, "%")
        },
    ).joinToString(" · ").ifEmpty { null }
    Zeile(
        stringResource(R.string.ks_kp_zeile), unter, zahl, if (z.istGewicht) masse else "%",
        zeichen = { Tonkreis(WAAGE, FLAECHE_KOERPER, koerperTon()) }, onClick = oeffne,
    )
}

/**
 * "Body": Gewicht und Zusammensetzung, wie Waagen-Apps sie in die Akte
 * schreiben. Oben das letzte Gewicht mit seinem Verlauf, dann das Fett und
 * die uebrigen Werte - was nicht gemessen wurde, fehlt ganz.
 */
@Composable
internal fun KoerperAnsicht() {
    var neu by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { neu++ }
    val stand = koerperstand(neu)
    val imperial = imperial()
    if (stand == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val zone = ZoneId.systemDefault()
    val heute = LocalDate.now()
    val gewicht = remember(stand) { Koerperrechnung.tage(stand.gewicht, zone) }
    val fett = remember(stand) { Koerperrechnung.tage(stand.fett, zone) }
    val zusammen = remember(stand) { Koerperrechnung.zusammensetzung(stand, zone) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Spacer(Modifier.height(2.dp)) }
        if (stand.leer) item { Kasten { Zeile(stringResource(R.string.ks_kp_keine)) } }
        if (gewicht.isNotEmpty()) item { GewichtKarte(gewicht, heute, imperial) }
        // Ein einzelner Punkt ist kein Verlauf; der Wert steht ohnehin in der Zusammensetzung.
        if (fett.count { it.tag >= heute.minusDays(FENSTER_TAGE) } >= 2) item { FettKarte(fett, heute) }
        if (!zusammen.leer) item { ZusammensetzungKarte(zusammen, heute, imperial) }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

/**
 * Die Medianlinie aus allen Tagen, erst dann aufs Fenster beschnitten: so
 * kennt auch ihr linker Rand seine Nachbarn davor.
 */
private fun linieImFenster(tage: List<Koerpertag>, von: LocalDate, umrechnen: (Double) -> Double): List<List<Pair<LocalDate, Double>>> =
    Koerperrechnung.medianlinie(tage)
        .map { stueck -> stueck.filter { it.first >= von }.map { (tag, w) -> tag to umrechnen(w) } }
        .filter { it.isNotEmpty() }

@Composable
private fun GewichtKarte(tage: List<Koerpertag>, heute: LocalDate, imperial: Boolean) {
    val context = LocalContext.current
    val grau = MaterialTheme.colorScheme.onSurfaceVariant
    val einheit = masseEinheit(imperial)
    val letzter = tage.last()
    val von = heute.minusDays(FENSTER_TAGE)
    val umrechnen = { kg: Double -> Koerperrechnung.masse(kg, imperial) }
    Kasten {
        Kopf(stringResource(R.string.ks_kp_gewicht_k), stringResource(R.string.ks_kp_tage_zusatz, FENSTER_TAGE.toInt()))
        Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(zahlMitEinheit(Koerperrechnung.eineStelle(umrechnen(letzter.wert)), einheit, 30, 16), lineHeight = 36.sp)
                val veraenderung = Koerperrechnung.veraenderung(tage, heute)?.let { veraenderungText(umrechnen(it), " $einheit") }
                Text(
                    listOfNotNull(messtag(context, letzter.tag, heute), veraenderung).joinToString(" · "),
                    fontSize = 13.sp, lineHeight = 19.sp, color = grau,
                )
            }
            Koerperverlauf(
                tage.filter { it.tag >= von }.map { it.copy(wert = umrechnen(it.wert)) },
                linieImFenster(tage, von, umrechnen), von, heute, koerperTon(), ::monat,
            )
        }
    }
}

@Composable
private fun FettKarte(tage: List<Koerpertag>, heute: LocalDate) {
    val von = heute.minusDays(FENSTER_TAGE)
    Kasten {
        Kopf(stringResource(R.string.ks_kp_fett_k), stringResource(R.string.ks_kp_tage_zusatz, FENSTER_TAGE.toInt()))
        Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp)) {
            Koerperverlauf(tage.filter { it.tag >= von }, linieImFenster(tage, von) { it }, von, heute, koerperTon(), ::monat)
        }
    }
}

private class Wertzeile(val titel: String, val zahl: String, val einheit: String?, val tag: LocalDate)

@Composable
private fun ZusammensetzungKarte(z: Zusammensetzung, heute: LocalDate, imperial: Boolean) {
    val context = LocalContext.current
    val masse = masseEinheit(imperial)
    fun masseZahl(t: Koerpertag) = Koerperrechnung.eineStelle(Koerperrechnung.masse(t.wert, imperial))
    val zeilen = listOfNotNull(
        z.fett?.let { Wertzeile(stringResource(R.string.ks_kp_fett), Koerperrechnung.eineStelle(it.wert), "%", it.tag) },
        z.magermasse?.let { Wertzeile(stringResource(R.string.ks_kp_magermasse), masseZahl(it), masse, it.tag) },
        z.knochen?.let { Wertzeile(stringResource(R.string.ks_kp_knochen), masseZahl(it), masse, it.tag) },
        z.wasser?.let { w ->
            Wertzeile(stringResource(R.string.ks_kp_wasser), masseZahl(w), masse + (z.wasserProzent?.let { " ($it%)" } ?: ""), w.tag)
        },
        z.grundumsatz?.let {
            Wertzeile(
                stringResource(R.string.ks_kp_grundumsatz), String.format(Locale.getDefault(), "%,d", it.wert.roundToInt()),
                stringResource(R.string.ks_kp_kcal_tag), it.tag,
            )
        },
        z.bmi?.let { Wertzeile(stringResource(R.string.ks_kp_bmi), Koerperrechnung.eineStelle(it.wert), null, it.tag) },
        z.groesse?.let { g ->
            val tag = g.zeit.atZone(ZoneId.systemDefault()).toLocalDate()
            if (imperial) {
                val (fuss, zoll) = Koerperrechnung.fussZoll(g.wert)
                Wertzeile(stringResource(R.string.ks_kp_groesse), "$fuss′ $zoll″", null, tag)
            } else {
                Wertzeile(stringResource(R.string.ks_kp_groesse), "${(g.wert * 100).roundToInt()}", "cm", tag)
            }
        },
    )
    Kasten {
        Kopf(stringResource(R.string.ks_kp_zusammensetzung_k))
        zeilen.forEachIndexed { i, w ->
            if (i > 0) Trenner(14.dp)
            Zeile(w.titel, w.tag.takeIf { it != heute }?.let { messtag(context, it, heute) }, w.zahl, w.einheit, minHoehe = 56.dp)
        }
        Spacer(Modifier.height(4.dp))
    }
}
