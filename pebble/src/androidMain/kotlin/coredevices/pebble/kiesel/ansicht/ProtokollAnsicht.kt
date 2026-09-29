package coredevices.pebble.kiesel.ansicht

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coredevices.pebble.R
import coredevices.pebble.kiesel.KieselVerlauf
import java.time.Instant

/** Was die Uhr-Apps gemeldet haben und was Boulder daraus machte - juengstes zuerst. */
@Composable
internal fun ProtokollAnsicht() {
    val context = LocalContext.current
    val zeilen = remember { KieselVerlauf.alle(context) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        if (zeilen.isEmpty()) item { Text(stringResource(R.string.ks_n_protokoll_leer), Modifier.padding(vertical = 16.dp)) }
        items(zeilen) { (t, z) ->
            Column(Modifier.padding(vertical = 8.dp)) {
                Text(wannText(context, Instant.ofEpochMilli(t)), style = MaterialTheme.typography.labelSmall)
                Text(z, style = MaterialTheme.typography.bodyMedium)
            }
            HorizontalDivider()
        }
    }
}
