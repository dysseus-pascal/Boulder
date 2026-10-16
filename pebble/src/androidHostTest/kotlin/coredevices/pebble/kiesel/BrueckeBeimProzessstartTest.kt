package coredevices.pebble.kiesel

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Boulder: die Bruecke von OsmAnd zur Uhr-App Kieselstrasse startet mit dem Prozess. Android
 * startet Boulder auch ohne Oberflaeche neu (PebbleService ist START_STICKY); hinge die Bruecke
 * an MainActivity, oeffnete sich Kieselstrasse dann ohne Hinweise. MainApplication ist Cores
 * Datei - verliert ein Core-Patch den Aufruf, schlaegt dieser Test an.
 */
class BrueckeBeimProzessstartTest {
    private val wurzel = File("..").canonicalFile

    /** Der Rumpf von [funktion] bis zur schliessenden Klammer auf derselben Einrueckung. */
    private fun rumpf(text: String, funktion: String): String {
        val kopf = text.indexOf(funktion)
        assertTrue(kopf >= 0, "$funktion fehlt")
        val einzug = text.substring(text.lastIndexOf('\n', kopf) + 1, kopf).takeWhile { it == ' ' }
        val ende = text.indexOf("\n$einzug}", kopf)
        assertTrue(ende > kopf, "Ende von $funktion nicht gefunden")
        return text.substring(kopf, ende)
    }

    @Test
    fun mainApplicationStartetDieBrueckeNachLibPebble() {
        val app = File(wurzel, "composeApp/src/androidMain/kotlin/coredevices/coreapp/MainApplication.kt").readText()
        val onCreate = rumpf(app, "override fun onCreate()")
        val init = onCreate.indexOf("pebbleAppDelegate.init()")
        val bruecke = onCreate.indexOf("pebbleAndroidDelegate.beimProzessstart()")
        assertTrue(bruecke >= 0, "MainApplication.onCreate ruft beimProzessstart nicht")
        // Vorher gaebe es libPebble.watches noch nicht in ihrem Stand nach init().
        assertTrue(init in 0 until bruecke, "beimProzessstart muss nach pebbleAppDelegate.init() kommen")
    }

    @Test
    fun beimProzessstartBegleitetKieselstrasse() {
        val delegat = File(wurzel, "pebble/src/androidMain/kotlin/coredevices/pebble/PebbleAndroidDelegate.kt").readText()
        assertTrue("Kieselstrasse.begleite(" in rumpf(delegat, "fun beimProzessstart()"))
        assertTrue("Kieselstrasse.begleite(" !in rumpf(delegat, "fun initPostPermissions()"))
    }
}
