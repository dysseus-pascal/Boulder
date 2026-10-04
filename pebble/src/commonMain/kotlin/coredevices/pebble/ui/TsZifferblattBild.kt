package coredevices.pebble.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import coredevices.pebble.rememberLibPebble
import coredevices.pebble.timestyle.TsBedrockEinstellungen
import coredevices.pebble.timestyle.TsBedrockNeusetzen
import coredevices.pebble.timestyle.TsWidget
import coredevices.pebble.timestyle.bild.TsDaten
import coredevices.pebble.timestyle.bild.TsDatenQuelle
import coredevices.pebble.timestyle.bild.TsRessourcen
import coredevices.pebble.timestyle.bild.TsRessourcenSpeicher
import coredevices.pebble.timestyle.bild.TsZustand
import coredevices.pebble.timestyle.bild.tsArgb
import coredevices.pebble.timestyle.bild.tsDatenLesen
import coredevices.pebble.timestyle.bild.tsVorschauZustand
import coredevices.pebble.timestyle.bild.tsZifferblatt
import coredevices.pebble.timestyle.tsAusHex
import coredevices.pebble.timestyle.tsFirmwareBuild
import coredevices.pebble.timestyle.tsUvJeStunde
import coredevices.pebble.timestyle.wieDieUhr
import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.ConnectedPebbleDevice
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.database.entity.BoolWatchPref
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlin.time.Clock
import kotlin.time.Instant

/*
 * Boulder: TIMESTYLE BEDROCK ALS BILD IM TELEFON - die Vorschau der
 * Einstellungen und die Kachel unter Apps > Faces zeichnet derselbe Nachbau
 * der Firmware (timestyle/bild, gegen Emulatorbilder auf das Pixel geprueft).
 * Hier nur noch: die Pixel als Bild, in ganzzahliger Vergroesserung ohne
 * Glaettung, damit jedes Uhrpixel ein scharfes Quadrat bleibt.
 */

private const val BREITE = 200
private const val HOEHE = 228

/**
 * Die Pixel (GColor8) als Bild. Je Zeile ein Rechteck pro Farblauf, ohne
 * Kantenglaettung auf ganzen Koordinaten - so landet jede Farbe genau auf
 * ihrem Pixel (gemeinsamer Code, auch fuer iOS).
 */
internal fun tsAlsBild(pixel: IntArray): ImageBitmap {
    val bild = ImageBitmap(BREITE, HOEHE)
    val leinwand = Canvas(bild)
    val farbe = Paint().apply { isAntiAlias = false }
    for (y in 0 until HOEHE) {
        var x = 0
        while (x < BREITE) {
            val g = pixel[y * BREITE + x]
            var ende = x + 1
            while (ende < BREITE && pixel[y * BREITE + ende] == g) ende++
            farbe.color = Color(tsArgb(g))
            leinwand.drawRect(Rect(x.toFloat(), y.toFloat(), ende.toFloat(), (y + 1).toFloat()), farbe)
            x = ende
        }
    }
    return bild
}

/** Die Ressourcen des Bildes; null, solange sie laden (oder wenn sie fehlen - siehe Log). */
@Composable
internal fun rememberTsRessourcen(): TsRessourcen? =
    produceState<TsRessourcen?>(null) { value = TsRessourcenSpeicher.hole() }.value

/**
 * Die Zeit fuer das Bild: jede Minute neu, mit dem Widget "Seconds" jede
 * Sekunde - wie die Uhr (main.c abonniert dann SECOND_UNIT).
 */
@Composable
internal fun rememberTsJetzt(sekuendlich: Boolean): Instant =
    produceState(Clock.System.now(), sekuendlich) {
        while (true) {
            val jetzt = Clock.System.now()
            value = jetzt
            val ms = jetzt.toEpochMilliseconds()
            val schritt = if (sekuendlich) 1000L else 60_000L
            delay(schritt - ms % schritt + 5)
        }
    }.value

