package coredevices.pebble.kiesel.ansicht

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coredevices.pebble.R
import coredevices.pebble.kiesel.Trainingsanalyse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.BitmapPool
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline
import java.io.File

/** Wie viele Karten gerade leben (nur im Hauptfaden) - die letzte leert den Kachelpool. */
private var offeneKarten = 0
private val hauptfaden = Handler(Looper.getMainLooper())
private val poolLeeren = Runnable { if (offeneKarten == 0) BitmapPool.getInstance().clearBitmapPool() }

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
            // Mit festem Grundordner sucht osmdroid nicht je Karte in /proc/mounts nach
            // Speicher: das kostete ~30 ms Hauptfaden und ~260 Logzeilen.
            osmdroidBasePath = File(context.cacheDir, "osmdroid")
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
        offeneKarten++
        hauptfaden.removeCallbacks(poolLeeren)
        karte.onResume()
        onDispose {
            lebenslauf.removeObserver(beobachter)
            karte.onDetach()
            // DIE LETZTE KARTE LEERT DEN KACHELPOOL: osmdroid gibt ihn nie frei (5-15 MB).
            // Zwei Sekunden spaeter - onDetach legt die Kacheln im Hintergrund dorthin.
            if (--offeneKarten == 0) hauptfaden.postDelayed(poolLeeren, 2_000)
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
 * Farbstufe (zwoelf Stufen) werden ein Lauf - ein paar Dutzend statt
 * Tausend -, gezeichnet von EINEM Overlay ([Farbspur]). Ein weisser Saum
 * darunter haelt die bunte Linie auf jeder Karte lesbar; bei einem Sprung
 * reisst beides ab.
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
    // Zwoelf Stufen der Tempo-Skala als FLAECHE: die Kacheln sind in beiden Schemata hell.
    val dunkel = Farben.dunkel()
    val stufen = remember(dunkel) { (0..11).map { Farben.tempo(it / 11f).flaecheIm(dunkel).alsInt() } }
    // Der update-Block laeuft bei jeder Neukomposition (etwa wenn die kcal nachkommen); gebaut wird nur mit neuen Daten.
    val gebaut = remember { arrayOfNulls<Any>(1) }
    AndroidView(
        factory = { karte },
        modifier = modifier,
        update = { k ->
            val daten = listOf(strecke, werte, von, bis, stufen, start, ende)
            if (gebaut[0] == daten) return@AndroidView
            gebaut[0] = daten
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
            val laeufe = mutableListOf<Farbspur.Lauf>()
            stuecke.filter { it.size >= 2 }.forEach { idx ->
                var a = 0
                while (a < idx.size - 1) {
                    val s = stufe(werte[idx[a]])
                    var b = a + 1
                    while (b < idx.size - 1 && stufe(werte[idx[b]]) == s) b++
                    laeufe += Farbspur.Lauf(s, idx.subList(a, b + 1).toIntArray())
                    a = b
                }
            }
            val dick = 5f * k.resources.displayMetrics.density
            k.overlays += Farbspur(
                geo,
                stuecke.filter { it.size >= 2 }.map { it.toIntArray() },
                laeufe,
                linie(Color.WHITE, dick + 3f * k.resources.displayMetrics.density),
                stufen.map { linie(it, dick) },
            )
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
            // Start gruen, Ziel rot wie eine Ampel - fest, weil die Kacheln in beiden
            // Schemata hell sind; keine Groesse und kein Urteil aus Farben.kt passt dazu.
            marke(geo.first(), start, Color.rgb(0x2E, 0x7D, 0x32))
            marke(geo.last(), ende, Color.rgb(0xC6, 0x28, 0x28))
            val rahmen = stuecke.maxBy { it.size }.map { geo[it] }.takeIf { it.size >= 2 } ?: geo
            k.post { k.zoomToBoundingBox(BoundingBox.fromGeoPoints(rahmen).increaseByScale(1.25f), false) }
            k.invalidate()
        },
    )
}

/** Der Pinsel einer osmdroid-Polyline (Strich, geglaettet, stumpfe Enden) in Farbe und Breite. */
private fun linie(farbe: Int, breite: Float) = Paint().apply {
    color = farbe
    strokeWidth = breite
    style = Paint.Style.STROKE
    isAntiAlias = true
}

/**
 * Die Farbstrecke als EIN Overlay: ~216 Polylines bei 441 Punkten projizierte
 * osmdroid in jedem Bild einzeln. Gezeichnet wird dasselbe, auch in derselben
 * Reihenfolge - auf Hin- und Rueckweg liegt der spaetere Lauf oben.
 */
