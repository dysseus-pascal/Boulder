package coredevices.pebble.ui

import CoreRoute
import android.Manifest
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import coreapp.util.generated.resources.Res
import coreapp.util.generated.resources.back
import coredevices.pebble.health.alleRechteJederAkte
import coredevices.pebble.kiesel.Akte
import coredevices.pebble.kiesel.Kieselsport
import coredevices.pebble.kiesel.ablauf.FakeAkte
import io.rebble.libpebblecommon.connection.LibPebble
import kotlinx.coroutines.flow.emptyFlow
import org.jetbrains.compose.resources.stringResource
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import theme.greyScheme
import java.io.File
import java.lang.reflect.Proxy
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * SPORT IST EINE UNTERSEITE VON HEALTH (0.51): kein Reiter mehr, und der Weg
 * aus Health legt Sport auf Health's eigenen Stapel - so fuehren Pfeil und
 * Zurueck-Taste von selbst nach Health.
 *
 * Geprueft mit dem echten Wegeplan von Health ([gesundheitswege]), dem echten
 * NavHost der Navigation und dem echten Sport-Bildschirm ([TrainingScreen])
 * auf einer Akte im Speicher. NACHGESTELLT ist nur die Kopfleiste: sie
 * wiederholt die von WatchHomeScreen (Pfeil, sobald etwas unter der Seite
 * liegt; Titel gruen, 28 sp) - der ganze WatchHomeScreen braucht Dutzende
 * Dienste. Das Bild liegt danach unter build/sport-unterseite.png.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SportUnterseiteTest {

    @Test
    fun die_leiste_hat_fuenf_reiter_ohne_sport() {
        assertEquals(
            listOf(WatchHomeNavTab.Gesundheit, WatchHomeNavTab.WatchFaces, WatchHomeNavTab.Watches, WatchHomeNavTab.Notifications, WatchHomeNavTab.Settings),
            WatchHomeNavTab.navBarEntries(indexEnabled = false),
        )
        // Mit Index sechs - auch dann ohne Sport.
        assertTrue(WatchHomeNavTab.navBarEntries(indexEnabled = true).none { it.route == PebbleNavBarRoutes.TrainingRoute })
        assertTrue(WatchHomeNavTab.entries.none { it.route == PebbleNavBarRoutes.TrainingRoute })
    }

    /** Ein Weg, der nur mitschreibt, wohin er fuehrt. */
    private class Mitschrift : NavBarNav {
        val ziele = mutableListOf<Any>()
        override fun navigateTo(route: CoreRoute) {
            ziele += route
        }
        override fun navigateTo(route: NavBarRoute) {
            ziele += route
        }
        override fun goBack() {}
    }

    @Test
    fun health_fuehrt_zu_sport_training_und_wasser() {
        val m = Mitschrift()
        val w = gesundheitswege(m)
        w.sport()
        w.training("abc")
        w.wasser()
        assertEquals(
            listOf<Any>(PebbleNavBarRoutes.TrainingRoute, PebbleNavBarRoutes.TrainingDetailRoute("abc"), PebbleNavBarRoutes.TrendRoute("ernaehrung")),
            m.ziele,
        )
    }

    // --- Der echte Stapel und das Bild ---

    private lateinit var akte: FakeAkte

    @Before
    fun auf() {
        val context = RuntimeEnvironment.getApplication()
        akte = FakeAkte(context.packageName)
        akte.erteilt = alleRechteJederAkte()
        Akte.ersatz = akte
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        // Sport fragt nur nach Gravels Einheiten-Schalter; ohne Einstellung metrisch.
        // Compose vergleicht den Dienst als Schluessel (remember): equals und hashCode wie ein gewoehnliches Objekt.
        lateinit var lib: LibPebble
        lib = Proxy.newProxyInstance(LibPebble::class.java.classLoader, arrayOf(LibPebble::class.java)) { _, methode, argumente ->
            when (methode.name) {
                "getHealthSettings" -> emptyFlow<Any>()
                "equals" -> argumente?.firstOrNull() === lib
                "hashCode" -> System.identityHashCode(lib)
                "toString" -> "LibPebble im Test"
                else -> throw UnsupportedOperationException(methode.name)
            }
        } as LibPebble
        startKoin { modules(module { single { lib } }) }
    }

    @After
    fun ab() {
        Akte.ersatz = null
        stopKoin()
    }

    /** Zwei Trainings dieser Woche in der Akte - die Sportseite soll etwas zeigen. */
    private fun trainings() {
        val zone = ZoneId.systemDefault()
        val heute = LocalDate.now(zone)
        fun sitzung(tag: LocalDate, h: Int, minuten: Long, art: Int, titel: String): ExerciseSessionRecord {
            val beginn = tag.atTime(h, 0).atZone(zone).toInstant()
            return ExerciseSessionRecord(
                startTime = beginn, startZoneOffset = null, endTime = beginn.plusSeconds(minuten * 60), endZoneOffset = null,
                metadata = Kieselsport.vonDerUhr("probe-${beginn.epochSecond}"), exerciseType = art, title = titel,
            )
        }
        val paket = RuntimeEnvironment.getApplication().packageName
        akte.legeAb(sitzung(heute.minusDays(1), 17, 62, ExerciseSessionRecord.EXERCISE_TYPE_BIKING, "Ride"), paket)
        akte.legeAb(sitzung(heute, 10, 50, ExerciseSessionRecord.EXERCISE_TYPE_WALKING, "Walk"), paket)
    }

    private fun texte(n: SemanticsNode): List<String> =
        n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + n.children.flatMap { texte(it) }

    private fun beschreibungen(n: SemanticsNode): List<String> =
        n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() + n.children.flatMap { beschreibungen(it) }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    @Config(sdk = [35], qualifiers = "w412dp-h915dp-xxhdpi")
    fun sport_liegt_auf_dem_stapel_von_health_und_zurueck_fuehrt_nach_health() {
        trainings()
        val aktivitaet = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        lateinit var nav: NavHostController
        aktivitaet.setContent {
            MaterialTheme(colorScheme = greyScheme) {
                nav = rememberNavController()
                val eintrag by nav.currentBackStackEntryAsState()
                var titel by remember { mutableStateOf("") }
                var aktionen by remember { mutableStateOf<@Composable RowScope.() -> Unit>({}) }
                val params = remember {
                    TopBarParams({}, { aktionen = it }, { titel = it }, emptyFlow(), {}, emptyFlow())
                }
                // Wie WatchHomeScreen: der Pfeil, sobald unter der Seite eine andere liegt.
                val zurueck = eintrag != null && nav.previousBackStackEntry != null
                Scaffold(
                    topBar = {
                        TopAppBar(
                            navigationIcon = {
                                if (zurueck) IconButton(onClick = { nav.popBackStack() }) {
                                    Icon(Icons.AutoMirrored.Default.ArrowBack, contentDescription = stringResource(Res.string.back))
                                }
                            },
                            title = { Text(titel, color = MaterialTheme.colorScheme.primary, fontSize = 28.sp, maxLines = 1) },
                            actions = { aktionen() },
                        )
                    },
                ) { innen ->
                    NavHost(nav, startDestination = PebbleNavBarRoutes.GesundheitRoute, modifier = Modifier.padding(innen)) {
                        composable<PebbleNavBarRoutes.GesundheitRoute> {
                            // Health selbst braucht die Datenbank der Uhr - hier steht nur sein Titel.
                            titel = "Health"
                            Text("Health")
                        }
                        composable<PebbleNavBarRoutes.TrainingRoute> { TrainingScreen(params, NoOpNavBarNav) }
                    }
                }
            }
        }
        shadowOf(Looper.getMainLooper()).idle()
        // Der Weg aus Health - derselbe Plan wie im Bildschirm, ueber den Controller wie in WatchHomeScreen.
        val wege = gesundheitswege(object : NavBarNav {
            override fun navigateTo(route: CoreRoute) = throw AssertionError("Sport gehoert in die Leiste, nicht nach aussen")
            override fun navigateTo(route: NavBarRoute) = nav.navigate(route)
            override fun goBack() {}
        })
        wege.sport()
        val wurzel = (aktivitaet.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ViewGroup).getChildAt(0) as ViewRootForTest
        // Die Sportseite laedt die Akte im Hintergrund: warten, bis die Woche dasteht.
        // Die Uhr laeuft mit (idleFor), sonst bliebe der Uebergang zwischen beiden Seiten stehen.
        for (versuch in 1..100) {
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100))
            if (texte(wurzel.semanticsOwner.unmergedRootSemanticsNode).any { it.startsWith("THIS WEEK") }) break
            Thread.sleep(50)
        }
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))

        val ansicht = aktivitaet.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        val bild = Bitmap.createBitmap(ansicht.width, ansicht.height, Bitmap.Config.ARGB_8888)
        ansicht.draw(Canvas(bild))
        File("build/sport-unterseite.png").outputStream().use { bild.compress(Bitmap.CompressFormat.PNG, 100, it) }

        assertTrue(nav.currentDestination!!.hasRoute<PebbleNavBarRoutes.TrainingRoute>(), "${nav.currentDestination}")
        assertTrue(nav.previousBackStackEntry!!.destination.hasRoute<PebbleNavBarRoutes.GesundheitRoute>(), "Health liegt nicht darunter")
        val baum = wurzel.semanticsOwner.unmergedRootSemanticsNode
        assertTrue("Sport" in texte(baum), "${texte(baum)}")
        assertTrue(texte(baum).any { it.startsWith("THIS WEEK") }, "Sportseite nicht geladen: ${texte(baum)}")
        // Health ist nicht mehr zu sehen - der Uebergang ist zu Ende.
        assertTrue("Health" !in texte(baum), "${texte(baum)}")
        assertTrue("Back" in beschreibungen(baum), "kein Pfeil: ${beschreibungen(baum)}")

        // Die Zurueck-Taste des Telefons fuehrt nach Health, nicht aus der App.
        aktivitaet.onBackPressedDispatcher.onBackPressed()
        // Einige Bilder lang: der Stapel aendert sich sofort, die Seiten erst mit den naechsten Frames.
        for (versuch in 1..100) {
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100))
            if ("Health" in texte(wurzel.semanticsOwner.unmergedRootSemanticsNode)) break
            Thread.sleep(20)
        }
        assertTrue(nav.currentDestination!!.hasRoute<PebbleNavBarRoutes.GesundheitRoute>(), "${nav.currentDestination}")
        assertFalse(aktivitaet.isFinishing)
        val danach = wurzel.semanticsOwner.unmergedRootSemanticsNode
        assertTrue("Back" !in beschreibungen(danach), "Pfeil auf Health: ${beschreibungen(danach)}")
        assertEquals("Health", texte(danach).firstOrNull(), "${texte(danach)}")
    }
}
