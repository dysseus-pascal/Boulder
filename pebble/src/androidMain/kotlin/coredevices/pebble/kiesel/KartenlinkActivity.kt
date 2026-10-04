package coredevices.pebble.kiesel

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import coredevices.pebble.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Ein Kartenlink, weitergereicht an OsmAnd.
 *
 * Ein eigenes Fenster, weil ein Kurzlink erst aufgeloest werden muss - ohne
 * Rueckmeldung stuende man Sekunden vor einem Schirm, auf dem nichts geschieht.
 * Die Google-Maps-Adressen muss man Boulder einmal von Hand zuordnen
 * (Einstellungen › Apps › Boulder › Standardmaessig oeffnen); geo: geht sofort.
 *
 * DIE ARBEIT STECKT IM [KartenlinkModell]: es ueberlebt das Drehen, ein Link
 * wird also nicht zweimal aufgeloest und OsmAnd nicht zweimal gestartet. Ein
 * zweiter Link, waehrend das Fenster offen ist (singleTask), kommt ueber
 * [onNewIntent] und loest den ersten ab.
 */
class KartenlinkActivity : ComponentActivity() {
    private val modell: KartenlinkModell by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val meldung by modell.meldung.collectAsState()
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
        lifecycleScope.launch {
            modell.fertig.first { it }
            finish()
        }
        // Nach dem Drehen arbeitet das Modell schon. Ein neues Modell nach dem Ende des Prozesses
        // zeigt die gemerkte Meldung; lief der Link noch, ist er mit dem Prozess abgebrochen.
        if (!modell.begonnen) {
            val gemerkt = savedInstanceState?.getString(MELDUNG)
            if (gemerkt != null) modell.zeige(gemerkt) else modell.verarbeite(applicationContext, intent?.dataString)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        modell.verarbeite(applicationContext, intent.dataString)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        modell.meldung.value?.let { outState.putString(MELDUNG, it) }
    }

    private companion object {
        const val MELDUNG = "meldung"
    }
}

/** Was das Kartenlink-Fenster tut und zeigt - an die App gebunden, nicht an das Fenster. */
internal class KartenlinkModell : ViewModel() {
    private val _meldung = MutableStateFlow<String?>(null)

    /** Null, solange gelesen wird. */
    val meldung: StateFlow<String?> = _meldung.asStateFlow()

    private val _fertig = MutableStateFlow(false)

    /** Das Ziel ist bei OsmAnd: das Fenster geht zu. */
    val fertig: StateFlow<Boolean> = _fertig.asStateFlow()

    /** Ob dieses Modell schon einen Link hatte. */
    var begonnen = false
        private set

    private var arbeit: Job? = null

    fun zeige(text: String) {
        begonnen = true
        _meldung.value = text
    }

    /**
     * [roh] aufloesen und an OsmAnd geben; ein laufender Link bricht ab - es gilt der zuletzt angetippte.
     * [app]: der Kontext der App, nicht des Fensters - die Arbeit ueberlebt das Fenster.
     */
    fun verarbeite(app: Context, roh: String?) {
        begonnen = true
        arbeit?.cancel()
        _meldung.value = null
        if (roh.isNullOrBlank()) {
            _meldung.value = app.getString(R.string.ks_k_kein_link)
            return
        }
        // Ganz im Hintergrund: Netz und OsmAnd; die Meldungen sind StateFlows, das Fenster liest sie.
        arbeit = viewModelScope.launch(Dispatchers.IO) {
            val voll = if (Kartenlink.istKurzlink(roh)) Kartenlink.folge(roh) else roh
            // Das Aufloesen laesst sich nicht unterbrechen: kam inzwischen ein neuer Link, gilt der.
            ensureActive()
            val ziel = Kartenlink.zerlege(voll)
            if (ziel == null || !ziel.brauchbar) {
                _meldung.value = app.getString(R.string.ks_k_kein_ziel)
                return@launch
            }
            val weg = Kartenlink.uebergib(app, ziel)
            if (weg != null) {
                KieselVerlauf.merke(app, app.getString(R.string.ks_k_an_osmand, weg))
                _fertig.value = true
            } else {
                _meldung.value = app.getString(R.string.ks_k_abgelehnt)
            }
        }
    }
}
