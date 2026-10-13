package coredevices.pebble.kiesel.ansicht

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coredevices.pebble.R
import coredevices.pebble.kiesel.Formate
import coredevices.pebble.kiesel.Gesundheitsziele
import io.rebble.libpebblecommon.connection.HealthDataApi
import io.rebble.libpebblecommon.health.BoulderZiele
import org.koin.compose.koinInject

private val SCHRITT_STUFEN = (BoulderZiele.SCHRITTE_MIN..BoulderZiele.SCHRITTE_MAX step 500).toList()
private val SCHLAF_STUFEN = (BoulderZiele.SCHLAF_MIN..BoulderZiele.SCHLAF_MAX step 15).toList()

/**
 * Boulder 0.53: das Schritt- und das Schlafziel, eine Seite in Gravels
 * Einstellungen (Health). Jede Aenderung gilt sofort, ohne Speichern-Knopf:
 * in Boulder und ueber die Datenbank Health auf der Uhr ([Gesundheitsziele.setze]).
 */
@Composable
internal fun ZieleAnsicht(api: HealthDataApi = koinInject()) {
    val context = LocalContext.current
    var ziele by remember { mutableStateOf(Gesundheitsziele.ziele(context)) }
    fun setze(neu: Gesundheitsziele.Ziele) {
        if (neu == ziele) return
        ziele = neu
        Gesundheitsziele.setze(context, api, neu)
    }

    Seite {
        Kasten {
            Stufer(
                stringResource(R.string.ks_z_schritte), SCHRITT_STUFEN, ziele.schritte,
                wert = { Formate.menge(it.toLong(), null) },
            ) { setze(ziele.copy(schritte = it)) }
            Stufer(
                stringResource(R.string.ks_z_schlaf), SCHLAF_STUFEN, ziele.schlafMin,
                wert = { Formate.dauerTeile(it.toLong()) },
            ) { setze(ziele.copy(schlafMin = it)) }
            Spacer(Modifier.height(6.dp))
        }
        Box(Modifier.padding(horizontal = INNEN)) {
            Unterzeile(stringResource(R.string.ks_z_text))
        }
    }
}
