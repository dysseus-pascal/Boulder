package coredevices.pebble.kiesel.ansicht

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import coredevices.pebble.R
import coredevices.pebble.kiesel.Formate
import coredevices.pebble.kiesel.Spiegel
import io.rebble.libpebblecommon.connection.HealthDataApi
import io.rebble.libpebblecommon.connection.LibPebble
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Boulder: der eigene Server fuer Claude ([Spiegel]). Oben Adresse und Sync-Token, darunter erst
 * nach der Einrichtung, was gesendet wird, der Stand und die Knoepfe.
 *
 * DAS TOKEN STEHT NIE WIEDER DA: das Feld bleibt nach dem Speichern leer, ein leeres Feld behaelt
 * das gespeicherte. Wer es braucht, hat es im Passwortmanager oder auf dem Server.
 */
@Composable
internal fun SpiegelAnsicht() {
    val context = LocalContext.current
    val api = koinInject<HealthDataApi>()
    val libPebble = koinInject<LibPebble>()
    val scope = rememberCoroutineScope()
    var stand by remember { mutableIntStateOf(0) }
    var laeuft by remember { mutableStateOf(false) }
    var meldung by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    val ziel = remember(stand) { Spiegel.ziel(context) }
    // NICHT AN [stand]: der zaehlt bei jedem Speichern, Senden und Schalter hoch, und was getippt,
    // aber nicht gespeichert ist, ginge mit - auch nach einer abgelehnten Adresse das Token.
    var adresse by remember { mutableStateOf(Spiegel.ziel(context)?.adresse.orEmpty()) }
    var token by remember { mutableStateOf("") }
    val status = remember(stand) { Spiegel.status(context) }
    val texte = Spiegeltexte(
        adresseFalsch = stringResource(R.string.ks_sv_adresse_falsch),
        tokenFalsch = stringResource(R.string.ks_sv_token_falsch),
        gespeichert = stringResource(R.string.ks_sv_eingerichtet),
    )

    fun sende(alles: Boolean) {
        laeuft = true
        scope.launch {
            meldung = when (val aus = Spiegel.sende(context, api, libPebble, alles, gruendlich = true)) {
                is Spiegel.Ausgang.Fertig -> Spiegel.gesendetText(context, aus.quittung) to false
                is Spiegel.Ausgang.Fehler -> aus.text to true
                Spiegel.Ausgang.Aus -> null
            }
            laeuft = false
            stand++
        }
    }

    Seite {
        Kasten {
            Kopf(stringResource(R.string.ks_sv_server))
            Column(Modifier.padding(horizontal = INNEN, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = adresse, onValueChange = { adresse = it }, singleLine = true,
                    label = { Text(stringResource(R.string.ks_sv_adresse)) },
                    placeholder = { Text("https://boulder.example.com") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = token, onValueChange = { token = it }, singleLine = true,
                    label = { Text(stringResource(R.string.ks_sv_token)) },
                    supportingText = { Text(stringResource(if (ziel != null) R.string.ks_sv_token_gespeichert else R.string.ks_sv_token_text)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                FilledTonalButton(
                    onClick = {
                        meldung = when (Spiegel.richteEin(context, adresse, token)) {
                            Spiegel.Eingabe.ADRESSE -> texte.adresseFalsch to true
                            Spiegel.Eingabe.TOKEN -> texte.tokenFalsch to true
                            null -> {
                                // Gespeichert: die Adresse so, wie sie gilt, und das Token nie wieder sichtbar.
                                adresse = Spiegel.ziel(context)?.adresse.orEmpty()
                                token = ""
                                texte.gespeichert to false
                            }
                        }
                        stand++
                    },
                    modifier = Modifier.heightIn(min = 36.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = Farben.aktion.flaeche().copy(alpha = 0.22f), contentColor = Farben.aktion.ton(),
                    ),
                ) { Text(stringResource(R.string.ks_sv_speichern)) }
            }
        }
        if (ziel != null) {
            Kasten {
                Kopf(stringResource(R.string.ks_sv_was))
                for ((art, titel, unter) in listOf(
                    Triple(Spiegel.Art.TAGE, R.string.ks_sv_tage, R.string.ks_sv_tage_text),
                    Triple(Spiegel.Art.NAECHTE, R.string.ks_sv_naechte, R.string.ks_sv_naechte_text),
                    Triple(Spiegel.Art.TRAININGS, R.string.ks_sv_trainings, R.string.ks_sv_trainings_text),
                    Triple(Spiegel.Art.MINUTEN, R.string.ks_sv_minuten, R.string.ks_sv_minuten_text),
                )) {
                    if (art != Spiegel.Art.TAGE) Trenner(INNEN)
                    Schaltzeile(stringResource(titel), stringResource(unter), an = remember(stand) { Spiegel.an(context, art) }) {
                        Spiegel.setze(context, art, it)
                        stand++
                    }
                }
            }
            Kasten {
                Kopf(stringResource(R.string.ks_sv_senden))
                Knopfzeile(
                    titel = stringResource(R.string.ks_sv_jetzt_text),
                    unter = when {
                        status.fehler -> status.meldung
                        status.erfolg == 0L -> stringResource(R.string.ks_sv_nie)
                        else -> stringResource(R.string.ks_sv_zuletzt, Formate.relativ(context, status.erfolg))
                    },
                    unterFarbe = if (status.fehler) Farben.schlecht.ton() else null,
                    knopf = stringResource(R.string.ks_sv_jetzt),
                    onClick = { sende(alles = false) },
                    aktiv = !laeuft,
                )
                Trenner(INNEN)
                Knopfzeile(
                    titel = stringResource(R.string.ks_sv_alles),
                    unter = stringResource(R.string.ks_sv_alles_text),
                    knopf = stringResource(R.string.ks_sv_alles_knopf),
                    onClick = { sende(alles = true) },
                    aktiv = !laeuft,
                )
            }
            Kasten {
                Knopfzeile(
                    titel = stringResource(R.string.ks_sv_entfernen),
                    unter = stringResource(R.string.ks_sv_entfernen_text),
                    knopf = stringResource(R.string.ks_sv_entfernen_knopf),
                    onClick = {
                        Spiegel.entferne(context)
                        adresse = ""
                        token = ""
                        meldung = null
                        stand++
                    },
                    aktiv = !laeuft,
                )
            }
        }
        meldung?.let { (text, fehler) -> Meldung(text, if (fehler) Farben.schlecht.ton() else null, Modifier.padding(horizontal = INNEN)) }
        Fliesstext(stringResource(R.string.ks_sv_hinweis), Modifier.padding(horizontal = INNEN))
    }
}

/** Die Texte, die ausserhalb der Komposition gebraucht werden (im onClick). */
private class Spiegeltexte(val adresseFalsch: String, val tokenFalsch: String, val gespeichert: String)
