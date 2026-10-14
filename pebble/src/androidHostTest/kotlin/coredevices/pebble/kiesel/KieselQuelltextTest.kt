package coredevices.pebble.kiesel

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Waechter ueber den Quelltext von kiesel/: KEIN FEHLER VERSCHWINDET STILL.
 *
 * Notiz 0.45 versprach "Fehler landen im Log", und doch schluckten ein
 * Dutzend Stellen sie ohne Zeile - die Blutdruckkarte verschwand spurlos,
 * eine kaputte Antwort hiess einfach null. Und was geloggt wurde, ging ueber
 * android.util.Log nur nach logcat, nicht ins exportierbare App-Log (Kermit,
 * FileLogWriter): ohne Kabel so gut wie verschluckt.
 *
 * Darum hier, ohne Ausnahme: jeder catch-Block loggt (Kermit), wirft weiter,
 * geht ueber [versuche] oder schreibt ins Protokoll (KieselVerlauf, das selbst
 * loggt); kein runCatching (es faengt auch den Abbruch und loggt nie); und
 * android.util.Log gibt es hier nicht.
 *
 * GELOGGT HEISST AB INFO: im Release schreibt das App-Log erst ab Info
 * (logging.kt, minSeverity) - eine Zeile auf Debug oder Verbose kaeme dort
 * nie an und waere fuer die Fehlersuche so still wie keine.
 *
 * AUSSER kiesel/ DIE BOULDER-DATEIEN DANEBEN ([ausserdem]): Firmware-Signatur,
 * Naechte, Rechte, Einstellungen, Trainings- und Gesundheitsseiten. Nicht
 * dabei: TimeStyle (eigener Bereich),
 * Gravels Dateien, und Einzellauf - dessen catch reicht den Fehler an das
 * beiFehler des Aufrufers, und beide Aufrufer loggen (GesundheitWidget,
 * PlatformHealthSync).
 */
class KieselQuelltextTest {
    private val wurzel = File("src/androidMain/kotlin/coredevices/pebble/kiesel")
    private val ausserdem = listOf(
        "androidMain/kotlin/coredevices/pebble/firmware/Ed25519.android.kt",
        "commonMain/kotlin/coredevices/pebble/firmware/FirmwareSignatur.kt",
        "androidMain/kotlin/coredevices/pebble/health/HealthConnectPermissions.android.kt",
        "androidMain/kotlin/coredevices/pebble/health/NachtPlatform.android.kt",
        "commonMain/kotlin/coredevices/pebble/health/NachtPlatform.kt",
        "commonMain/kotlin/coredevices/pebble/health/Rechteanlass.kt",
        "commonMain/kotlin/coredevices/pebble/health/Tagesbloecke.kt",
        "commonMain/kotlin/coredevices/pebble/health/UhrNachfrage.kt",
        "androidMain/kotlin/coredevices/pebble/ui/BoulderSettings.android.kt",
        "commonMain/kotlin/coredevices/pebble/ui/BoulderSettings.kt",
        "androidMain/kotlin/coredevices/pebble/ui/GesundheitScreens.android.kt",
        "commonMain/kotlin/coredevices/pebble/ui/GesundheitScreens.kt",
        "androidMain/kotlin/coredevices/pebble/ui/TrainingScreens.android.kt",
        "commonMain/kotlin/coredevices/pebble/ui/TrainingScreens.kt",
    ).map { File("src/$it") }
    private val dateien by lazy { wurzel.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.toList() + ausserdem }

    /** Der Text ohne Kommentare - ein Wort im Kommentar ist kein Aufruf. */
    private fun code(text: String): String =
        text.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "").lines().joinToString("\n") { zeile ->
            // Zeilenkommentar weg, aber nicht "//" in einem String (URLs).
            val i = Regex("""(?<!:)//""").find(zeile)?.range?.first
            if (i == null) zeile else zeile.substring(0, i)
        }

    /** Kermit ab Info (i, w, e, a) - direkt oder ueber withTag -, versuche, weiterwerfen oder das Protokoll. */
    private val geloggt = Regex("""\b[lL]ogger(\.withTag\([^)]*\))?\.[iwea]\s*[({]|\bversuche\(|\bthrow\b|KieselVerlauf\.merke""")

    /** Jeder catch-Block in [text], dessen Rumpf nicht loggt: Zeile und Kopf. */
    fun stilleCatches(text: String): List<String> = buildList {
        val c = code(text)
        Regex("""catch \(([^)]*)\)\s*\{""").findAll(c).forEach { m ->
            var i = m.range.last + 1
            var tiefe = 1
            while (tiefe > 0 && i < c.length) {
                if (c[i] == '{') tiefe++ else if (c[i] == '}') tiefe--
                i++
            }
            if (!geloggt.containsMatchIn(c.substring(m.range.last + 1, i - 1))) add("Zeile ${c.substring(0, m.range.first).count { it == '\n' } + 1}: ${m.value}")
        }
        if (Regex("""\brunCatching\b""").containsMatchIn(c)) add("runCatching")
        if (Regex("""\bandroid\.util\.Log\b""").containsMatchIn(c)) add("android.util.Log")
    }

    @Test
    fun keinFehlerVerschwindetStill() {
        assertTrue(dateien.size > 50, "kiesel/ nicht gefunden: ${wurzel.absolutePath}")
        assertTrue(ausserdem.all { it.isFile }, "nicht gefunden: $ausserdem")
        val funde = dateien.associate { it.path to stilleCatches(it.readText()) }.filterValues { it.isNotEmpty() }
        assertEquals(emptyMap(), funde)
    }

    @Test
    fun derWaechterErkenntDieBekanntenMuster() {
        // Stille Muster - jedes muss auffallen.
        val still = """
            fun a() = try { lies() } catch (e: Exception) { null }
            fun b() { try { x() } catch (e: IllegalArgumentException) {
                // War schon geloest.
            } }
            fun c() = runCatching { LocalDate.parse(k) }.getOrNull()
            fun d() { android.util.Log.w("T", "x") }
            fun e() { try { x() } catch (e: Exception) { logger.d { "schon abgemeldet" } } }
            fun f() = try { lies() } catch (e: Exception) { Logger.withTag("T").v { "y" }; null }
        """.trimIndent()
        assertEquals(6, stilleCatches(still).size, stilleCatches(still).toString())
        // Und was erlaubt ist, faellt nicht auf: loggen, weiterwerfen, versuche, Protokoll.
        val gut = """
            fun a() = try { lies() } catch (e: Exception) { logger.w { "x" }; null }
            fun b() = try { lies() } catch (e: CancellationException) { throw e } catch (e: Exception) { Logger.withTag("T").i { "y" }; null }
            fun c() = versuche(TAG, "z") { lies() }
            fun d() = try { lies() } catch (e: Exception) { KieselVerlauf.merke(context, "z") }
            fun e() = try { lies() } catch (e: Exception) { logger.e(e) { "z" }; null }
            fun f() = try { lies() } catch (e: Exception) { Logger.withTag("T").a { "z" }; null }
            // runCatching im Kommentar zaehlt nicht, ebenso wenig android.util.Log hier.
            val u = "https://example.org/a//b"
        """.trimIndent()
        assertEquals(emptyList(), stilleCatches(gut))
    }
}
