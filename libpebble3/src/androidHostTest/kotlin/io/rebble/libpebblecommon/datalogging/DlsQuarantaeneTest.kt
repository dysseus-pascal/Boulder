package io.rebble.libpebblecommon.datalogging

import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Die Quarantaene ist begrenzt: darueber gehen die aeltesten Dateien weg, nie
 * das eben abgelegte Paket. Echte DlsQuarantaene auf einem Temp-Ordner; nur die
 * Uhr ist gestellt.
 */
class DlsQuarantaeneTest {
    private val ordner = Files.createTempDirectory("dls-quarantaene").toFile()

    @AfterTest
    fun aufraeumen() {
        ordner.deleteRecursively()
    }

    private var jetzt = 1_790_500_000_000L
    private val uhr = object : Clock {
        override fun now() = Instant.fromEpochMilliseconds(jetzt)
    }

    private fun quarantaene(grenze: Long, platte: QuarantaenePlatte = EchtePlatte) =
        DlsQuarantaene(uhr, grenze, platte) { Path(ordner.path) }

    /**
     * Der echte Datentraeger, der mitschreibt, was wann geschah - und was in dem
     * Moment im Ordner lag. [kaputt] laesst einen Schritt scheitern wie eine volle Platte.
     */
    private inner class MitschreibendePlatte(private val kaputt: String? = null) : QuarantaenePlatte {
        val ereignisse = mutableListOf<String>()

        override fun schreibe(pfad: Path, daten: ByteArray) {
            if (kaputt == "schreibe") throw java.io.IOException("No space left on device")
            EchtePlatte.schreibe(pfad, daten)
            ereignisse += "schreibe ${pfad.name.substringAfterLast('.')}"
        }

        override fun sichereOrdner(ordner: Path) {
            if (kaputt == "ordner") throw java.io.IOException("fsync not supported")
            EchtePlatte.sichereOrdner(ordner)
            // Der Name, den der Ordner sichern soll, muss schon stehen.
            ereignisse += "ordner " + this@DlsQuarantaeneTest.ordner.listFiles()!!.map { it.name.substringAfterLast('.') }.sorted()
        }
    }

    /** Das erste Byte jeder Datei, die noch liegt - es kennzeichnet das Paket. */
    private fun liegen() = ordner.listFiles()!!.filter { it.name.startsWith("dls-") }.map { it.readBytes()[0].toInt() }.toSet()

    @Test
    fun ueberDerGrenzeGehenDieAeltestenWeg() = runTest {
        val q = quarantaene(grenze = 100)
        for (paket in 1..5) {
            jetzt += 1_000
            assertTrue(q.lege(84u, 1u, 40u, ByteArray(40) { paket.toByte() }, "test"))
        }
        // 5 x 40 Byte bei 100 Byte Grenze: die zwei juengsten bleiben.
        assertEquals(setOf(4, 5), liegen())
    }

    @Test
    fun inDerselbenMillisekundeEntscheidetDieReihenfolge() = runTest {
        val q = quarantaene(grenze = 100)
        for (paket in 1..4) assertTrue(q.lege(84u, 1u, 40u, ByteArray(40) { paket.toByte() }, "test"))
        assertEquals(setOf(3, 4), liegen())
    }

    @Test
    fun dateienEinesFrueherenLaufsZaehlenMit() = runTest {
        // Von frueher: eine ganze Datei und eine halbe eines abgebrochenen Schreibens.
        // Der Zaehler im Namen beginnt nach jedem Start wieder bei 0, das Alter gibt die Zeit.
        File(ordner, "dls-tag84-item40-1000-s1-5.bin").writeBytes(ByteArray(60) { 7 })
        File(ordner, "dls-tag84-item40-2000-s1-0.bin.tmp").writeBytes(ByteArray(30) { 8 })
        File(ordner, "fremd.txt").writeText("bleibt")
        val q = quarantaene(grenze = 100)
        assertTrue(q.lege(84u, 1u, 40u, ByteArray(40) { 1 }, "test"))
        // 60 + 30 + 40 = 130 Byte: die aelteste (60) geht, 70 Byte bleiben.
        assertEquals(setOf(8, 1), liegen())
        assertTrue(File(ordner, "fremd.txt").exists())
    }

    @Test
    fun dasNeuePaketBleibtAuchUeberDerGrenze() = runTest {
        // Waere es weg, haette die Uhr es auf ein ACK hin geloescht.
        val q = quarantaene(grenze = 10)
        jetzt += 1_000
        assertTrue(q.lege(84u, 1u, 40u, ByteArray(40) { 1 }, "test"))
        jetzt += 1_000
        assertTrue(q.lege(84u, 1u, 40u, ByteArray(40) { 2 }, "test"))
        assertEquals(setOf(2), liegen())
    }

    @Test
    fun aufgeraeumtWirdBisAufAchtzigProzent() = runTest {
        // Nur bis knapp unter die Grenze, und jedes weitere Paket loeste wieder ein
        // Aufraeumen samt Lesen des ganzen Ordners aus.
        val q = quarantaene(grenze = 100)
        for (paket in 1..12) {
            jetzt += 1_000
            assertTrue(q.lege(84u, 1u, 10u, ByteArray(10) { paket.toByte() }, "test"))
        }
        // Paket 11 bringt 110 Byte: weg bis 80 (1..3). Paket 12 passt dann ohne Aufraeumen.
        assertEquals((4..12).toSet(), liegen())
    }

    @Test
    fun vorDemAckLiegtDasPaketMitNamenAufDemDatentraeger() = runTest {
        val platte = MitschreibendePlatte()
        assertTrue(quarantaene(grenze = 100, platte).lege(84u, 1u, 40u, ByteArray(40) { 1 }, "test"))
        // Erst die Hilfsdatei mit fsync, dann umbenannt, dann der Ordner - alles, bevor lege true sagt.
        assertEquals(listOf("schreibe tmp", "ordner [bin]"), platte.ereignisse)
        assertEquals(setOf(1), liegen())
    }

    @Test
    fun scheitertDasSchreibenWirdNichtBestaetigt() = runTest {
        val platte = MitschreibendePlatte(kaputt = "schreibe")
        assertFalse(quarantaene(grenze = 100, platte).lege(84u, 1u, 40u, ByteArray(40) { 1 }, "test"))
        assertEquals(emptySet(), liegen())
    }

    @Test
    fun ohneOrdnerSyncWirdTrotzdemBestaetigt() = runTest {
        // Sonst bestaetigte eine Plattform ohne Ordner-fsync nie, und die Uhr liefe voll.
        val platte = MitschreibendePlatte(kaputt = "ordner")
        assertTrue(quarantaene(grenze = 100, platte).lege(84u, 1u, 40u, ByteArray(40) { 1 }, "test"))
        assertEquals(setOf(1), liegen())
    }

    @Test
    fun dieEchtePlatteSchreibtGanzUndSichertDenOrdner() {
        // Die JVM-Fassung (Android ist derselbe Code): Inhalt vollstaendig, Ordner-fsync geht.
        val datei = File(ordner, "probe.bin")
        EchtePlatte.schreibe(Path(datei.path), ByteArray(70_000) { (it % 251).toByte() })
        assertEquals(ByteArray(70_000) { (it % 251).toByte() }.toList(), datei.readBytes().toList())
        EchtePlatte.sichereOrdner(Path(ordner.path))
    }

    @Test
    fun dieVorgabeSind20Megabyte() {
        assertEquals(20L * 1024 * 1024, DlsQuarantaene.GRENZE)
    }
}