private class Farbspur(
    private val punkte: List<GeoPoint>,
    /** Die Punktnummern je Stueck zwischen Spruengen, ab zwei Punkten. */
    private val stuecke: List<IntArray>,
    private val laeufe: List<Lauf>,
    private val saum: Paint,
    private val stufen: List<Paint>,
) : Overlay() {
    /** Ein Lauf einer Farbstufe: seine Punktnummern, der letzte ist der erste des naechsten. */
    class Lauf(val stufe: Int, val idx: IntArray)

    private val xy = FloatArray(punkte.size * 2)
    private val linien = FloatArray(maxOf(punkte.size - 1, 1) * 4)
    private val p = Point()
    // Die Bildpunkte gelten fuer denselben Ausschnitt; beim Rollen der Seite zeichnet die Karte unbewegt neu.
    private var projiziert: Triple<Double, Long, Long>? = null

    override fun draw(c: Canvas, pj: Projection) {
        val stand = Triple(pj.zoomLevel, pj.offsetX, pj.offsetY)
        if (stand != projiziert) {
            for (i in punkte.indices) {
                pj.toPixels(punkte[i], p)
                xy[2 * i] = p.x.toFloat()
                xy[2 * i + 1] = p.y.toFloat()
            }
            projiziert = stand
        }
        stuecke.forEach { zeichne(c, it, saum) }
        laeufe.forEach { zeichne(c, it.idx, stufen[it.stufe]) }
    }

    private fun zeichne(c: Canvas, idx: IntArray, pinsel: Paint) {
        var n = 0
        for (j in 0 until idx.size - 1) {
            val a = idx[j]
            val b = idx[j + 1]
            linien[n++] = xy[2 * a]; linien[n++] = xy[2 * a + 1]
            linien[n++] = xy[2 * b]; linien[n++] = xy[2 * b + 1]
        }
        if (n > 0) c.drawLines(linien, 0, n, pinsel)
    }
}

/**
 * Die Karte als feste Vorschau, etwa im Sport-Reiter: die Strecke in der
 * Farbe der Sportart auf echten Kacheln. SIE NIMMT KEINE GESTEN AN - eine
 * Karte, die mitten in einer Liste wischt, haelt die Liste fest. Ein Tipp
 * oeffnet [onClick], dort ist die Karte beweglich.
 *
 * SIE WIRD EIN STANDBILD, sobald alle Kacheln da sind: lebend zeichnete sie beim
 * Rollen in jedem Bild neu und hielt 5-6 MB Kacheln. Fehlen Kacheln (ohne Netz),
 * bleibt sie lebend.
 */
