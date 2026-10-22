package coredevices.pebble.kiesel.ansicht

import android.app.TimePickerDialog
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coredevices.pebble.R
import coredevices.pebble.kiesel.Formate
import coredevices.pebble.kiesel.Herzgrenzen
import coredevices.pebble.kiesel.KieselEinstellungen
import coredevices.pebble.rememberLibPebble
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.util.Locale

/** Die Pin-Arten in der Reihenfolge der Uhr: 0 kein Pin, dann Sportart + 1. */
private val PIN_ARTEN = listOf(
    R.string.ks_n_kein_pin, R.string.ks_sport_laufen, R.string.ks_sport_bike, R.string.ks_sport_wandern,
    R.string.ks_sport_kraft, R.string.ks_sport_mtb, R.string.ks_sport_yoga, R.string.ks_sport_schwimmen,
    R.string.ks_sport_atem,
)

/**
 * Die Einstellungen von Kieselsport, am Telefon geaendert - eine Seite in
 * Gravels Einstellungen (Sport), und wie bei einem Watchface ueber "Settings"
 * bei Kieselsport in der Liste der Apps (LockerAppScreen).
 *
 * DER ENTWURF STARTET BEIM STAND DER UHR, nicht bei einem eigenen: die Uhr
 * ist die eine Stelle, an der die Werte gelten (siehe KieselEinstellungen).
 */
