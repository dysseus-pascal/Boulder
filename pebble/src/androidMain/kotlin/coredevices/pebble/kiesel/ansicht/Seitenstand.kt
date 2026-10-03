package coredevices.pebble.kiesel.ansicht

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.rebble.libpebblecommon.connection.LibPebble
import org.koin.compose.koinInject

/**
 * Was eine Seite zeigt, geladen nach einem Muster fuer alle Seiten.
 *
 * ZUERST DER LETZTE STAND: [zuletzt] ist, was der Lader im Prozess zuletzt
 * las (etwa [coredevices.pebble.kiesel.Koerperdaten.zuletzt]). Jede Rueckkehr
 * auf eine Seite baut sie neu, und ohne ihn stuende dort erst ein Ladekreis.
 * Null nur, bis zum ersten Mal geladen ist.
 *
 * Neu geladen wird beim Aufbau, mit jedem neuen [anlass] und - mit
 * [rueckkehr] - wenn die Seite wieder in den Vordergrund kommt, etwa aus
 * den Systemeinstellungen. Das erste ON_RESUME zaehlt nicht: es kommt mit
 * dem Aufbau, und sonst laede jede Seite beim Oeffnen zweimal.
 *
 * [lade] gibt den fertigen Stand zurueck. Was vorher schon steht, zeigt es
 * mit `zeige` - die Seite muss dann nicht auf langsame Teile warten.
 */
@Composable
internal fun <T> rememberStand(
    zuletzt: T?,
    vararg anlass: Any?,
    rueckkehr: Boolean = true,
    lade: suspend (zeige: (T) -> Unit) -> T,
): T? {
    var stand by remember { mutableStateOf(zuletzt) }
    var rueckkehren by remember { mutableIntStateOf(0) }
    if (rueckkehr) LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { rueckkehren++ }
    // Erst ab dem zweiten ON_RESUME ein neuer Schluessel, siehe oben.
    LaunchedEffect(*anlass, maxOf(rueckkehren, 1)) { stand = lade { stand = it } }
    return stand
}

/**
 * Ein Zaehler, der mit jedem Schub neuer Daten von der Uhr steigt - als
 * [rememberStand]-Anlass fuer Seiten, die Uhrdaten zeigen.
 */
@Composable
internal fun rememberUhrschub(): Int {
    val libPebble = koinInject<LibPebble>()
    var schuebe by remember { mutableIntStateOf(0) }
    LaunchedEffect(libPebble) { libPebble.healthDataUpdated.collect { schuebe++ } }
    return schuebe
}
