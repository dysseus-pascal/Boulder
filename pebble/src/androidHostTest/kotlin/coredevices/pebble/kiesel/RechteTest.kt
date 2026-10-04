package coredevices.pebble.kiesel

import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.permission.HealthPermission
import coredevices.pebble.health.Anfragen
import coredevices.pebble.health.syncNachFrage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RechteTest {
    private val hintergrund = HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND
    private val verlauf = HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY
    private val wasser = "android.permission.health.WRITE_HYDRATION"

    @Test
    fun ohne_funktion_kein_recht() {
        val rechte = setOf(wasser, hintergrund, verlauf)
        assertEquals(setOf(wasser), nurVerfuegbare(rechte) { false })
        assertEquals(rechte, nurVerfuegbare(rechte) { true })
        // Nur den Verlauf kennt diese Akte
        assertEquals(
            setOf(wasser, verlauf),
            nurVerfuegbare(rechte) { it == HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_HISTORY },
        )
    }

    @Test
    fun funktionsrechte_stehen_in_den_kiesel_rechten() {
        // Sonst filterte nurVerfuegbare etwas, das nie gefragt wird
        assertTrue(KIESEL_RECHTE.containsAll(FUNKTIONSRECHTE.keys))
    }

    @Test
    fun startdialog_schaltet_nur_ein() {
        assertEquals(true, syncNachFrage(erteilt = true, nurEinschalten = true))
        assertNull(syncNachFrage(erteilt = false, nurEinschalten = true))
        // Der Schalter in den Einstellungen wie bei Gravel
        assertEquals(true, syncNachFrage(erteilt = true, nurEinschalten = false))
        assertEquals(false, syncNachFrage(erteilt = false, nurEinschalten = false))
    }

    @Test
    fun einmal_je_fassung() {
        val fehlend = setOf(wasser)
        assertTrue(Rechtefrage.sollFragen(fehlend, null, "0.41.0"))
        assertTrue(Rechtefrage.sollFragen(fehlend, "0.40.0", "0.41.0"))
        assertFalse(Rechtefrage.sollFragen(fehlend, "0.41.0", "0.41.0"))
        assertFalse(Rechtefrage.sollFragen(emptySet(), null, "0.41.0"))
    }

    /**
     * Die Rechte, die die gebaute App anmeldet: uses-permission aus allen [manifeste]n,
     * ausser denen, die eines mit tools:node="remove" streicht - so fuehrt der Merger zusammen.
     * Gestrichen in einer Variante (release) heisst gestrichen.
     */
    private fun angemeldet(vararg manifeste: File): Set<String> {
        val android = "http://schemas.android.com/apk/res/android"
        val tools = "http://schemas.android.com/tools"
        val gemeldet = mutableSetOf<String>()
        val gestrichen = mutableSetOf<String>()
        for (m in manifeste) {
            val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(m)
            val rechte = doc.getElementsByTagName("uses-permission")
            for (i in 0 until rechte.length) {
                val e = rechte.item(i) as Element
                val name = e.getAttributeNS(android, "name")
                if (e.getAttributeNS(tools, "node") in setOf("remove", "removeAll")) gestrichen += name else gemeldet += name
            }
        }
        return gemeldet - gestrichen
    }

    private val manifesteDerApp = listOf(
        "src/androidMain/AndroidManifest.xml",
        "../androidApp/src/main/AndroidManifest.xml",
        "../androidApp/src/debug/AndroidManifest.xml",
        "../androidApp/src/release/AndroidManifest.xml",
    ).map(::File)

    @Test
    fun ein_gestrichenes_recht_zaehlt_nicht() {
        val ordner = kotlin.io.path.createTempDirectory("manifest").toFile()
        val kopf = """<manifest xmlns:android="http://schemas.android.com/apk/res/android" xmlns:tools="http://schemas.android.com/tools">"""
        val bibliothek = File(ordner, "a.xml").apply { writeText("""$kopf<uses-permission android:name="x.A" /><uses-permission android:name="x.B" /></manifest>""") }
        val app = File(ordner, "b.xml").apply { writeText("""$kopf<uses-permission android:name="x.B" tools:node="remove" /></manifest>""") }
        assertEquals(setOf("x.A"), angemeldet(bibliothek, app))
        assertEquals(setOf("x.A", "x.B"), angemeldet(bibliothek))
        ordner.deleteRecursively()
    }

    @Test
    fun jedes_gefragte_recht_steht_im_manifest() {
        val manifest = angemeldet(*manifesteDerApp.toTypedArray())
        val gefragt = coredevices.pebble.health.alleRechteJederAkte()
        // Was der Dialog fragt, ohne Anmeldung aber nie erteilt wird, stuende fuer immer als fehlend da.
        assertEquals(emptySet(), gefragt - manifest, "Im Dialog, nicht im Manifest")
        // Der Abgleich laeuft wirklich: ueber 30 Rechte, alle der Akte (heute 31).
        assertTrue(gefragt.size > 30, "nur ${gefragt.size} Rechte")
        assertTrue(gefragt.all { it.startsWith("android.permission.health.") })
    }

    @Test
    fun abgeloeste_anfrage_ist_keine_ablehnung() = runTest {
        val anfragen = Anfragen<Set<String>>()
        val (alteNummer, alte) = anfragen.neu()
        val wartet = async { alte.await() }
        val (neueNummer, neue) = anfragen.neu()
        // Die alte endet ohne Antwort, nicht mit "nichts erteilt"
        assertFailsWith<CancellationException> { wartet.await() }
        assertTrue(neue.isActive)
        // Die Antwort des alten Dialogs beantwortet die neue Anfrage nicht
        anfragen.beantworte(alteNummer, emptySet())
        assertTrue(neue.isActive)
        anfragen.beantworte(neueNummer, setOf(wasser))
        assertEquals(setOf(wasser), neue.await())
    }

    @Test
    fun zweite_antwort_faellt_weg() = runTest {
        val anfragen = Anfragen<Set<String>>()
        val (nummer, antwort) = anfragen.neu()
        anfragen.beantworte(nummer, setOf(wasser))
        anfragen.beantworte(nummer, emptySet())
        assertEquals(setOf(wasser), antwort.await())
    }
}
