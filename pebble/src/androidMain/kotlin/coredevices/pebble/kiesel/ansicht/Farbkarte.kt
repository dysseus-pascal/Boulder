package coredevices.pebble.kiesel.ansicht

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.foundation.clickable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import coredevices.pebble.R
import coredevices.pebble.kiesel.Trainingsanalyse
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File

/**
 * Eine osmdroid-Karte, die dem Lebenslauf des Schirms folgt.
 *
 * DIE KARTE BRAUCHT IHREN LEBENSLAUF: osmdroid laedt Kacheln nur zwischen
 * onResume und onPause. Ohne die Weitergabe bliebe sie grau, sobald man die
 * App einmal verlassen hat.
 */
@Composable
private fun rememberKarte(beweglich: Boolean = true): MapView {
    val context = LocalContext.current
    val karte = remember {
        // OpenStreetMap verlangt eine Kennung je App - und einen eigenen Ablageort.
        Configuration.getInstance().apply {
            userAgentValue = context.packageName
            osmdroidTileCache = File(context.cacheDir, "kacheln")
        }
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(beweglich)
            zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
        }
    }
    val lebenslauf = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lebenslauf) {
        val beobachter = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> karte.onResume()
                Lifecycle.Event.ON_PAUSE -> karte.onPause()
                else -> Unit
            }
        }
        lebenslauf.addObserver(beobachter)
        karte.onResume()
        onDispose {
            lebenslauf.removeObserver(beobachter)
            karte.onDetach()
        }
    }
    return karte
}

/**
 * Die Strecke, gefaerbt nach einem Wert je Punkt - Tempo (blau langsam, rot
 * schnell) oder beim Wandern die Steigung (blau bergab, rot bergauf).
 *
 * NICHT JEDES STUECK EINE EIGENE LINIE (aus Kiesel-Helper): tausend
 * Polylines machen die Karte zaeh. Aufeinander folgende Punkte derselben
 * Farbstufe (zwoelf Stufen) werden eine Linie - ein paar Dutzend statt
 * Tausend. Ein weisser Saum darunter haelt die bunte Linie auf jeder Karte
 * lesbar; bei einem Sprung reisst beides ab.
 */
@Composable
internal fun Farbkarte(
    strecke: List<Trainingsanalyse.Streckenpunkt>,
    werte: List<Double>,
    von: Double,
    bis: Double,
    modifier: Modifier = Modifier,
) {
    val karte = rememberKarte()
    val start = stringResource(R.string.ks_t_start)
    val ende = stringResource(R.string.ks_t_ende)
    AndroidView(
        factory = { karte },
        modifier = modifier,
        update = { k ->
            k.overlays.clear()
            if (strecke.size < 2) return@AndroidView
            fun stufe(w: Double) = (((w - von) / (bis - von).coerceAtLeast(0.001)).coerceIn(0.0, 1.0) * 11).toInt()
            val geo = strecke.map { GeoPoint(it.lat, it.lon) }

            // Die Stuecke zwischen den Spruengen, fuer Saum und Ausschnitt.
            val stuecke = mutableListOf(mutableListOf(0))
            for (i in 1 until strecke.size) {
                if (strecke[i].nachSprung) stuecke += mutableListOf<Int>()
                stuecke.last() += i
            }
            val dick = 5f * k.resources.displayMetrics.density
            stuecke.filter { it.size >= 2 }.forEach { idx ->
                k.overlays += Polyline(k).apply {
                    setPoints(idx.map { geo[it] })
                    outlinePaint.color = Color.WHITE
                    outlinePaint.strokeWidth = dick + 3f * k.resources.displayMetrics.density
                }
            }
            stuecke.filter { it.size >= 2 }.forEach { idx ->
                var a = 0
                while (a < idx.size - 1) {
                    val s = stufe(werte[idx[a]])
                    var b = a + 1
                    while (b < idx.size - 1 && stufe(werte[idx[b]]) == s) b++
                    k.overlays += Polyline(k).apply {
                        setPoints(idx.subList(a, b + 1).map { geo[it] })
                        outlinePaint.color = tempofarbe(s / 11f).alsInt()
                        outlinePaint.strokeWidth = dick
                    }
                    a = b
                }
            }
            fun marke(p: GeoPoint, text: String, ton: Int) {
                k.overlays += Marker(k).apply {
                    position = p
                    title = text
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    icon = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(ton)
                        val d = k.resources.displayMetrics.density
                        setStroke((2 * d).toInt(), Color.WHITE)
                        setSize((14 * d).toInt(), (14 * d).toInt())
                    }
                }
            }
            marke(geo.first(), start, Color.rgb(0x2E, 0x7D, 0x32))
            marke(geo.last(), ende, Color.rgb(0xC6, 0x28, 0x28))
            val rahmen = stuecke.maxBy { it.size }.map { geo[it] }.takeIf { it.size >= 2 } ?: geo
            k.post { k.zoomToBoundingBox(BoundingBox.fromGeoPoints(rahmen).increaseByScale(1.25f), false) }
            k.invalidate()
        },
    )
}

/**
 * Die Karte als feste Vorschau, etwa im Sport-Reiter: die Strecke in der
 * Farbe der Sportart auf echten Kacheln. SIE NIMMT KEINE GESTEN AN - eine
 * Karte, die mitten in einer Liste wischt, haelt die Liste fest. Ein Tipp
 * oeffnet [onClick], dort ist die Karte beweglich.
 */
@Composable
internal fun Kartenvorschau(punkte: List<Pair<Double, Double>>, ton: androidx.compose.ui.graphics.Color, modifier: Modifier, onClick: () -> Unit) {
    val karte = rememberKarte(beweglich = false)
    androidx.compose.foundation.layout.Box(modifier) {
        AndroidView(
            factory = { karte },
            modifier = Modifier.matchParentSize(),
            update = { k ->
                k.overlays.clear()
                if (punkte.size < 2) return@AndroidView
                val geo = punkte.map { GeoPoint(it.first, it.second) }
                val d = k.resources.displayMetrics.density
                k.overlays += Polyline(k).apply {
                    setPoints(geo)
                    outlinePaint.color = Color.WHITE
                    outlinePaint.strokeWidth = 7.5f * d
                }
                k.overlays += Polyline(k).apply {
                    setPoints(geo)
                    outlinePaint.color = ton.alsInt()
                    outlinePaint.strokeWidth = 4.5f * d
                }
                k.post { k.zoomToBoundingBox(BoundingBox.fromGeoPoints(geo).increaseByScale(1.3f), false) }
                k.invalidate()
            },
        )
        // Ueber der Karte: faengt den Tipp ab, laesst das Wischen der Liste durch.
        androidx.compose.foundation.layout.Box(
            Modifier.matchParentSize().clickable(onClick = onClick),
        )
    }
}
