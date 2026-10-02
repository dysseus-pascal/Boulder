package coredevices.pebble.kiesel.ansicht

import android.content.Context
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
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * Die duenne Seite von kiesel/Formate.kt, die das laufende Telefon braucht:
 * Gravels Einheiten-Schalter (in Compose und ausserhalb, fuers Widget), die
 * Stundenwahl fuer Achsen, und die alten Format-Helfer als Weiterleitung,
 * bis die Seiten Formate direkt nehmen (Etappe 2, Teil b).
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

// --- Weiterleitungen -----------------------------------------------------------------

@Deprecated("Formate.uhrzeit(context, t)", ReplaceWith("Formate.uhrzeit(context, t)", "coredevices.pebble.kiesel.Formate"))
internal fun uhrzeit(context: Context, t: Instant): String = Formate.uhrzeit(context, t)

/**
 * Eine Uhrzeit, und der Tag davor, wenn sie nicht von heute ist - jetzt in
 * der einen Form von [Formate.zeitpunkt] ("yesterday, 9:14 PM"). Heute ohne
 * Tag wie bisher; Formate selbst nennt "Today" erst, wenn man es abwaehlt.
 */
@Deprecated(
    "Formate.zeitpunkt(context, t, klein = true, heuteOhneTag = true) - heuteOhneTag nur im Zeitstrahl von heute",
    ReplaceWith("Formate.zeitpunkt(context, t, klein = true, heuteOhneTag = true)", "coredevices.pebble.kiesel.Formate"),
)
internal fun zeitpunkt(context: Context, t: Instant): String = Formate.zeitpunkt(context, t, klein = true, heuteOhneTag = true)

@Deprecated("Formate.stundenMinuten", ReplaceWith("Formate.stundenMinuten(minuten)", "coredevices.pebble.kiesel.Formate"))
internal fun stundenMinuten(minuten: Int): String = Formate.stundenMinuten(minuten)

@Deprecated("Formate.dauer", ReplaceWith("Formate.dauer(minuten)", "coredevices.pebble.kiesel.Formate"))
internal fun dauerText(minuten: Long): String = Formate.dauer(minuten)

@Deprecated("Formate.dauerTeile - liefert eine Angabe", ReplaceWith("Formate.dauerTeile(minuten)", "coredevices.pebble.kiesel.Formate"))
internal fun dauerTeile(minuten: Long): Pair<String, String> = Formate.dauerTeile(minuten).let { it.zahl to it.einheit.orEmpty() }

@Deprecated("Formate.distanz(meter, einheitenImperial())", ReplaceWith("Formate.distanz(meter, false).text", "coredevices.pebble.kiesel.Formate"))
internal fun kmText(meter: Double): String = Formate.distanz(meter, false).text

@Deprecated("Formate.datumKurz(tag)", ReplaceWith("Formate.datumKurz(t)", "coredevices.pebble.kiesel.Formate"))
internal fun kurzDatum(t: LocalDate): String = Formate.datumKurz(t)

@Deprecated("Formate.datumMuster")
internal fun kurzDatumsformat(): DateTimeFormatter {
    val l = Locale.getDefault()
    return DateTimeFormatter.ofPattern(Formate.datumMuster(l), l)
}
