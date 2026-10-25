package coredevices.pebble.ui

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
import androidx.compose.ui.state.ToggleableState
import io.rebble.libpebblecommon.connection.FakeLibPebble
import io.rebble.libpebblecommon.database.entity.BoolWatchPref
import io.rebble.libpebblecommon.database.dao.WatchPreference
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Boulder: "SpO2 During Sleep" steht bei Pebbles SpO2-Schaltern auf der Seite des Telefons
 * und schreibt die Uhr-Einstellung boulderSpo2Schlaf, die die Boulder-Firmware ab b37 liest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Spo2SchlafTest {

    @Test
    fun steht_bei_pebbles_spo2_schaltern_und_nicht_doppelt() {
        assertEquals(Section.Health, BoolWatchPref.BoulderSpo2Schlaf.section())
        assertEquals(TopLevelType.Phone, BoolWatchPref.BoulderSpo2Schlaf.topLevelType())
        val alle = listOf(WatchPreference(BoolWatchPref.BoulderSpo2Schlaf, null), WatchPreference(BoolWatchPref.Clock24h, null))
        assertEquals(listOf(BoolWatchPref.Clock24h), alle.ohneEigenenPlatz().map { it.pref })
    }

    @Test
    fun ein_tipp_schaltet_die_uhr_einstellung_ein() {
        val libPebble = FakeLibPebble()
        val eintrag = spo2SchlafPref(WatchPreference(BoolWatchPref.BoulderSpo2Schlaf, null), libPebble) { true }
        assertEquals("SpO2 During Sleep", eintrag.title)
        val aktivitaet = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        aktivitaet.setContent { MaterialTheme { eintrag.Item() } }
        shadowOf(Looper.getMainLooper()).idle()
        val ansicht = aktivitaet.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ViewGroup
        val wurzel = (ansicht.getChildAt(0) as ViewRootForTest).semanticsOwner.unmergedRootSemanticsNode
        fun alle(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap { alle(it) }
        val kasten = alle(wurzel).single { it.config.getOrNull(SemanticsProperties.ToggleableState) != null }
        // Aus, solange die Uhr nichts gemeldet hat: wie die Firmware ohne den Schluessel.
        assertEquals(ToggleableState.Off, kasten.config[SemanticsProperties.ToggleableState])
        kasten.config[SemanticsActions.OnClick].action!!.invoke()
        shadowOf(Looper.getMainLooper()).idle()
        val gesetzt = runBlocking { libPebble.watchPrefs.first() }.single { it.pref == BoolWatchPref.BoulderSpo2Schlaf }
        assertEquals(true, gesetzt.value)
    }
}
