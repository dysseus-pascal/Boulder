package coredevices.pebble.kiesel.ansicht

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coredevices.pebble.R
import coredevices.pebble.kiesel.Formate
import coredevices.pebble.kiesel.Koerperdaten
import coredevices.pebble.kiesel.Koerperrechnung
import coredevices.pebble.kiesel.Fenster
import coredevices.pebble.kiesel.Koerpertag
import coredevices.pebble.kiesel.Koerperzeile
import coredevices.pebble.kiesel.Zusammensetzung
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToLong

/** Ein Anteil in Prozent mit einer Stelle, getrennt fuer die fette Zahl: "22.4" und "%". */
private fun prozentEineStelle(p: Double) = Formate.Angabe(Formate.zahl(p, 1), "%")

/** "−0.6 kg in 30 days", "+1.2% in 30 days" - die Einheit ueber die Angabe, vor "%" ohne Abstand. */
@Composable
private fun veraenderungText(betrag: Double, einheit: String): String =
    stringResource(
        R.string.ks_kp_in_tagen, Formate.Angabe(Formate.vorzeichen(betrag, 1), einheit).text, Fenster.KOERPER_VERAENDERUNG_TAGE.toInt(),
    )

/**
 * Die Zeile "Body" fuer die Liste der Gesundheitsseite, oder null: ohne
 * Gewicht und Fett der letzten 90 Tage gibt es keine.
 * Sie liest selbst - der GesundheitsStand bleibt so, wie das Widget ihn kennt.
 *
 * NICHT AM UHRSCHUB: Gewicht kommt von der Waage, nie von der Uhr. Frueher
 * las die Zeile mit jedem neuen Stand der Seite (jeder Schub, jeder Eintrag)
 * 7 Arten ueber ein Jahr. Jetzt beim Aufbau nur, wenn ihr Stand aelter als
 * [Koerperdaten.FRISCH_MS] ist; die Rueckkehr aus einer anderen App - etwa
 * der Waage - liest immer.
 */
@Composable
internal fun koerperzeile(oeffne: () -> Unit): (@Composable () -> Unit)? {
    val context = LocalContext.current
    val erster = remember { AtomicBoolean(true) }
    val stand = rememberStand(Koerperdaten.zuletzt) {
        Koerperdaten.lies(context, if (erster.getAndSet(false)) Koerperdaten.FRISCH_MS else 0)
    }
    val imperial = einheitenImperial()
    val z = remember(stand) { stand?.let { Koerperrechnung.zeile(it, LocalDate.now(), ZoneId.systemDefault()) } } ?: return null
    return { KoerperZeile(z, imperial, oeffne) }
}

@Composable
private fun KoerperZeile(z: Koerperzeile, imperial: Boolean, oeffne: () -> Unit) {
    val context = LocalContext.current
    val wert = if (z.istGewicht) Formate.gewicht(z.wert.wert, imperial) else prozentEineStelle(z.wert.wert)
    val unter = listOfNotNull(
        // Das Datum nur bei alten Messungen - das Gewicht von vor Wochen laese sich sonst als das heutige.
        if (z.alt) Formate.tag(context, z.wert.tag) else null,
        z.fett?.let { stringResource(R.string.ks_kp_fett_anteil, Formate.zahl(it.wert, 1)) },
        z.veraenderung?.let {
            if (z.istGewicht) veraenderungText(Formate.gewichtIn(it, imperial), Formate.gewichtEinheit(imperial)) else veraenderungText(it, "%")
        },
    ).joinToString(" · ").ifEmpty { null }
    Zeile(
        stringResource(R.string.ks_kp_zeile), unter, wert.zahl, wert.einheit,
        zeichen = { Tonkreis(Zeichen.waage, Farben.koerper) }, onClick = oeffne,
    )
}

/**
 * "Body": Gewicht und Zusammensetzung, wie Waagen-Apps sie in die Akte
 * schreiben. Oben das letzte Gewicht mit seinem Verlauf, dann das Fett und
 * die uebrigen Werte - was nicht gemessen wurde, fehlt ganz.
 */
