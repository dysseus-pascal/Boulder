package coredevices.coreapp

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Boulder: vier Compose-Ressourcen bleiben im Baum, aber ignoreAssetsPattern
 * haelt sie aus dem APK (0,92 MB). Ihre Res-Zugriffe kompilieren weiter - ein
 * Aufruf baute, bestand jeden Test und warf erst beim Zeichnen
 * MissingResourceException. Wie ExcludedAssetSentinelTest fuer die Wispr-Logos.
 */
class BoulderAssetausschlussTest {

    private val dateien = listOf("Inter-Italic.ttf", "index01_mic.png", "ring_wireframe.png", "core.xml")

    // Was Kotlin-Code schreiben muesste, um eine der Dateien zu laden. CoreIcons.kt
    // (Upstream) definiert den Zugriff auf core.xml und darf ihn behalten, solange ihn
    // niemand aufruft; Inter-Italic heisst im generierten Code Inter_Italic.
    private val zugriffe = listOf(
        Regex("""\bInter_Italic\b"""),
        Regex("""\bindex01_mic\b"""),
        Regex("""\bring_wireframe\b"""),
        Regex("""\bCoreIcons\.Core\b"""),
        Regex("""\bdrawable\.core\b"""),
    )

    @Test
    fun kein_quelltext_laedt_eine_ausgeschlossene_ressource() {
        val taeter = TrackedTree.files
            .filter { it.endsWith(".kt") && !it.endsWith("/BoulderAssetausschlussTest.kt") && !it.endsWith("util/src/commonMain/kotlin/CoreIcons.kt") }
            .filter { pfad -> TrackedTree.file(pfad).readText().let { text -> zugriffe.any { it.containsMatchIn(text) } } }
        assertTrue(taeter.isEmpty(), "Diese Quellen laden eine Ressource, die das APK nicht mehr enthaelt:\n" + taeter.joinToString("\n"))
    }

    @Test
    fun der_ausschluss_steht_noch_im_build() {
        val build = TrackedTree.file("androidApp/build.gradle.kts").readText()
        dateien.forEach { assertTrue(build.contains("!$it"), "androidApp/build.gradle.kts schliesst $it nicht mehr aus") }
    }

    @Test
    fun die_dateien_liegen_noch_im_baum() {
        dateien.forEach { name ->
            assertTrue(
                TrackedTree.files.count { it.contains("/composeResources/") && it.endsWith("/$name") } == 1,
                "$name liegt nicht mehr genau einmal unter composeResources - Muster und diesen Test anpassen",
            )
        }
    }
}
