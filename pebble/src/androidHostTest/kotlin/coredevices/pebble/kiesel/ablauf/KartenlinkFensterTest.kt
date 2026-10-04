package coredevices.pebble.kiesel.ablauf

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.net.Uri
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import coredevices.pebble.R
import coredevices.pebble.kiesel.KartenlinkActivity
import coredevices.pebble.kiesel.KartenlinkModell
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Das Kartenlink-Fenster: ein zweiter Link, waehrend es offen ist, kommt an
 * (singleTask liefert ihn ueber onNewIntent), und Drehen loest den Link nicht
 * noch einmal auf - es startete sonst OsmAnd ein zweites Mal.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class KartenlinkFensterTest {
    private lateinit var context: Context

    @Before
    fun auf() {
        context = RuntimeEnvironment.getApplication()
    }

    /** OsmAnd installieren, so dass Kieselstrasse.osmandPaket es findet. */
    private fun osmandDa() {
        val pm = shadowOf(context.packageManager)
        pm.installPackage(PackageInfo().apply {
            packageName = OSMAND
            applicationInfo = ApplicationInfo().apply { packageName = OSMAND }
        })
        val karte = ComponentName(OSMAND, "$OSMAND.activities.MapActivity")
        pm.addActivityIfNotPresent(karte)
        pm.addIntentFilterForActivity(karte, IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) })
    }

    private fun link(roh: String) = Intent(context, KartenlinkActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.parse(roh))

    private fun modell(a: KartenlinkActivity) = ViewModelProvider(a)[KartenlinkModell::class.java]

    /** Den Hauptfaden laufen lassen, bis [bedingung] gilt - die Arbeit laeuft auf Dispatchers.IO. */
    private fun warte(was: String, bedingung: () -> Boolean) {
        val ende = System.currentTimeMillis() + 10_000
        while (!bedingung() && System.currentTimeMillis() < ende) {
            // Mit Zeit: Compose legt Bildtakte in die Schlange, die erst nach 16 ms dran sind.
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
            Thread.sleep(10)
        }
        assertTrue(bedingung(), was)
    }

    private fun gestartet(): Intent? = shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity

    @Test
    fun ein_zweiter_link_im_offenen_fenster_kommt_an() {
        osmandDa()
        val fenster = Robolectric.buildActivity(KartenlinkActivity::class.java, link("geo:0,0?q=")).setup()
        warte("Meldung zum ersten Link") { modell(fenster.get()).meldung.value != null }
        assertEquals(context.getString(R.string.ks_k_kein_ziel), modell(fenster.get()).meldung.value)

        fenster.newIntent(link("geo:47.1,8.2"))
        var ziel: Intent? = null
        warte("OsmAnd mit dem zweiten Ziel") { ziel = ziel ?: gestartet(); ziel != null }
        assertEquals(OSMAND, ziel!!.`package`)
        assertTrue(ziel!!.dataString!!.startsWith("geo:47.1"), ziel!!.dataString)
    }

    @Test
    fun drehen_loest_den_link_nicht_noch_einmal_auf() {
        // Ohne OsmAnd: die Meldung "abgelehnt" steht offen.
        val fenster = Robolectric.buildActivity(KartenlinkActivity::class.java, link("geo:47.1,8.2")).setup()
        val abgelehnt = context.getString(R.string.ks_k_abgelehnt)
        warte("Meldung") { modell(fenster.get()).meldung.value == abgelehnt }

        // Jetzt waere OsmAnd da: ein zweites Aufloesen beim Drehen startete es.
        osmandDa()
        fenster.recreate()
        repeat(30) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
            Thread.sleep(10)
        }
        assertNull(gestartet(), "Nach dem Drehen erneut an OsmAnd gegeben")
        assertEquals(abgelehnt, assertNotNull(modell(fenster.get()).meldung.value))
    }

    private companion object {
        const val OSMAND = "net.osmand.plus"
    }
}
