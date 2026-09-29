package coredevices.pebble.kiesel.ansicht

import android.content.Intent
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coredevices.pebble.R
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

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // stand zwingt nach jeder Aenderung zum Neulesen der gemerkten Werte.
        stand.let { }
        val ordner = remember(stand) { Sichern.ordner(context) }
        Karte {
            Text(stringResource(R.string.ks_b_ordner), style = MaterialTheme.typography.labelMedium)
            Text(ordner?.name ?: stringResource(R.string.ks_b_keiner), fontWeight = FontWeight.Bold)
            OutlinedButton(onClick = { waehle.launch(Sichern.ordnerUri(context)) }) { Text(stringResource(R.string.ks_b_waehlen)) }
        }
        Karte {
            Text(stringResource(R.string.ks_b_sichern), style = MaterialTheme.typography.labelMedium)
            Text(stringResource(R.string.ks_b_sichern_text), style = MaterialTheme.typography.bodySmall)
            val zuletzt = Sichern.zuletzt(context)
            Text(
                when {
                    zuletzt == 0L -> stringResource(R.string.ks_b_nie)
                    // Unter einer Minute sagte DateUtils "vor 0 Minuten".
                    System.currentTimeMillis() - zuletzt < DateUtils.MINUTE_IN_MILLIS -> stringResource(R.string.ks_b_eben)
                    else -> stringResource(R.string.ks_b_zuletzt, DateUtils.getRelativeTimeSpanString(zuletzt).toString())
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Button(enabled = ordner != null && !laeuft, onClick = { tue { Sichern.jetzt(context, api) } }) { Text(stringResource(R.string.ks_b_jetzt)) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.ks_b_taeglich), Modifier.weight(1f))
                Switch(checked = Sichern.taeglich(context), enabled = ordner != null, onCheckedChange = { Sichern.setzeTaeglich(context, it); stand++ })
            }
        }
        Karte {
            Text(stringResource(R.string.ks_b_uebernehmen), style = MaterialTheme.typography.labelMedium)
            Text(stringResource(R.string.ks_b_uebernehmen_text), style = MaterialTheme.typography.bodySmall)
            Button(enabled = ordner != null && !laeuft, onClick = { tue { Sichern.zurueck(context) } }) { Text(stringResource(R.string.ks_b_holen)) }
        }
        meldung?.let { Text(it, fontWeight = FontWeight.Bold) }
    }
}
