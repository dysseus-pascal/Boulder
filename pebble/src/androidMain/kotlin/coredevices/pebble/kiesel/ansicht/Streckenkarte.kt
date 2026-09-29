package coredevices.pebble.kiesel.ansicht

import android.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import coredevices.pebble.kiesel.Spur
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline
import java.io.File

/**
 * Die Strecke eines Trainings auf einer OpenStreetMap-Karte - wie in
 * Kiesel-Helper mit osmdroid, in Compose eingebettet.
 *
 * DIE KARTE BRAUCHT IHREN LEBENSLAUF: osmdroid laedt Kacheln nur zwischen
 * onResume und onPause. Ohne die Weitergabe bliebe sie grau, sobald man die
 * App einmal verlassen hat.
 */
@Composable
internal fun Streckenkarte(punkte: List<Spur.Punkt>, farbe: Int, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val karte = remember {
        // OpenStreetMap verlangt eine Kennung je App - und einen eigenen Ablageort.
        Configuration.getInstance().apply {
            userAgentValue = context.packageName
            osmdroidTileCache = File(context.cacheDir, "kacheln")
        }
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
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
    AndroidView(
        factory = { karte },
        modifier = modifier,
        update = { k ->
            k.overlays.clear()
            val geo = punkte.map { GeoPoint(it.lat, it.lon) }
            if (geo.size >= 2) {
                // BEI EINEM SPRUNG REISST DIE LINIE AB, statt ihn zu zeichnen:
                // verlorener und wiedergefundener Empfang ist kein Weg. Dieselbe
                // Grenze wie bei der Streckenlaenge (Spur.SPRUNG_MAX).
                val stuecke = mutableListOf(mutableListOf(geo.first()))
                for (i in 1 until punkte.size) {
                    if (Spur.abstand(punkte[i - 1], punkte[i]) > Spur.SPRUNG_MAX) stuecke += mutableListOf<GeoPoint>()
                    stuecke.last() += geo[i]
                }
                stuecke.filter { it.size >= 2 }.forEach { stueck ->
                    k.overlays += Polyline(k).apply {
                        setPoints(stueck)
                        outlinePaint.color = farbe
                        outlinePaint.strokeWidth = 10f
                    }
                }
                // DER AUSSCHNITT GILT DEM LAENGSTEN STUECK: ein einzelner Punkt
                // weit weg (ein alter Standort beim ersten Fix) zoegte die Karte
                // sonst auf einen Kontinent. Erst setzen, wenn sie ihre Groesse kennt.
                val rahmen = stuecke.maxBy { it.size }.takeIf { it.size >= 2 } ?: geo
                k.post {
                    k.zoomToBoundingBox(BoundingBox.fromGeoPoints(rahmen).increaseByScale(1.25f), false)
                }
            }
            k.invalidate()
        },
    )
}

internal fun farbeAlsInt(argb: Long): Int = Color.argb(
    ((argb shr 24) and 0xFF).toInt(),
    ((argb shr 16) and 0xFF).toInt(),
    ((argb shr 8) and 0xFF).toInt(),
    (argb and 0xFF).toInt(),
)
