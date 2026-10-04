package coredevices.pebble.timestyle

import coredevices.pebble.timestyle.bild.TsRessourcen
import coredevices.pebble.timestyle.bild.TsWetter
import coredevices.pebble.timestyle.bild.TsWetterSymbol
import coredevices.pebble.timestyle.bild.TsZustand
import coredevices.pebble.timestyle.bild.tsArgb
import coredevices.pebble.timestyle.bild.tsZifferblatt
import io.rebble.libpebblecommon.database.entity.hexAlsBytes
import java.awt.image.BufferedImage
import java.io.File
import java.security.MessageDigest
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * DIE VORSCHAU IST DAS BILD DER UHR: der Nachbau (timestyle/bild) zeichnet jede
 * Einstellung aus resources/timestyle/referenzen/ und muss mit dem Bild aus
 * dem Emulator (QEMU, emery, Firmware-Pruefbau TS_DEMO) in JEDEM Pixel
 * uebereinstimmen. Die Bilder macht erzeugen.sh im selben Ordner.
 */
class TsZifferblattReferenzTest {
    private val ordner = File("src/androidHostTest/resources/timestyle/referenzen")
    private val dateien = File("src/commonMain/composeResources/files/timestyle")
    private val ressourcen by lazy { TsRessourcen.aus { File(dateien, it).readBytes() } }

    /** Ein Referenzbild als GColor8 (je Kanal nur 0, 85, 170, 255 - sonst war das Licht gedimmt). */
    private fun lies(datei: File): IntArray {
        val b = ImageIO.read(datei)
        assertEquals(200 to 228, b.width to b.height, datei.name)
        return IntArray(200 * 228) { i ->
            val rgb = b.getRGB(i % 200, i / 200)
            val r = (rgb shr 16) and 0xff
            val g = (rgb shr 8) and 0xff
            val bl = rgb and 0xff
            assertTrue(r % 85 == 0 && g % 85 == 0 && bl % 85 == 0, "${datei.name}: keine der 64 Farben bei Pixel $i")
            0xc0 or ((r / 85) shl 4) or ((g / 85) shl 2) or (bl / 85)
        }
    }

    /**
     * Der Zustand des Pruefbaus TS_DEMO, WOERTLICH aus der Firmware: main.c
     * (Samstag 3.10.2026 15:45:00, tm_gmtoff 0), weather.c (22 Grad, 24/12,
     * UV 3, bewoelkt), sidebar_widgets.c (5523 Schritte, 4230 m, Puls 62,
     * Schlaf 26580 s bzw. ruhig 7500 s, Akku 80 % ohne Laden), sidebar.c
     * (verbunden). Beats 698 = 15:45 UTC.
     */
    private fun tsDemo(vierundzwanzig: Boolean, metrisch: Boolean) = TsZustand(
        stunde = 15, minute = 45, sekunde = 0, tag = 3, monat = 9, jahr = 2026, wochentag = 6, jahresTag = 275,
        vierundzwanzig = vierundzwanzig, utcVersatzSekunden = 0, beats = 698,
        wetter = TsWetter(22, 24, 12, 3, TsWetterSymbol.BEWOELKT, TsWetterSymbol.BEWOELKT),
        schritte = 5523, distanzMeter = 4230, metrisch = metrisch, puls = 62,
        schlafSekunden = 26580, ruhigerSchlafSekunden = 7500, akkuProzent = 80, laedt = false, verbunden = true,
    )

    private data class Referenz(val name: String, val h24: Boolean, val metrisch: Boolean, val einstellungen: TsBedrockEinstellungen)

    private val referenzen: List<Referenz> by lazy {
        File(ordner, "referenzen.txt").readLines().filter { it.isNotBlank() }.map { zeile ->
            val (name, h, e, hex) = zeile.split("|")
            Referenz(name, h == "24", e == "km", dekodieren(hex.hexAlsBytes()!!))
        }
    }

    /** Bei einer Abweichung: Soll | Ist | Unterschied (magenta) unter build/, zum Ansehen. */
    private fun vergleichsbild(name: String, soll: IntArray, ist: IntArray) {
        val b = BufferedImage(600, 228, BufferedImage.TYPE_INT_RGB)
        for (i in soll.indices) {
            val x = i % 200
            val y = i / 200
            b.setRGB(x, y, tsArgb(soll[i]) and 0xffffff)
            b.setRGB(200 + x, y, tsArgb(ist[i]) and 0xffffff)
            b.setRGB(400 + x, y, if (soll[i] == ist[i]) (tsArgb(soll[i]) shr 2) and 0x3f3f3f else 0xff00ff)
        }
        val ziel = File("build/timestyle-abweichungen/$name.png")
        ziel.parentFile.mkdirs()
        ImageIO.write(b, "png", ziel)
    }

    @Test
    fun jedePixelGleichWieImEmulator() {
        val abweichend = referenzen.mapNotNull { r ->
            val soll = lies(File(ordner, "${r.name}.png"))
            val ist = tsZifferblatt(r.einstellungen, tsDemo(r.h24, r.metrisch), ressourcen)
            val n = soll.indices.count { soll[it] != ist[it] }
            if (n > 0) {
                vergleichsbild(r.name, soll, ist)
                "${r.name}: $n Pixel"
            } else {
                null
            }
        }
        assertEquals(emptyList(), abweichend)
    }