@Composable
internal fun Kartenvorschau(punkte: List<Pair<Double, Double>>, ton: androidx.compose.ui.graphics.Color, modifier: Modifier, onClick: () -> Unit) {
    val context = LocalContext.current
    BoxWithConstraints(modifier) {
        val breite = constraints.maxWidth
        val hoehe = constraints.maxHeight
        val fest = constraints.hasBoundedWidth && constraints.hasBoundedHeight
        val schluessel = remember(punkte, ton, breite, hoehe) { Standbild.schluessel(punkte, ton.alsInt(), breite, hoehe) }
        var bild by remember(schluessel) { mutableStateOf<ImageBitmap?>(null) }
        var lebend by remember(schluessel) { mutableStateOf(!fest) }
        LaunchedEffect(schluessel) {
            if (!fest) return@LaunchedEffect
            val gemerkt = withContext(Dispatchers.IO) { Standbild.lies(context, schluessel) }
            if (gemerkt != null) bild = gemerkt.asImageBitmap() else lebend = true
        }
        val b = bild
        if (b != null) {
            Image(b, null, Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
            // Verlaesst das Bild den Schirm, gibt recycle() seine ~1,5 MB Pixel sofort frei: ein GC kommt im
            // stillen Prozess lange nicht, und mit Bedienungshilfen haelt Compose den alten Knoten noch fest.
            DisposableEffect(b) { onDispose { Standbild.gibFrei(b.asAndroidBitmap()) } }
        } else if (lebend) {
            LebendeVorschau(punkte, ton, Modifier.matchParentSize(), breite, hoehe, fotografiere = fest) { foto ->
                bild = foto.asImageBitmap()
                Standbild.schreibe(context, schluessel, foto)
            }
        }
        // Ueber der Karte: faengt den Tipp ab, laesst das Wischen der Liste durch.
        // Fuer TalkBack unsichtbar - das Ziel ist die Karte darum, ein zweites ohne Namen stoerte.
        Box(Modifier.matchParentSize().clickable(onClick = onClick).semantics { hideFromAccessibility() })
    }
}

/** Die lebende Vorschau; mit [fotografiere] meldet sie [fertig] ein Bild, sobald alle Kacheln da sind. */
@Composable
private fun LebendeVorschau(
    punkte: List<Pair<Double, Double>>, ton: androidx.compose.ui.graphics.Color, modifier: Modifier,
    breite: Int, hoehe: Int, fotografiere: Boolean, fertig: (Bitmap) -> Unit,
) {
    val karte = rememberKarte(beweglich = false)
    val gebaut = remember { arrayOfNulls<Any>(1) }
    AndroidView(
        factory = { karte },
        modifier = modifier,
        update = { k ->
            val daten = listOf(punkte, ton)
            if (gebaut[0] == daten) return@AndroidView
            gebaut[0] = daten
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
            k.post {
                k.zoomToBoundingBox(BoundingBox.fromGeoPoints(geo).increaseByScale(1.3f), false)
                // ERST NACH DEM ZOOM zaehlt ein fertiger Kachellauf: davor zeigte die
                // Karte den Ausschnitt, mit dem sie entstand.
                if (fotografiere) warteAufKacheln(k, breite, hoehe, fertig)
            }
            k.invalidate()
        },
    )
}

/**
 * Fotografiert die Karte nach dem ersten vollstaendigen Kachellauf (aktuell oder
 * abgelaufen, kein Platzhalter) - per post: mitten im Zeichnen laeuft die Liste der runAfters.
 */
private fun warteAufKacheln(k: MapView, breite: Int, hoehe: Int, fertig: (Bitmap) -> Unit) {
    val zustand = k.overlayManager.tilesOverlay.tileStates
    val nachLauf = object : Runnable {
        var erledigt = false
        override fun run() {
            if (erledigt || zustand.total == 0 || zustand.upToDate + zustand.expired != zustand.total) return
            erledigt = true
            k.post {
                zustand.runAfters.remove(this)
                if (k.width != breite || k.height != hoehe || !k.isAttachedToWindow) return@post
                val foto = try {
                    Bitmap.createBitmap(breite, hoehe, Bitmap.Config.ARGB_8888).also { k.draw(Canvas(it)) }
                } catch (e: Exception) {
                    Log.w("Kartenvorschau", "Standbild: " + e.message)
                    return@post
                }
                fertig(foto)
            }
        }
    }
    zustand.runAfters.add(nachLauf)
    k.invalidate()
}

/** Das Standbild der Vorschau im Cache - nur eines, die Vorschau zeigt immer das letzte Training. */
private object Standbild {
    private const val ORDNER = "kartenvorschau"

    /** Ein Faden der Reihe nach: [gibFrei] kommt erst dran, wenn ein [schreibe] desselben Bildes fertig ist. */
    private val schreiber = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    /** Strecke, Farbe und Groesse in Pixeln als Name - aendert sich eines, passt das alte Bild nicht mehr. */
    fun schluessel(punkte: List<Pair<Double, Double>>, ton: Int, breite: Int, hoehe: Int): String {
        var h = 1125899906842597L
        for ((lat, lon) in punkte) {
            h = 31 * h + lat.toRawBits()
            h = 31 * h + lon.toRawBits()
        }
        return "${java.lang.Long.toHexString(h)}-${Integer.toHexString(ton)}-${breite}x$hoehe"
    }

    private fun datei(context: Context, schluessel: String) = File(File(context.cacheDir, ORDNER), "$schluessel.png")

    fun lies(context: Context, schluessel: String): Bitmap? = try {
        datei(context, schluessel).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }
    } catch (e: Exception) {
        null
    }

    /** Verlustfrei als PNG - das Bild soll aussehen wie die Karte, nicht wie ihre Kopie. */
    fun schreibe(context: Context, schluessel: String, foto: Bitmap) {
        schreiber.launch {
            try {
                val ziel = datei(context, schluessel)
                ziel.parentFile?.let { o -> o.mkdirs(); o.listFiles()?.forEach { if (it.name != ziel.name) it.delete() } }
                val teil = File(ziel.path + ".teil")
                teil.outputStream().use { foto.compress(Bitmap.CompressFormat.PNG, 100, it) }
                teil.renameTo(ziel)
            } catch (e: Exception) {
                Log.w("Kartenvorschau", "Standbild sichern: " + e.message)
            }
        }
    }

    /** Erst nach dem Schreiben: ein frueheres recycle() liesse compress() scheitern, das Bild fehlte im Cache. */
    fun gibFrei(foto: Bitmap) {
        schreiber.launch { foto.recycle() }
    }
}