@Composable
internal fun KieselsportAnsicht() {
    val context = LocalContext.current
    val libPebble = rememberLibPebble()
    val scope = rememberCoroutineScope()
    var stand by remember { mutableStateOf(KieselEinstellungen.stand(context)) }
    var entwurf by remember { mutableStateOf(stand) }
    var sendet by remember { mutableStateOf(false) }
    var ergebnis by remember { mutableStateOf<KieselEinstellungen.Ergebnis?>(null) }

    Seite {
        // Woher die Werte kommen: der Stand der Uhr, nicht ein eigener.
        Box(Modifier.padding(horizontal = INNEN)) {
            Unterzeile(
                if (stand.gemeldetUm == 0L) stringResource(R.string.ks_n_stand_nie)
                else stringResource(R.string.ks_n_stand_um, Formate.relativ(context, stand.gemeldetUm)),
            )
        }
        // "Nach Alter" ist ein eigener Zustand, nicht die Zahl 190: zur Uhr geht
        // er als Vermerk, dazu die Schaetzung fuer aeltere Firmware, die nicht
        // selbst rechnet (KieselEinstellungen.sende). Erst nach dem Laden
        // entscheiden - vorher saehe jedes Alter wie keines aus.
        val schaetzung by produceState<Herzgrenzen.Maximum?>(null) { value = Herzgrenzen.maximum(null, Herzgrenzen.alter(), null) }
        val nachAlter = schaetzung?.takeIf { it.nachAlter }?.wert
        // Der zuletzt eingestellte Wert: "From age" an und wieder aus soll ihn nicht verlieren.
        var eigener by remember { mutableStateOf(stand.maxpuls.takeIf { stand.maxpulsGesetzt }) }
        LaunchedEffect(nachAlter) { if (nachAlter != null && !entwurf.maxpulsGesetzt) entwurf = entwurf.copy(maxpuls = nachAlter) }
        Kasten {
            Kopf(stringResource(R.string.ks_n_puls))
            // Ohne Alter gibt es nichts zu schaetzen: kein Schalter, der eine Schaetzung behauptet.
            if (nachAlter != null) Schaltzeile(stringResource(R.string.ks_n_maxpuls_alter, nachAlter), an = !entwurf.maxpulsGesetzt) { ausAlter ->
                entwurf = entwurf.copy(maxpulsGesetzt = !ausAlter, maxpuls = if (ausAlter) nachAlter else eigener ?: nachAlter)
            }
            if (schaetzung != null && (entwurf.maxpulsGesetzt || nachAlter == null)) Stufer(
                stringResource(R.string.ks_n_maxpuls), (120..220).toList(), entwurf.maxpuls, grob = 5,
                wert = { Formate.menge(it.toLong(), "bpm") },
            ) {
                entwurf = entwurf.copy(maxpuls = it, maxpulsGesetzt = true)
                eigener = it
            }
            Spacer(Modifier.height(6.dp))
        }

        Kasten {
            Kopf(stringResource(R.string.ks_sport_kraft))
            val nie = stringResource(R.string.ks_n_nie)
            Stufer(
                stringResource(R.string.ks_n_pause), (0..300 step 15).toList(), entwurf.pause,
                wert = { if (it == 0) Formate.Angabe(nie, null) else Formate.menge(it.toLong(), "s") },
            ) { entwurf = entwurf.copy(pause = it) }
            val stufen = listOf(
                stringResource(R.string.ks_n_traege), stringResource(R.string.ks_n_normal), stringResource(R.string.ks_n_fein),
            )
            Stufer(
                stringResource(R.string.ks_n_empfind), listOf(1, 2, 3), entwurf.empfind,
                wert = { Formate.Angabe(stufen[it - 1], null) },
            ) { entwurf = entwurf.copy(empfind = it) }
            Spacer(Modifier.height(6.dp))
        }

        Kasten {
            Kopf(stringResource(R.string.ks_sport_schwimmen))
            // Das Becken in Metern, wie die Uhr es fuehrt - auch mit "Imperial Units".
            Stufer(
                stringResource(R.string.ks_n_becken), (10..50 step 5).toList(), entwurf.becken,
                wert = { Formate.Angabe(Formate.zahl(it.toLong()), "m") },
            ) { entwurf = entwurf.copy(becken = it) }
            Spacer(Modifier.height(6.dp))
        }

        Kasten {
            Kopf(stringResource(R.string.ks_sport_atem))
            val r = entwurf.atem
            fun neu(a: KieselEinstellungen.Atemrhythmus) { entwurf = entwurf.copy(atem = a) }
            val sekunden = { s: Int -> Formate.menge(s.toLong(), "s") }
            Stufer(stringResource(R.string.ks_n_atem_ein), KieselEinstellungen.Atemrhythmus.EIN.toList(), r.ein, grob = 5, wert = sekunden) { neu(r.copy(ein = it)) }
            Stufer(stringResource(R.string.ks_n_atem_halten), KieselEinstellungen.Atemrhythmus.HALTEN.toList(), r.halten, grob = 5, wert = sekunden) { neu(r.copy(halten = it)) }
            Stufer(stringResource(R.string.ks_n_atem_aus), KieselEinstellungen.Atemrhythmus.EIN.toList(), r.aus, grob = 5, wert = sekunden) { neu(r.copy(aus = it)) }
            Stufer(stringResource(R.string.ks_n_atem_leer), KieselEinstellungen.Atemrhythmus.HALTEN.toList(), r.leer, grob = 5, wert = sekunden) { neu(r.copy(leer = it)) }
            Stufer(
                stringResource(R.string.ks_n_atem_runden), KieselEinstellungen.Atemrhythmus.RUNDEN.toList(), r.runden, grob = 5,
                wert = { Formate.Angabe(Formate.zahl(it.toLong()), null) },
            ) { neu(r.copy(runden = it)) }
            Box(Modifier.padding(horizontal = INNEN, vertical = 6.dp)) {
                // Eine Uhr vor b36 nimmt den Rhythmus an, kennt die Uebung aber nicht.
                Unterzeile(
                    if (stand.gemeldetUm != 0L && !stand.uhrKenntAtem) stringResource(R.string.ks_n_atem_alt)
                    else stringResource(R.string.ks_n_atem_dauer, Formate.minutenSekunden(r.rundeS.toLong()), Formate.minutenSekunden(r.rundeS.toLong() * r.runden)),
                )
            }
        }

        Kasten {
            Kopf(stringResource(R.string.ks_n_pin))
            val arten = PIN_ARTEN.map { stringResource(it) }
            Stufer(
                stringResource(R.string.ks_n_pin_art), PIN_ARTEN.indices.toList(), entwurf.pinArt,
                wert = { Formate.Angabe(arten[it], null) },
            ) { entwurf = entwurf.copy(pinArt = it) }
            if (entwurf.pinArt > 0) {
                val (h, m) = entwurf.pinZeit.split(":").map { it.toIntOrNull() ?: 0 }.let { it[0].coerceIn(0, 23) to it.getOrElse(1) { 0 }.coerceIn(0, 59) }
                // Gezeigt im Format des Telefons; gespeichert wie die Uhr es meldet.
                Knopfzeile(stringResource(R.string.ks_n_pin_zeit), knopf = Formate.uhrzeit(context, LocalTime.of(h, m)), onClick = {
                    TimePickerDialog(context, { _, hh, mm ->
                        // "HH:MM" wie die Uhr es zurueckmeldet - sonst saehe "8:00"
                        // gegen "08:00" unbestaetigt aus. Daten fuer die Uhr, keine Anzeige.
                        entwurf = entwurf.copy(pinZeit = String.format(Locale.ROOT, "%02d:%02d", hh, mm))
                    }, h, m, DateFormat.is24HourFormat(context)).show()
                })
            } else Spacer(Modifier.height(6.dp))
        }

        // Die eine Tat der Seite als Zeile mit Knopf; waehrend sie laeuft, sagt es die Zeile.
        Kasten {
            Knopfzeile(
                stringResource(R.string.ks_n_senden), if (sendet) stringResource(R.string.ks_n_sendet) else null,
                knopf = stringResource(R.string.ks_n_senden_knopf), aktiv = !sendet && !entwurf.gleich(stand),
                onClick = {
                    sendet = true
                    ergebnis = null
                    scope.launch {
                        ergebnis = KieselEinstellungen.sende(context, libPebble, entwurf)
                        stand = KieselEinstellungen.stand(context)
                        sendet = false
                    }
                },
            )
        }
        val fehler = Farben.schlecht.ton()
        when (ergebnis) {
            KieselEinstellungen.Ergebnis.Uebernommen -> Meldung(stringResource(R.string.ks_n_uebernommen), modifier = Modifier.padding(horizontal = INNEN))
            KieselEinstellungen.Ergebnis.KeineUhr -> Meldung(stringResource(R.string.ks_n_keine_uhr), fehler, Modifier.padding(horizontal = INNEN))
            KieselEinstellungen.Ergebnis.KeineAntwort -> Meldung(stringResource(R.string.ks_n_keine_antwort), fehler, Modifier.padding(horizontal = INNEN))
            null -> {}
        }
    }
}

