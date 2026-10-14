package coredevices.pebble.kiesel.ansicht

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coredevices.pebble.R
import coredevices.pebble.kiesel.Formate
import coredevices.pebble.kiesel.KieselVerlauf
import java.time.Instant

/** Was die Uhr-Apps gemeldet haben und was Boulder daraus machte - juengstes zuerst. */
@Composable
internal fun ProtokollAnsicht() {
    val context = LocalContext.current
    val zeilen = remember { KieselVerlauf.alle(context) }
    if (zeilen.isEmpty()) {
        Leerseite(stringResource(R.string.ks_n_protokoll_leer))
        return
    }
    // Bis zu 100 Zeilen ohne Karte: ein Kasten baute sie alle auf einmal, die Liste nur die sichtbaren.
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = SEITENRAND)) {
        itemsIndexed(zeilen) { i, (t, z) ->
            if (i > 0) Trenner(0.dp)
            // Mit Tag auch heute: die Liste hat keine Tageskoepfe, die ihn tragen koennten.
            Column(Modifier.padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Unterzeile(Formate.zeitpunkt(context, Instant.ofEpochMilli(t), mitWochentag = true))
                Fliesstext(z)
            }
        }
    }
}
