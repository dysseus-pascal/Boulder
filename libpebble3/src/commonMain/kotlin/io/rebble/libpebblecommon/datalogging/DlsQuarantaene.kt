package io.rebble.libpebblecommon.datalogging

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.fetchAndIncrement
import kotlin.time.Clock

/**
 * Boulder: Ablage fuer Datalogging-Pakete, die Boulder nicht oder nur zum Teil
 * lesen kann.
 *
 * Solche Pakete werden trotzdem bestaetigt: unbestaetigt schickte die Uhr sie
 * nach jeder Wartezeit wieder, und ihr DLS-Speicher fuer diesen Tag liefe voll.
 * Damit nichts verloren geht, liegt jedes roh in einer eigenen Datei; Tag und
 * Itemgroesse stehen im Namen, so kann eine spaetere Fassung sie nachlesen.
 */
class DlsQuarantaene(
    private val clock: Clock,
    // Erst beim ersten Paket aufgeloest: der Ort haengt an der Plattform.
    private val verzeichnis: () -> Path,
) {
    private val logger = Logger.withTag("DlsQuarantaene")
    private val zaehler = AtomicInt(0)

    /** Legt [daten] ab. true, wenn sie auf dem Speicher liegen - erst dann darf die Uhr sie freigeben. */
    fun lege(tag: UInt, sessionId: UByte, itemSize: UShort, daten: ByteArray, grund: String): Boolean {
        return try {
            val ordner = verzeichnis()
            SystemFileSystem.createDirectories(ordner, mustCreate = false)
            val name = "dls-tag$tag-item$itemSize-${clock.now().toEpochMilliseconds()}-s$sessionId-${zaehler.fetchAndIncrement()}.bin"
            val ziel = Path(ordner, name)
            // Erst fertig schreiben, dann umbenennen: eine halbe Datei saehe aus wie ein kurzes Paket.
            val halb = Path(ordner, "$name.tmp")
            SystemFileSystem.sink(halb).buffered().use { it.write(daten) }
            SystemFileSystem.atomicMove(halb, ziel)
            logger.w { "DLS packet quarantined: $ziel (${daten.size} bytes, tag $tag): $grund" }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(e) { "DLS packet NOT quarantined (tag $tag, ${daten.size} bytes, $grund); not acknowledging" }
            false
        }
    }
}
