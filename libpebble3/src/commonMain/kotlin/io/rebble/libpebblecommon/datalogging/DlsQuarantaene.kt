package io.rebble.libpebblecommon.datalogging

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 *
 * Die Ablage ist auf [grenze] Bytes begrenzt. Darueber gehen die aeltesten
 * Dateien weg (mit Log), bis wieder ein Fuenftel frei ist, damit ein dauerhaft
 * unlesbarer Tag den App-Speicher nicht fuellt.
 */
class DlsQuarantaene(
    private val clock: Clock,
    private val grenze: Long = GRENZE,
    private val platte: QuarantaenePlatte = EchtePlatte,
    // Erst beim ersten Paket aufgeloest: der Ort haengt an der Plattform.
    private val verzeichnis: () -> Path,
) {
    private val logger = Logger.withTag("DlsQuarantaene")
    private val zaehler = AtomicInt(0)

    // Mehrere Uhren koennen gleichzeitig ablegen; Summe und Aufraeumen gehen der Reihe nach.
    private val sperre = Mutex()

    // Belegte Bytes, einmal gezaehlt und dann mitgefuehrt (null: noch nicht gezaehlt).
    // So muss nicht jedes Paket den ganzen Ordner lesen.
    private var belegt: Long? = null

    /** Legt [daten] ab. true, wenn sie auf dem Speicher liegen - erst dann darf die Uhr sie freigeben. */
    suspend fun lege(tag: UInt, sessionId: UByte, itemSize: UShort, daten: ByteArray, grund: String): Boolean =
        sperre.withLock {
            val ordner = try {
                verzeichnis().also { SystemFileSystem.createDirectories(it, mustCreate = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e(e) { "DLS packet NOT quarantined (tag $tag, ${daten.size} bytes, $grund): no folder; not acknowledging" }
                return@withLock false
            }
            val name = "dls-tag$tag-item$itemSize-${clock.now().toEpochMilliseconds()}-s$sessionId-${zaehler.fetchAndIncrement()}.bin"
            val ziel = Path(ordner, name)
            try {
                // Erst fertig schreiben, dann umbenennen: eine halbe Datei saehe aus wie ein kurzes Paket.
                // Auf dem Datentraeger (fsync), bevor sie ihren Namen bekommt: nach dem ACK
                // gibt die Uhr ihre Kopie auf.
                val halb = Path(ordner, "$name.tmp")
                platte.schreibe(halb, daten)
                SystemFileSystem.atomicMove(halb, ziel)
                logger.w { "DLS packet quarantined: $ziel (${daten.size} bytes, tag $tag): $grund" }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e(e) { "DLS packet NOT quarantined (tag $tag, ${daten.size} bytes, $grund); not acknowledging" }
                return@withLock false
            }
            // Auch der neue Name muss auf den Datentraeger. Scheitert das, wird trotzdem
            // bestaetigt: die Datei liegt schon, und eine Plattform ohne Ordner-fsync
            // bestaetigte sonst nie - der DLS-Speicher der Uhr liefe voll.
            try {
                platte.sichereOrdner(ordner)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.w(e) { "DLS quarantine folder not synced after $name; acknowledging anyway" }
            }
            // Das Paket liegt; ein Fehler beim Aufraeumen aendert daran nichts.
            begrenze(ordner, ziel, daten.size.toLong())
            true
        }

    private class Datei(val pfad: Path, val groesse: Long, val zeit: Long, val nummer: Int)

    // Die mitgefuehrte Summe entscheidet nur, OB aufgeraeumt wird; was weggeht,
    // entscheidet ein frischer Blick in den Ordner, nie eine veraltete Liste.
    private fun begrenze(ordner: Path, neu: Path, groesse: Long) {
        try {
            val summe = belegt?.plus(groesse) ?: dateien(ordner).sumOf { it.groesse }
            belegt = summe
            if (summe <= grenze) return
            val bestand = dateien(ordner).sortedWith(compareBy({ it.zeit }, { it.nummer }))
            var rest = bestand.sumOf { it.groesse }
            // Bis auf 80 % der Grenze: raeumte es nur bis knapp darunter, laese jedes
            // weitere Paket den ganzen Ordner (Tausende Dateien) im DLS-Arbeiter neu ein.
            val bisAuf = grenze * NACH_DEM_AUFRAEUMEN_PROZENT / 100
            var weg = 0
            var wegBytes = 0L
            for (d in bestand) {
                if (rest <= bisAuf) break
                // Das eben abgelegte Paket bleibt immer, sonst waere sein ACK gelogen.
                if (d.pfad == neu) continue
                SystemFileSystem.delete(d.pfad, mustExist = false)
                rest -= d.groesse
                weg++
                wegBytes += d.groesse
            }
            belegt = rest
            // Frisch gezaehlt kann es schon passen (Dateien von aussen entfernt): dann nichts zu melden.
            if (weg > 0) {
                logger.w { "DLS quarantine over $grenze bytes: removed $weg oldest files ($wegBytes bytes), $rest bytes left" }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Beim naechsten Paket wird neu gezaehlt.
            belegt = null
            logger.e(e) { "DLS quarantine size check failed" }
        }
    }

    // Nur die eigenen Dateien, auch halbe (.tmp) eines abgebrochenen Schreibens.
    // Das Alter steht im Namen: Zeitpunkt, dann die laufende Nummer.
    private fun dateien(ordner: Path): List<Datei> =
        SystemFileSystem.list(ordner).mapNotNull { pfad ->
            val treffer = NAME.matchEntire(pfad.name) ?: return@mapNotNull null
            val meta = SystemFileSystem.metadataOrNull(pfad) ?: return@mapNotNull null
            if (!meta.isRegularFile) return@mapNotNull null
            Datei(pfad, meta.size, treffer.groupValues[1].toLong(), treffer.groupValues[2].toInt())
        }

    companion object {
        /** 20 MB: Platz fuer Zehntausende Pakete, klein gegen den App-Speicher. */
        const val GRENZE: Long = 20L * 1024 * 1024

        /** So viel der Grenze bleibt nach dem Aufraeumen belegt. */
        const val NACH_DEM_AUFRAEUMEN_PROZENT = 80

        /**
         * Der Ordner im App-Speicher (Android: filesDir). backup_rules.xml nimmt ihn
         * vom Cloud-Backup aus - wer ihn umbenennt, zieht die Regeln mit (BackupRulesTest).
         */
        const val ORDNER = "dls-quarantaene"

        private val NAME = Regex("""dls-tag\d+-item\d+-(\d+)-s\d+-(\d+)\.bin(\.tmp)?""")
    }
}

/**
 * Boulder: der Datentraeger der [DlsQuarantaene]. NUR FUER TESTS austauschbar,
 * die pruefen, was vor dem ACK auf dem Datentraeger liegt.
 */
interface QuarantaenePlatte {
    /** Schreibt [daten] nach [pfad], mit fsync. Wirft, wenn das nicht ging. */
    fun schreibe(pfad: Path, daten: ByteArray)

    /** Bringt die Namen in [ordner] auf den Datentraeger. Wirft, wenn das nicht ging. */
    fun sichereOrdner(ordner: Path)
}

/** Boulder: der echte Datentraeger der Plattform. */
object EchtePlatte : QuarantaenePlatte {
    override fun schreibe(pfad: Path, daten: ByteArray) = schreibeDauerhaft(pfad, daten)

    override fun sichereOrdner(ordner: Path) = ordnerDauerhaft(ordner)
}
