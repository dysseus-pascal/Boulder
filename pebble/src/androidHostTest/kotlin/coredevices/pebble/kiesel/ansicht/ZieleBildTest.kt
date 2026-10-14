package coredevices.pebble.kiesel.ansicht

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import coredevices.pebble.kiesel.Gesundheitsziele
import io.rebble.libpebblecommon.connection.FakeLibPebble
import io.rebble.libpebblecommon.connection.HealthDataApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import theme.greyScheme
import java.io.File
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * DIE SEITE GOALS, GEZEICHNET (Boulder 0.53): beide Ziele mit der Vorgabe,
 * ein Tipp auf Plus aendert das Schrittziel um 500 - auf der Seite, im
 * Gemerkten und auf dem Weg zur Uhr. Das Bild liegt danach unter build/ziele.png.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ZieleBildTest {
    private val zurUhr: MutableList<Pair<Int, Int>> = Collections.synchronizedList(mutableListOf())
    private val api = object : HealthDataApi by FakeLibPebble() {
        override suspend fun setzeZiele(schritte: Int, schlafMin: Int) {
            zurUhr += schritte to schlafMin
        }
    }

    private fun alle(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap { alle(it) }
    private fun texte(n: SemanticsNode) = alle(n).flatMap { k -> k.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }

    @Test
    @Config(sdk = [35], qualifiers = "w412dp-h900dp-xxhdpi")
    fun vorgabe_und_ein_tipp_auf_plus() = runBlocking {
        val aktivitaet = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        aktivitaet.setContent {
            MaterialTheme(colorScheme = greyScheme) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { ZieleAnsicht(api) }
            }
        }
        shadowOf(Looper.getMainLooper()).idle()
        val ansicht = aktivitaet.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        val bild = Bitmap.createBitmap(ansicht.width, ansicht.height, Bitmap.Config.ARGB_8888)
        ansicht.draw(Canvas(bild))
        File("build/ziele.png").outputStream().use { bild.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val wurzel = ((ansicht as ViewGroup).getChildAt(0) as ViewRootForTest).semanticsOwner.unmergedRootSemanticsNode

        val vorher = texte(wurzel)
        for (t in listOf("Steps per day", "Sleep per night")) assertTrue(t in vorher, "\"$t\" fehlt in $vorher")
        assertTrue(vorher.any { it.startsWith("10,000") }, "$vorher")
        assertTrue(vorher.any { it.startsWith("8:00") }, "$vorher")
        assertTrue(vorher.any { it.startsWith("Health, the trends and the widget") }, "$vorher")

        // Das erste Plus gehoert zu den Schritten; klickbar ist der Knopf um das Zeichen.
        fun beschrieben(n: SemanticsNode) = alle(n).any { k -> k.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains("Increase") }
        val plus = alle(wurzel).filter { n -> n.config.getOrNull(SemanticsActions.OnClick) != null && beschrieben(n) }
        assertEquals(2, plus.size)
        plus.first().config[SemanticsActions.OnClick].action!!.invoke()
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(texte(wurzel).any { it.startsWith("10,500") }, "${texte(wurzel)}")
        assertEquals(Gesundheitsziele.Ziele(10_500, 480), Gesundheitsziele.ziele(aktivitaet))
        withTimeout(10_000) { while (zurUhr.isEmpty()) delay(20) }
        assertEquals(10_500 to 480, zurUhr.last())
    }
}