/** Das Minus als Strichzeichen wie [Zeichen.plus] - der Baukasten hat es noch nicht. */
private val MINUS: ImageVector by lazy {
    ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
        addPath(
            PathParser().parsePathString("M5 12h14").toNodes(), fill = null, stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        )
    }.build()
}

/**
 * Ein Wert aus einer festen Reihe, mit Minus und Plus. Mit [grob] kommen
 * Knoepfe dazu, die so viele Stufen auf einmal gehen - beim Maximalpuls
 * waeren hundert Einzelschritte zu viel. Jeder Knopf ist mindestens 48 dp
 * gross; der Wert steht fett mit kleiner Einheit wie jede Zahl der App.
 */
@Composable
internal fun Stufer(
    titel: String,
    werte: List<Int>,
    aktuell: Int,
    grob: Int = 0,
    wert: (Int) -> Formate.Angabe,
    aendere: (Int) -> Unit,
) {
    // Ein Wert, den die Uhr ausserhalb der Reihe meldet, landet auf der naechsten Stufe.
    val i = werte.indices.minBy { kotlin.math.abs(werte[it] - aktuell) }
    fun geh(schritte: Int) = aendere(werte[(i + schritte).coerceIn(werte.indices)])
    Column(Modifier.fillMaxWidth().padding(start = INNEN, end = 6.dp, top = 6.dp)) {
        Text(titel, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (grob > 0) TextButton(onClick = { geh(-grob) }, enabled = i > 0, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(Formate.MINUS + Formate.zahl(grob.toLong()))
            }
            IconButton(onClick = { geh(-1) }, enabled = i > 0) { Icon(MINUS, stringResource(R.string.ks_n_weniger)) }
            Text(zahlMitEinheit(wert(werte[i]), 16, 13), Modifier.weight(1f), textAlign = TextAlign.Center)
            IconButton(onClick = { geh(1) }, enabled = i < werte.lastIndex) { Icon(Zeichen.plus, stringResource(R.string.ks_n_mehr)) }
            if (grob > 0) TextButton(onClick = { geh(grob) }, enabled = i < werte.lastIndex, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("+" + Formate.zahl(grob.toLong()))
            }
        }
    }
}
