package coredevices.pebble.kiesel.ansicht

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coredevices.pebble.R
import coredevices.pebble.kiesel.Minutenexport
import coredevices.pebble.kiesel.TrainingEintrag
import coredevices.pebble.kiesel.TrainingsDaten
import io.rebble.libpebblecommon.connection.HealthDataApi
import io.rebble.libpebblecommon.connection.LibPebble
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Der Minutenexport (Debug): Zeitraum waehlen, Vorschau, als Datei teilen. */
@Composable
internal fun MinutenexportAnsicht() {
    val context = LocalContext.current
    val api = koinInject<HealthDataApi>()
    val libPebble = koinInject<LibPebble>()
    val scope = rememberCoroutineScope()
    val zone = remember { ZoneId.systemDefault() }
    // Vorgabe: die letzten zwei Stunden bis zur laufenden Minute.
    val jetzt = remember { Instant.now().epochSecond / 60 * 60 }
    var von by rememberSaveable { mutableLongStateOf(jetzt - 2 * 3600) }
    var bis by rememberSaveable { mutableLongStateOf(jetzt) }
    var trainings by remember { mutableStateOf(TrainingsDaten.zuletzt?.trainings) }
    var mitDaten by remember { mutableStateOf<Int?>(null) }
    var laeuft by remember { mutableStateOf(false) }
    var meldung by remember { mutableStateOf<String?>(null) }
    val fehlgeschlagen = stringResource(R.string.ks_m_fehlgeschlagen)
    LaunchedEffect(Unit) { trainings = TrainingsDaten.uebersicht(context).trainings }
    val fehler = Minutenexport.pruefe(von, bis)
    LaunchedEffect(von, bis) {
        mitDaten = null
        if (fehler == null) mitDaten = Minutenexport.zaehle(api, von, bis)
    }
    val zeitpunkt = remember { DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT) }
    val uhr = remember { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT) }
    val tag = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM) }
    fun text(t: Long) = zeitpunkt.format(Instant.ofEpochSecond(t).atZone(zone))

    // Erst der Tag, dann die Uhrzeit: zwei Dialoge von Android, wie die Zeiten in Kieselsport.
    fun waehle(t: Long, setze: (Long) -> Unit) {
        val z = Instant.ofEpochSecond(t).atZone(zone)
        DatePickerDialog(context, { _, jahr, monat, tagImMonat ->
            TimePickerDialog(context, { _, stunde, minute ->
                setze(LocalDateTime.of(jahr, monat + 1, tagImMonat, stunde, minute).atZone(zone).toEpochSecond())
            }, z.hour, z.minute, DateFormat.is24HourFormat(context)).show()
        }, z.year, z.monthValue - 1, z.dayOfMonth).show()
    }

    Seite {
        Kasten {
            Kopf(stringResource(R.string.ks_m_zeitraum))
            // Ein neuer Beginn behaelt die Dauer: so verschiebt man denselben Ausschnitt.
            Zeile(stringResource(R.string.ks_m_beginn), text(von), onClick = {
                waehle(von) { neu -> bis = neu + (bis - von); von = neu }
            })
            Trenner(INNEN)
            Zeile(stringResource(R.string.ks_m_ende), text(bis), onClick = { waehle(bis) { bis = it } })
            FlowRow(
                Modifier.padding(horizontal = INNEN, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (minuten in Minutenexport.DAUERN) {
                    FilterChip(
                        selected = bis - von == minuten * 60L,
                        onClick = { bis = von + minuten * 60L },
                        label = { Text(if (minuten % 60 == 0) "${minuten / 60} h" else "$minuten min") },
                    )
                }
            }
            val im = trainings.orEmpty().count { ueberlappt(it, von, bis) }
            Hinweiszeile(
                when (fehler) {
                    Minutenexport.Fehler.ENDE_VOR_BEGINN -> stringResource(R.string.ks_m_ende_vor_beginn)
                    Minutenexport.Fehler.ZU_LANG -> stringResource(R.string.ks_m_zu_lang)
                    null -> mitDaten?.let { stringResource(R.string.ks_m_vorschau, (bis - von) / 60, it, im) }
                        ?: stringResource(R.string.ks_m_zaehle)
                },
                farbe = if (fehler != null) Farben.schlecht.ton() else null,
            )
        }
        Kasten {
            Kopf(stringResource(R.string.ks_m_aus_training))
            val liste = trainings
            when {
                liste == null -> Ladekreis()
                liste.isEmpty() -> Leerzeile(stringResource(R.string.ks_m_keine_trainings))
                else -> liste.take(TRAININGS).forEachIndexed { i, t ->
                    if (i > 0) Trenner(INNEN)
                    val a = t.sitzung.startTime.atZone(zone)
                    val b = t.sitzung.endTime.atZone(zone)
                    Zeile(
                        titel = stringResource(t.sport.nameId) + " · " + tag.format(a),
                        unter = uhr.format(a) + "–" + uhr.format(b),
                        onClick = {
                            val (neuVon, neuBis) = Minutenexport.umTraining(a.toEpochSecond(), b.toEpochSecond())
                            von = neuVon
                            bis = neuBis
                        },
                    )
                }
            }
            Hinweiszeile(stringResource(R.string.ks_m_training_text))
        }
        Kasten {
            Knopfzeile(
                titel = stringResource(R.string.ks_m_datei),
                unter = stringResource(R.string.ks_m_datei_text),
                knopf = stringResource(R.string.ks_m_exportieren),
                aktiv = fehler == null && !laeuft,
                onClick = {
                    laeuft = true
                    meldung = null
                    scope.launch {
                        val datei = Minutenexport.erstelle(context, api, libPebble, von, bis)
                        laeuft = false
                        if (datei == null) meldung = fehlgeschlagen else Minutenexport.teile(context, datei)
                    }
                },
            )
        }
        meldung?.let { Meldung(it, Farben.schlecht.ton(), Modifier.padding(horizontal = INNEN)) }
    }
}

/** Ein grauer Satz in der Karte, eingerueckt wie ihre Zeilen. */
@Composable
private fun Hinweiszeile(text: String, farbe: Color? = null) {
    Box(Modifier.padding(start = INNEN, end = INNEN, top = 4.dp, bottom = 12.dp)) { Unterzeile(text, farbe) }
}

/** So viele der letzten Trainings stehen zur Wahl - zum Einstellen reichen die juengsten. */
private const val TRAININGS = 8

private fun ueberlappt(t: TrainingEintrag, von: Long, bis: Long): Boolean =
    t.sitzung.startTime.epochSecond < bis && t.sitzung.endTime.epochSecond > von
