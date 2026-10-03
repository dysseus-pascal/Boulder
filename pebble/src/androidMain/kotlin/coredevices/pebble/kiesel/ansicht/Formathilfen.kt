package coredevices.pebble.kiesel.ansicht

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import coredevices.pebble.kiesel.Formate
import io.rebble.libpebblecommon.connection.LibPebble
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.compose.koinInject
import org.koin.mp.KoinPlatform
import java.time.ZonedDateTime
import java.util.Locale

/*
 * Die duenne Seite von kiesel/Formate.kt, die das laufende Telefon braucht:
 * Gravels Einheiten-Schalter (in Compose und ausserhalb, fuers Widget) und
 * die Stundenwahl fuer Achsen. Alles Uebrige formt Formate selbst.
 */

/**
 * Gravels Schalter "Imperial Units" (Health-Einstellungen der Uhr): mi, ft,
 * lb statt km, m, kg. Bis die Einstellung geladen ist, metrisch - das ist
 * Gravels Vorgabe. Jede Seite mit Strecke, Tempo, Hoehe oder Gewicht fragt
 * hier, damit der Schalter nicht nur auf Body wirkt.
 */
@Composable
internal fun einheitenImperial(): Boolean {
    val libPebble = koinInject<LibPebble>()
    val einstellungen by remember(libPebble) { libPebble.healthSettings }.collectAsState(null)
    return einstellungen?.imperialUnits == true
}

/**
 * Derselbe Schalter ohne Compose - fuer das Widget (RemoteViews) und andere
 * Hintergrundarbeit. Wartet hoechstens zwei Sekunden auf die Einstellung;
 * ein Widget, das haengt, waere schlimmer als einmal km statt mi.
 */
internal suspend fun einheitenImperialHolen(libPebble: LibPebble = KoinPlatform.getKoin().get()): Boolean =
    withTimeoutOrNull(2_000) { libPebble.healthSettings.first().imperialUnits } ?: false

/**
 * Die Beschriftung voller Stunden auf Zeitachsen im Format des Telefons:
 * "21" oder "9 PM". Einmal gefragt, dann fuer jeden Strich - ein Bild mit
 * fester 24-Stunden-Achse neben "11:02 PM" las sich wie zwei Uhren.
 */
@Composable
internal fun stundenText(): (ZonedDateTime) -> String {
    val vierundzwanzig = DateFormat.is24HourFormat(LocalContext.current)
    val locale = Locale.getDefault()
    return remember(vierundzwanzig, locale) { { z: ZonedDateTime -> Formate.stunde(z.toLocalTime(), vierundzwanzig, locale) } }
}
