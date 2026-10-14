package coredevices.pebble.kiesel.ansicht

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import coredevices.pebble.R
import coredevices.pebble.kiesel.Formate
import coredevices.pebble.kiesel.Sport
import coredevices.pebble.kiesel.Sportrechnung
import coredevices.pebble.kiesel.TrainingsUebersicht
import java.time.LocalDate
import java.time.ZoneId

/**
 * Das Sport-Feld auf Health, im Stil der Zeilen darunter: das letzte
 * Training (Art, Strecke oder Dauer, Tag) und rechts die Tage dieser Woche
 * als Punkte mit ihrer Zahl. DIESELBE WOCHE WIE AUF DER SPORTSEITE - aus
 * derselben Uebersicht ([TrainingsDaten.uebersicht]) und derselben Rechnung
 * ([Sportrechnung.reihe], [Sportrechnung.lage]): Montag bis Sonntag, ein
 * Tag mit Training ist gefuellt. Ein Tipp oeffnet die Sportseite.
 * Bis die Uebersicht geladen ist ([daten] null), steht nur "Sport" da.
 */
@Composable
internal fun Sportfeld(daten: TrainingsUebersicht?, heute: LocalDate, imperial: Boolean, oeffne: () -> Unit) {
    val context = LocalContext.current
    val letztes = daten?.trainings?.firstOrNull()
    val unter = when {
        letztes != null -> {
            // Die Strecke wie in Sport (streckeVon); ohne Strecke die aktive Dauer.
            val menge = if (letztes.meter > 0) streckeVon(letztes, imperial).text else Formate.dauer(letztes.minuten)
            val tag = letztes.sitzung.startTime.atZone(ZoneId.systemDefault()).toLocalDate()
            stringResource(R.string.ks_sf_letztes, trainingsName(letztes), menge, Formate.tag(context, tag, heute, klein = true))
        }
        daten != null -> stringResource(R.string.ks_sp_leer)
        else -> null
    }
    val einheiten = daten?.trainings.orEmpty().map { it.einheit }
    val tage = Sportrechnung.reihe(einheiten, heute).map { it.haupt != null }
    val anzahl = Sportrechnung.lage(einheiten, heute).anzahl
    val titel = stringResource(R.string.ks_n_training)
    val woche = stringResource(R.string.ks_sf_woche)
    // Das Feld steht fuer Sport allgemein: der Laeufer in der Farbe des Laufens, wie im Entwurf.
    val farbe = Farben.sport(Sport.Laufen)
    val gefuellt = farbe.flaeche()
    val leer = MaterialTheme.colorScheme.onSurfaceVariant.aufKarte(0.40f)
    Kasten {
        Zeile(
            titel, unter,
            zeichen = { Tonkreis(Zeichen.laeufer, farbe) },
            beschreibung = listOfNotNull(titel, unter, "${Formate.zahl(anzahl.toLong())} $woche").joinToString(", "),
            rechts = if (daten == null) null else ({
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        tage.forEach { an ->
                            Box(
                                Modifier.size(8.dp)
                                    .then(if (an) Modifier.background(gefuellt, CircleShape) else Modifier.border(1.5.dp, leer, CircleShape)),
                            )
                        }
                    }
                    Text(zahlMitEinheit(Formate.zahl(anzahl.toLong()), woche), maxLines = 1)
                }
            }),
            onClick = oeffne,
        )
    }
}
