package coredevices.pebble.kiesel.ansicht

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
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
import coredevices.pebble.R
import coredevices.pebble.kiesel.Formate
import coredevices.pebble.kiesel.Sichern
import io.rebble.libpebblecommon.connection.HealthDataApi
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Sicherung und Uebernahme aus Kiesel-Helper. Ein Ordner fuer beides: dort
 * legt Kiesel-Helper seine Sicherung ab, und dort legt Boulder seine eigene.
 */
@Composable
internal fun SicherungAnsicht() {
    val context = LocalContext.current
    val api = koinInject<HealthDataApi>()
    val scope = rememberCoroutineScope()
    var stand by remember { mutableIntStateOf(0) }
    var meldung by remember { mutableStateOf<String?>(null) }
    var laeuft by remember { mutableStateOf(false) }
    val waehle = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            Sichern.setzeOrdner(context, uri)
            stand++
        }
    }
    fun tue(arbeit: suspend () -> String) {
        laeuft = true
        scope.launch {
            meldung = arbeit()
            laeuft = false
            stand++
        }
    }
    // Die Fehlschlaege, die Sichern als Text meldet - sie stehen rot, der Erfolg nicht.
    val fehler = listOf(R.string.ks_b_kein_ordner, R.string.ks_b_fehlgeschlagen, R.string.ks_b_keine_sicherung).map { stringResource(it) }

    Seite {
        // stand zwingt nach jeder Aenderung zum Neulesen der gemerkten Werte.
        val ordner = remember(stand) { Sichern.ordner(context) }
        // Der Name ist eine Abfrage beim Dokumentanbieter (IPC, 2-20 ms im Hauptfaden): einmal je
        // Stand statt bei jeder Rekomposition, die laeuft und meldung beim Sichern ausloesen.
        val ordnerName = remember(stand) { ordner?.name }
        val zuletzt = remember(stand) { Sichern.zuletzt(context) }
        val taeglich = remember(stand) { Sichern.taeglich(context) }
        Kasten {
            Kopf(stringResource(R.string.ks_b_ordner))
            Knopfzeile(
                titel = ordnerName ?: stringResource(R.string.ks_b_keiner),
                knopf = stringResource(R.string.ks_b_waehlen),
                onClick = { waehle.launch(Sichern.ordnerUri(context)) },
            )
        }
        Kasten {
            Kopf(stringResource(R.string.ks_b_sichern))
            Knopfzeile(
                titel = stringResource(R.string.ks_b_sichern_text),
                // Der Stand in der einen Form der App: "just now", "5 minutes ago".
                unter = if (zuletzt == 0L) stringResource(R.string.ks_b_nie)
                else stringResource(R.string.ks_b_zuletzt, Formate.relativ(context, zuletzt)),
                knopf = stringResource(R.string.ks_b_jetzt),
                onClick = { tue { Sichern.jetzt(context, api) } },
                aktiv = ordner != null && !laeuft,
            )
            Trenner(INNEN)
            Schaltzeile(stringResource(R.string.ks_b_taeglich), an = taeglich, aktiv = ordner != null) {
                Sichern.setzeTaeglich(context, it)
                stand++
            }
        }
        Kasten {
            Kopf(stringResource(R.string.ks_b_uebernehmen))
            Knopfzeile(
                titel = stringResource(R.string.ks_b_uebernehmen_text),
                unter = stringResource(R.string.ks_b_uebernehmen_unter),
                knopf = stringResource(R.string.ks_b_holen),
                onClick = { tue { Sichern.zurueck(context) } },
                aktiv = ordner != null && !laeuft,
            )
        }
        meldung?.let { Meldung(it, if (it in fehler) Farben.schlecht.ton() else null, Modifier.padding(horizontal = INNEN)) }
    }
}