/** Was die Vorschau ueber Uhr und Telefon weiss (24 h, Einheit, Akku, Verbindung, Firmware). */
internal data class TsUmgebung(
    val vierundzwanzig: Boolean,
    val metrisch: Boolean,
    val akku: Int?,
    /** Die Uhr ist verbunden (ohne bekannte Uhr: ja - dann gibt es nichts zu vergleichen). */
    val verbunden: Boolean,
    val build: Int?,
)

/** Liest 24 h der Uhr, ihre Distanzeinheit, den Akku, die Verbindung und den Build der Uhr. */
@Composable
internal fun rememberTsUmgebung(): TsUmgebung {
    val libPebble = rememberLibPebble()
    val prefs by remember(libPebble) { libPebble.watchPrefs }.collectAsState(emptyList())
    val gesundheit by remember(libPebble) { libPebble.healthSettings }.collectAsState(null)
    val uhr = lastConnectedWatch()
    // Die 24-h-Einstellung DER UHR, nicht die des Telefons: die Uhr rechnet
    // Uhrzeit und zweite Zeitzone damit (clock_is_24h_style).
    val vierundzwanzig = prefs.firstOrNull { it.pref == BoolWatchPref.Clock24h }?.value as? Boolean
        ?: BoolWatchPref.Clock24h.defaultValue
    return TsUmgebung(
        vierundzwanzig = vierundzwanzig,
        metrisch = gesundheit?.imperialUnits != true,
        akku = (uhr as? ConnectedPebbleDevice)?.batteryLevel,
        verbunden = uhr == null || uhr is ConnectedPebbleDevice,
        build = tsFirmwareBuild(uhr?.runningFwVersion),
    )
}

/** Boulders Daten ueber die Uhr (LibPebble) als Quelle fuer tsDatenLesen. */
internal class TsLibPebbleQuelle(private val libPebble: LibPebble) : TsDatenQuelle {
    override suspend fun wetter() = libPebble.wetterDerUhr()
    override suspend fun schritteUndDistanzCm(von: Long, bis: Long) =
        libPebble.getTotalHealthData(von, bis)?.let { (it.steps ?: 0L) to (it.distanceCm ?: 0L) }
    override suspend fun letzterPuls() = libPebble.getLatestHeartRateReading()
    override suspend fun naechte(von: Long, bis: Long) = libPebble.getNaechte(von, bis)
    override suspend fun nachtAbschnitte(von: Long, bis: Long) = libPebble.getNachtAbschnitte(von, bis)
}

private val tsDatenLogger = Logger.withTag("TsDaten")

/**
 * Wetter, Schritte, Distanz, Puls und Schlaf, wie die Uhr sie gerade hat -
 * einmal je Minute aus Boulders Datenbank (die Uhr zeigt Minuten) und bei
 * jedem Wechsel der Verbindung. Bis zum ersten Lesen: [TsDaten.LEER].
 */