    @Test
    fun dieReferenzenDeckenAllesAb() {
        val e = referenzen.map { it.einstellungen }
        assertTrue(referenzen.size >= 12, "mindestens 12 Einstellungen")
        assertTrue(e.any { it.sidebarOnLeft } && e.any { !it.sidebarOnLeft }, "Leiste links und rechts")
        assertTrue(e.any { it.useLargeFonts }, "grosse Schrift")
        // Jede waehlbare Widget-Art mindestens einmal.
        assertEquals(TS_WIDGETS.filter { it != TsWidget.KEINS }.toSet(), e.flatMap { it.widgets }.toSet() - TsWidget.KEINS)
        assertTrue(e.map { it.languageId }.toSet().size >= 3, "mindestens drei Sprachen")
        assertEquals((0..4).toSet(), e.map { it.clockFontId }.toSet(), "jede Schrift")
        assertTrue(e.any { it.sidebarColor != 0xff || it.timeColor != 0xff }, "andere Farben")
        assertTrue(referenzen.any { !it.h24 } && referenzen.any { !it.metrisch }, "12 h und Meilen")
        assertTrue(e.any { it.healthUseDistance } && e.any { it.healthUseRestfulSleep }, "Distanz und ruhiger Schlaf")
    }

    @Test
    fun dieDateienSindDieDerFirmware() {
        // SHA-256 der Dateien der Firmware (pebbleos resources/normal/base/timestyle, pbf):
        // aendert sich dort eine, muss sie hier bewusst nachgezogen werden.
        val soll = mapOf(
            "BATTERY_BG.pdc" to "458d7d438243638aad905632faed2ddd6df4c2fb03c923e8908c8e027873f851",
            "BATTERY_CHARGE.pdc" to "6567caedc1c2da5f259739547dfa681e845b7f40087b7b66875ead536c59a355",
            "CLEAR_DAY.pdc" to "0c8d75c16cc75bd0cc63b4f01447bb5689538bbeb4fe6a9c3c69d544ef19eb9b",
            "CLEAR_NIGHT.pdc" to "acbe18d4a0d79a84ce411c6824df3e1432501a7a2df50ab699f1fc10ef25f207",
            "CLOUDY_DAY.pdc" to "97161ca90b325e23600458fd88c293e7b13c397ddd5de1ed90ceed6e1adeb06c",
            "DATE_BG_LG.pdc" to "ddba05e95c95a82915c2170eccf94e5d909a3ef93a4f7e137bf4919e93cd59af",
            "DISCONNECTED.pdc" to "5db97df2927c9e882b2711d92a3f179563921e3c4d4dee58e2306d4f34054486",
            "GOTHIC_18_BOLD.pbf" to "7fc104d09b88a187f44e7f174135473e9d6273df730d94d9cd2c17641b9d635c",
            "GOTHIC_24_BOLD.pbf" to "b341eea37ae3e586f9382d0a3319de17cc6e436d981ebffe3200c237cf95014c",
            "GOTHIC_28_BOLD.pbf" to "728ea9bef855eaf16f48954d3ed6a287b52116345bc3533f42725013c3bed170",
            "HEALTH_HEART.pdc" to "63fc3dc66a77481c548d2feed67ec52269e0a8684f4d421559d27a35a5ae2344",
            "HEALTH_SLEEP.pdc" to "3e6bb35cf76dd87b0cdb37b62050f1bfcf1af70fa23dfb4f7b9f793e8cb60f9a",
            "HEALTH_STEPS.pdc" to "ae419721f4b5b77e5e1702dc216e6cc3df1653685ffd61d905010ad1259c6ad5",
            "HEAVY_RAIN.pdc" to "c7b96ce43b441630e8752165670a3b2f6f1bae9333f59b58bf51985fcccb8338",
            "HEAVY_SNOW.pdc" to "190dec54baa68e4db64e2c5cb43e4e364811a6ed12a7cef6a29f02b20f73c08b",
            "KumbhSans-Bold.ffont" to "53f58e45665d31ff19577a84efd5a46950ffebd624937b652e9e24b12eb5403a",
            "KumbhSans-Regular.ffont" to "45217a90deed7f544fb235359f8945207e7181e7d660f593d975f771134ab760",
            "LECO1976-Regular.ffont" to "3b2d6c16a63d47d3ad612195a190296208721d29de225af206a97014ae787c7d",
            "LIGHT_RAIN.pdc" to "3821fe3cb82163d0fa39562e303cd8d5e92f37eb35e0e486edc11662f068061c",
            "LIGHT_SNOW.pdc" to "a9550e43218cf7d8b8c974a02f71055149b7c6bf7a244b39698222c79104c3cf",
            "PARTLY_CLOUDY_NIGHT.pdc" to "a7657e20ba519752ffc967dcd4fbc57fcdb6db41b91622f7b70386d6f4d5ab65",
            "PARTLY_CLOUDY.pdc" to "085d56d9dbe2bc1c68c7a7e90bcb4b92f2536be6d0ccbdf7c7abd028cb033930",
            "RAINING_AND_SNOWING.pdc" to "1e139003f4e048972afd5caeb466e8da9fe2524f9c3cd399d26df072423f14e6",
            "THUNDERSTORM.pdc" to "8597421394aaa90f8de0744629585112e3ba0405d55fc864e80462889e6fdb11",
            "WEATHER_GENERIC.pdc" to "5c8dceb328ab6e3ff071c81599d0bd49241b43dd130697bc8327eaa33b6e3d93",
        )
        assertEquals(soll.keys, TsRessourcen.DATEIEN.toSet())
        val ist = soll.keys.associateWith { name ->
            MessageDigest.getInstance("SHA-256").digest(File(dateien, name).readBytes()).joinToString("") { "%02x".format(it) }
        }
        assertEquals(soll, ist)
    }
}
