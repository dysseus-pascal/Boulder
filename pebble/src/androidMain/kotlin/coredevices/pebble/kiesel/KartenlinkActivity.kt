package coredevices.pebble.kiesel

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import coredevices.pebble.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Ein Kartenlink, weitergereicht an OsmAnd.
 *
 * Ein eigenes Fenster, weil ein Kurzlink erst aufgeloest werden muss - ohne
 * Rueckmeldung stuende man Sekunden vor einem Schirm, auf dem nichts geschieht.
 * Die Google-Maps-Adressen muss man Boulder einmal von Hand zuordnen
 * (Einstellungen › Apps › Boulder › Standardmaessig oeffnen); geo: geht sofort.
 */
class KartenlinkActivity : ComponentActivity() {
    private var meldung by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.ks_k_titel), style = MaterialTheme.typography.titleLarge)
                        Text(meldung ?: stringResource(R.string.ks_k_lese))
                        if (meldung != null) TextButton(onClick = { finish() }) { Text(stringResource(R.string.ks_n_schliessen)) }
                    }
                }
            }
        }

        val roh = intent?.dataString
        if (roh.isNullOrBlank()) {
            meldung = getString(R.string.ks_k_kein_link)
            return
        }
        lifecycleScope.launch {
            val voll = withContext(Dispatchers.IO) { if (Kartenlink.istKurzlink(roh)) Kartenlink.folge(roh) else roh }
            val ziel = Kartenlink.zerlege(voll)
            if (ziel == null || !ziel.brauchbar) {
                meldung = getString(R.string.ks_k_kein_ziel)
                return@launch
            }
            val weg = Kartenlink.uebergib(this@KartenlinkActivity, ziel)
            if (weg != null) {
                KieselVerlauf.merke(this@KartenlinkActivity, getString(R.string.ks_k_an_osmand, weg))
                finish()
            } else {
                meldung = getString(R.string.ks_k_abgelehnt)
            }
        }
    }
}