@Composable
internal fun rememberTsDaten(jetzt: Instant, verbunden: Boolean, build: Int?): TsDaten {
    val libPebble = rememberLibPebble()
    val quelle = remember(libPebble) { TsLibPebbleQuelle(libPebble) }
    val minute = jetzt.epochSeconds / 60
    val uvJeStunde = tsUvJeStunde(build)
    return produceState(TsDaten.LEER.copy(verbunden = verbunden), minute, verbunden, uvJeStunde) {
        try {
            value = withContext(Dispatchers.Default) {
                tsDatenLesen(quelle, jetzt, TimeZone.currentSystemDefault(), verbunden, uvJeStunde)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Das Bild bleibt beim letzten Stand; der Grund steht im Log.
            tsDatenLogger.e(e) { "TimeStyle preview: reading weather/health from Boulder failed" }
        }
    }.value
}

/** Die gespeicherten Einstellungen von TimeStyle Bedrock (Vorgabe, solange keine gesetzt sind). */
@Composable
internal fun rememberTsGespeichert(): TsBedrockEinstellungen {
    val libPebble = rememberLibPebble()
    val hex by remember(libPebble) { libPebble.watchPrefs.map { TsBedrockNeusetzen.ausListe(it) } }.collectAsState(null)
    return remember(hex) { tsAusHex(hex) }
}

/**
 * Die ganzzahlige Vergroesserung fuer eine gewuenschte Hoehe von [wunschPx]
 * Pixeln: die naechste ganze Zahl, mindestens 1 - so bleibt jedes Uhrpixel ein
 * scharfes Quadrat aus gleich vielen Telefonpixeln (Pixel 8, 2,625 px/dp:
 * 160 dp = 420 px -> Faktor 2 = 456 px).
 */
internal fun tsFaktor(wunschPx: Float): Int = maxOf(1, kotlin.math.round(wunschPx / HOEHE).toInt())

/**
 * Das Zifferblatt mit [einstellungen] so, wie die Uhr mit [umgebung] es
 * zeigt (alte Firmware: LECO und nur ihre Sprachen). Groesse: mit [wunschHoehe]
 * die ganzzahlige Vergroesserung, die dieser Hoehe am naechsten kommt
 * (tsFaktor); ohne die groesste ganzzahlige, die in den Platz passt - nur wo
 * nicht einmal 1:1 passt (kleine Kachel), wird geglaettet verkleinert.
 */
@Composable
internal fun TsZifferblattBild(
    einstellungen: TsBedrockEinstellungen,
    umgebung: TsUmgebung,
    beschreibung: String?,
    modifier: Modifier = Modifier,
    wunschHoehe: Dp? = null,
) {
    val ressourcen = rememberTsRessourcen()
    val jetzt = rememberTsJetzt(sekuendlich = einstellungen.zeigt(TsWidget.SEKUNDEN))
    val wieUhr = remember(einstellungen, umgebung.build) { einstellungen.wieDieUhr(umgebung.build) }
    val daten = rememberTsDaten(jetzt, umgebung.verbunden, umgebung.build)
    val zustand: TsZustand = remember(jetzt, umgebung, daten) {
        tsVorschauZustand(jetzt, TimeZone.currentSystemDefault(), umgebung.vierundzwanzig, umgebung.metrisch, umgebung.akku, daten)
    }
    val bild by produceState<ImageBitmap?>(null, wieUhr, zustand, ressourcen) {
        val r = ressourcen ?: return@produceState
        value = withContext(Dispatchers.Default) { tsAlsBild(tsZifferblatt(wieUhr, zustand, r)) }
    }
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val dichte = LocalDensity.current
        val passtPx = with(dichte) { minOf(maxWidth.toPx() / BREITE, maxHeight.toPx() / HOEHE) }
        val faktor: Float = when {
            wunschHoehe != null -> tsFaktor(with(dichte) { wunschHoehe.toPx() }).toFloat()
            passtPx >= 1f -> passtPx.toInt().toFloat()
            else -> passtPx
        }
        val breiteDp = with(dichte) { (BREITE * faktor).toDp() }
        val hoeheDp = with(dichte) { (HOEHE * faktor).toDp() }
        val b = bild
        if (b != null) {
            Image(
                bitmap = b,
                contentDescription = beschreibung,
                modifier = Modifier.size(breiteDp, hoeheDp),
                contentScale = ContentScale.FillBounds,
                filterQuality = if (faktor >= 1f) FilterQuality.None else FilterQuality.Medium,
            )
        } else {
            Box(Modifier.size(breiteDp, hoeheDp))
        }
    }
}

/** Die Kachel unter Apps > Faces: das Zifferblatt mit den AKTUELLEN Einstellungen. */
@Composable
internal fun TsBedrockKachel(modifier: Modifier) {
    TsZifferblattBild(rememberTsGespeichert(), rememberTsUmgebung(), "TimeStyle Bedrock", modifier)
}