@Composable
internal fun KoerperAnsicht() {
    val context = LocalContext.current
    val stand = rememberStand(Koerperdaten.zuletzt) { Koerperdaten.lies(context) }
    val imperial = einheitenImperial()
    if (stand == null) {
        Ladekreis()
        return
    }
    // Ohne jede Messung eine leere Seite mit einem Satz statt einer Karte mit einer Zeile.
    if (stand.leer) {
        Leerseite(stringResource(R.string.ks_kp_keine))
        return
    }
    val zone = ZoneId.systemDefault()
    val heute = LocalDate.now()
    val gewicht = remember(stand) { Koerperrechnung.tage(stand.gewicht, zone) }
    val fett = remember(stand) { Koerperrechnung.tage(stand.fett, zone) }
    val zusammen = remember(stand) { Koerperrechnung.zusammensetzung(stand, zone) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = SEITENRAND), verticalArrangement = Arrangement.spacedBy(KARTENABSTAND)) {
        item { Spacer(Modifier.height(2.dp)) }
        if (gewicht.isNotEmpty()) item { GewichtKarte(gewicht, heute, imperial) }
        // Ein einzelner Punkt ist kein Verlauf; der Wert steht ohnehin in der Zusammensetzung.
        if (fett.count { it.tag >= heute.minusDays(Fenster.KOERPER_TAGE) } >= 2) item { FettKarte(fett, heute) }
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

/** Der Monat unter der Zeitachse der Verlaeufe: "Sep". */
private fun monat(tag: LocalDate): String = Formate.monatKurz(tag.month)

@Composable
private fun GewichtKarte(tage: List<Koerpertag>, heute: LocalDate, imperial: Boolean) {
    val context = LocalContext.current
    val letzter = tage.last()
    val von = heute.minusDays(Fenster.KOERPER_TAGE)
    val umrechnen = { kg: Double -> Formate.gewichtIn(kg, imperial) }
    val titel = stringResource(R.string.ks_kp_gewicht_k)
    val zusatz = stringResource(R.string.ks_kp_tage_zusatz, Fenster.KOERPER_TAGE.toInt())
    val wert = Formate.gewicht(letzter.wert, imperial)
    Abschnitt(titel, zusatz) {
        val veraenderung = Koerperrechnung.veraenderung(tage, heute)?.let { veraenderungText(umrechnen(it), Formate.gewichtEinheit(imperial)) }
        Hauptwert(wert.zahl, wert.einheit, listOfNotNull(Formate.tag(context, letzter.tag, heute), veraenderung).joinToString(" · "))
        Koerperverlauf(
            tage.filter { it.tag >= von }.map { it.copy(wert = umrechnen(it.wert)) },
            linieImFenster(tage, von, umrechnen), von, heute, Farben.koerper.ton(), ::monat,
            beschreibung = listOfNotNull(titel, zusatz, wert.text, veraenderung).joinToString(", "),
        )
    }
}

@Composable
private fun FettKarte(tage: List<Koerpertag>, heute: LocalDate) {
    val von = heute.minusDays(Fenster.KOERPER_TAGE)
    val titel = stringResource(R.string.ks_kp_fett_k)
    val zusatz = stringResource(R.string.ks_kp_tage_zusatz, Fenster.KOERPER_TAGE.toInt())
    Abschnitt(titel, zusatz) {
        Koerperverlauf(
            tage.filter { it.tag >= von }, linieImFenster(tage, von) { it }, von, heute, Farben.koerper.ton(), ::monat,
            beschreibung = listOf(titel, zusatz, prozentEineStelle(tage.last().wert).text).joinToString(", "),
        )
    }
}

private class Wertzeile(val titel: String, val wert: Formate.Angabe, val tag: LocalDate)

@Composable
private fun ZusammensetzungKarte(z: Zusammensetzung, heute: LocalDate, imperial: Boolean) {
    val context = LocalContext.current
    fun masse(t: Koerpertag) = Formate.gewicht(t.wert, imperial)
    val zeilen = listOfNotNull(
        z.fett?.let { Wertzeile(stringResource(R.string.ks_kp_fett), prozentEineStelle(it.wert), it.tag) },
        z.magermasse?.let { Wertzeile(stringResource(R.string.ks_kp_magermasse), masse(it), it.tag) },
        z.knochen?.let { Wertzeile(stringResource(R.string.ks_kp_knochen), masse(it), it.tag) },
        z.wasser?.let { w ->
            // Der Anteil am Gewicht klein hinter der Einheit: "kg (55%)".
            val m = masse(w)
            Wertzeile(stringResource(R.string.ks_kp_wasser), m.copy(einheit = m.einheit + (z.wasserProzent?.let { " (${Formate.prozent(it)})" } ?: "")), w.tag)
        },
        z.grundumsatz?.let {
            Wertzeile(stringResource(R.string.ks_kp_grundumsatz), Formate.Angabe(Formate.zahl(it.wert.roundToLong()), stringResource(R.string.ks_kp_kcal_tag)), it.tag)
        },
        z.bmi?.let { Wertzeile(stringResource(R.string.ks_kp_bmi), Formate.Angabe(Formate.zahl(it.wert, 1), null), it.tag) },
        z.groesse?.let { g -> Wertzeile(stringResource(R.string.ks_kp_groesse), Formate.groesse(g.wert, imperial), g.zeit.atZone(ZoneId.systemDefault()).toLocalDate()) },
    )
    Kasten {
        Kopf(stringResource(R.string.ks_kp_zusammensetzung_k))
        zeilen.forEachIndexed { i, w ->
            if (i > 0) Trenner(INNEN)
            Zeile(w.titel, w.tag.takeIf { it != heute }?.let { Formate.tag(context, it, heute) }, w.wert.zahl, w.wert.einheit, minHoehe = 56.dp)
        }
        Spacer(Modifier.height(4.dp))
    }
}
