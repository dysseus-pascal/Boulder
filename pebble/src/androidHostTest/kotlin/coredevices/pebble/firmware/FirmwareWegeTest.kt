package coredevices.pebble.firmware

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Boulder: Firmware aus dem Netz kommt nur ueber VerifiedFirmwareInstaller auf die Uhr (sha256,
 * Ed25519, Manifest). Cores updateFirmware laedt selbst und prueft keine Signatur; ruft es eine
 * Oberflaeche wieder direkt auf - etwa nach einem Core-Merge -, liefe Firmware ungeprueft auf die Uhr.
 */
class FirmwareWegeTest {
    private val wurzel = File("..").canonicalFile
    private val aufruf = Regex("""(?<!fun )\bupdateFirmware\(""")

    @Test
    fun keineOberflaecheInstalliertAmGeprueftenInstallerVorbei() {
        val module = listOf("pebble", "composeApp", "androidApp", "experimental", "util")
        val funde = module.flatMap { modul ->
            File(wurzel, "$modul/src").walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                // Nur Produktivcode: Testquellen duerfen Cores Weg nachstellen.
                .filter { datei -> datei.path.split(File.separator).none { it.endsWith("Test") } }
                .flatMap { datei ->
                    datei.readLines().mapIndexedNotNull { i, zeile ->
                        if (aufruf.containsMatchIn(zeile)) "${datei.relativeTo(wurzel)}:${i + 1}: ${zeile.trim()}" else null
                    }
                }
                .toList()
        }
        assertEquals(emptyList(), funde)
    }
}
