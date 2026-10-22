package coredevices.pebble.kiesel.ansicht

import android.os.Looper
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import coredevices.pebble.kiesel.Spiegel
import io.rebble.libpebblecommon.connection.FakeLibPebble
import io.rebble.libpebblecommon.connection.HealthDataApi
import io.rebble.libpebblecommon.connection.LibPebble
import org.junit.After
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import theme.greyScheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Boulder: die Seite "Boulder server" ([SpiegelAnsicht]) - was man in Adresse und Token tippt,
 * bleibt stehen, bis es gespeichert ist; erst dann gilt die gespeicherte Adresse und das Token
 * verschwindet.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xxhdpi")
class SpiegelAnsichtTest {
    @After
    fun ab() {
        stopKoin()
    }

    private lateinit var aktivitaet: ComponentActivity

    private fun alle(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap { alle(it) }

    private fun knoten(): List<SemanticsNode> {
        shadowOf(Looper.getMainLooper()).idle()
        val ansicht = aktivitaet.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ViewGroup
        return alle((ansicht.getChildAt(0) as ViewRootForTest).semanticsOwner.unmergedRootSemanticsNode)
    }

    /** Adresse und Token, in dieser Reihenfolge. */
    private fun felder() = knoten().filter { it.config.getOrNull(SemanticsActions.SetText) != null }

    private fun tippe(feld: Int, text: String) {
        felder()[feld].config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(text))
    }

    private fun inhalt(feld: Int): String = felder()[feld].config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()

    private fun texte(): List<String> = knoten().flatMap { k -> k.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }

    private fun speichere() {
        val knopf = knoten().first { k ->
            k.config.getOrNull(SemanticsActions.OnClick) != null && alle(k).any { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == "Save" } }
        }
        knopf.config[SemanticsActions.OnClick].action!!.invoke()
    }

    @Test
    fun getipptesBleibtBisEsGespeichertIst() {
        val lib = FakeLibPebble()
        startKoin { modules(module { single<HealthDataApi> { lib }; single<LibPebble> { lib } }) }
        aktivitaet = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        Spiegel.entferne(aktivitaet)
        aktivitaet.setContent { MaterialTheme(colorScheme = greyScheme) { SpiegelAnsicht() } }

        // Ohne https: abgelehnt - beide Eingaben bleiben, das lange Token muss niemand neu einfuegen.
        tippe(0, "boulder.example.ch")
        tippe(1, "x".repeat(64))
        speichere()
        assertTrue(texte().any { it.startsWith("The address must start with https://") }, texte().toString())
        assertEquals("boulder.example.ch", inhalt(0))
        assertEquals(64, inhalt(1).length)

        // Berichtigt und gespeichert: die Adresse, wie sie gilt, und das Token nie wieder sichtbar.
        tippe(0, "https://boulder.example.ch/")
        speichere()
        assertEquals("https://boulder.example.ch", inhalt(0))
        assertEquals("", inhalt(1))
        assertEquals("https://boulder.example.ch", Spiegel.ziel(aktivitaet)?.adresse)
    }
}
