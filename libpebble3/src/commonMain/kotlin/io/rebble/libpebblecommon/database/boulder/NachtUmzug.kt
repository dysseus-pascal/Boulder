package io.rebble.libpebblecommon.database.boulder

import androidx.room.Transactor
import androidx.room.useReaderConnection
import androidx.room.useWriterConnection
import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.database.Database
import io.rebble.libpebblecommon.database.dao.NachtDao
import io.rebble.libpebblecommon.database.entity.NachtAbschnittEntity
import io.rebble.libpebblecommon.database.entity.NachtEntity
import io.rebble.libpebblecommon.database.entity.NachtHrvEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource

/**
 * Zieht die Naechte EINMAL aus libpebble3.db nach boulder.db.
 *
 * SICHER BEI ABBRUCH: die alten Tabellen werden nur gelesen; Daten und Vermerk
 * landen in EINER Transaktion in boulder.db, und vor dem Vermerk muessen
 * Zaehlungen und Pruefsummen beider Seiten gleich sein. Stirbt der Prozess
 * vorher, ist boulder.db leer und der naechste Start beginnt von vorn.
 *
 * Die alten Tabellen bleiben in dieser Fassung liegen; Room kennt sie nicht mehr.
 */
class NachtUmzug(
    private val alt: Database,
    private val neu: BoulderDatenbank,
    private val uhr: Clock = Clock.System,
    private val zeit: TimeSource.WithComparableMarks = TimeSource.Monotonic,
    private val pause: Duration = WIEDERHOLEN_NACH,
) {
    private val logger = Logger.withTag("NachtUmzug")
    private val sperre = Mutex()

    @Volatile
    private var erledigt = false

    // Der letzte Fehlschlag: bis zur Pause wirft jeder Zugriff ihn erneut, statt
    // bei jedem Datalogging-Paket alle Naechte noch einmal zu lesen.
    private var fehlschlag: Pair<Throwable, ComparableTimeMark>? = null

    // Nur fuer Tests: ein Abbruch zwischen Einfuegen und Vermerk.
    internal var vorDemVermerk: suspend () -> Unit = {}

    /** Kehrt erst zurueck, wenn der Umzug erledigt ist; wirft, wenn er scheitert. */
    suspend fun sicherstellen() {
        if (erledigt) return
        sperre.withLock {
            if (erledigt) return
            fehlschlag?.let { (fehler, wann) ->
                if (wann.elapsedNow() < pause) throw IllegalStateException("Night move failed recently", fehler)
            }
            try {
                fuehreAus()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fehlschlag = e to zeit.markNow()
                logger.e(e) { "Moving the nights to $BOULDER_DB_DATEINAME failed; next try in $pause" }
                throw e
            }
            fehlschlag = null
            erledigt = true
        }
    }

    private suspend fun fuehreAus() {
        if (neu.umzugDao().vermerk() != null) return
        val a = liesAlt()
        var geschrieben = false
        neu.useWriterConnection { t ->
            t.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
                val dao = neu.umzugDao()
                // Unter der Schreibsperre noch einmal: so zieht auch ein zweiter Prozess nicht doppelt.
                if (dao.vermerk() != null) return@withTransaction
                if (a.naechte.isNotEmpty()) dao.naechte(a.naechte)
                if (a.abschnitte.isNotEmpty()) dao.abschnitte(a.abschnitte)
                if (a.hrv.isNotEmpty()) dao.hrv(a.hrv)
                val ziel = summen(t, TABELLEN.toSet())
                check(ziel == a.summen) { "Night move: checksums differ, old=${a.summen} new=$ziel" }
                vorDemVermerk()
                dao.vermerke(
                    UmzugEntity(
                        naechte = a.naechte.size.toLong(),
                        abschnitte = a.abschnitte.size.toLong(),
                        hrv = a.hrv.size.toLong(),
                        zeitMs = uhr.now().toEpochMilliseconds(),
                    )
                )
                geschrieben = true
            }
        }
        if (geschrieben) {
            logger.i { "Umzug fertig: ${a.naechte.size} Naechte, ${a.abschnitte.size} Abschnitte, ${a.hrv.size} HRV" }
        }
    }

    private class AltBestand(
        val naechte: List<NachtEntity>,
        val abschnitte: List<NachtAbschnittEntity>,
        val hrv: List<NachtHrvEntity>,
        val summen: List<List<Long>>,
    )

    // Ein Lesevorgang, damit Zeilen und Pruefsummen denselben Stand zeigen.
    private suspend fun liesAlt(): AltBestand = alt.useReaderConnection { t ->
        t.withTransaction(Transactor.SQLiteTransactionType.DEFERRED) {
            // Eine frische Installation hat die alten Tabellen nie bekommen: dann ist nichts umzuziehen.
            val da = TABELLEN.filter { name ->
                t.usePrepared("SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?") {
                    it.bindText(1, name)
                    it.step()
                    it.getLong(0) > 0
                }
            }.toSet()
            val naechte = if ("boulder_nacht" !in da) emptyList() else t.usePrepared(
                "SELECT beginnUtc, utcZuLokal, einschlafen, aufwachen, schlafMinuten, ruhepuls, hrv, " +
                    "akkuBeginn, akkuEnde, geladen, messstufe FROM boulder_nacht"
            ) { s ->
                buildList {
                    while (s.step()) add(
                        NachtEntity(
                            beginnUtc = s.getLong(0), utcZuLokal = s.getInt(1), einschlafen = s.getInt(2),
                            aufwachen = s.getInt(3), schlafMinuten = s.getInt(4), ruhepuls = s.getInt(5),
                            hrv = s.getInt(6),
                            akkuBeginn = if (s.isNull(7)) null else s.getInt(7),
                            akkuEnde = if (s.isNull(8)) null else s.getInt(8),
                            geladen = s.getLong(9) != 0L,
                            messstufe = if (s.isNull(10)) null else s.getInt(10),
                        )
                    )
                }
            }
            val abschnitte = if ("boulder_nacht_abschnitt" !in da) emptyList() else t.usePrepared(
                "SELECT beginnUtc, von, bis, phase FROM boulder_nacht_abschnitt"
            ) { s ->
                buildList { while (s.step()) add(NachtAbschnittEntity(s.getLong(0), s.getInt(1), s.getInt(2), s.getInt(3))) }
            }
            val hrv = if ("boulder_nacht_hrv" !in da) emptyList() else t.usePrepared(
                "SELECT beginnUtc, minute, rmssd, puls FROM boulder_nacht_hrv"
            ) { s ->
                buildList { while (s.step()) add(NachtHrvEntity(s.getLong(0), s.getInt(1), s.getInt(2), s.getInt(3))) }
            }
            AltBestand(naechte, abschnitte, hrv, summen(t, da))
        }
    }

    companion object {
        /** So lange wirft ein gescheiterter Umzug, bevor der naechste Zugriff ihn neu versucht. */
        val WIEDERHOLEN_NACH = 1.minutes

        private val TABELLEN = listOf("boulder_nacht", "boulder_nacht_abschnitt", "boulder_nacht_hrv")

        // Je Tabelle Zahl, Summe der Schluessel und eine gewichtete Summe ueber alle
        // Spalten: vertauschte Spalten oder verlorene NULLs aendern sie.
        private val SUMMEN = mapOf(
            "boulder_nacht" to "SELECT COUNT(*), IFNULL(SUM(beginnUtc),0), IFNULL(SUM(utcZuLokal + 3*einschlafen + " +
                "5*aufwachen + 7*schlafMinuten + 11*ruhepuls + 13*hrv + 17*IFNULL(akkuBeginn,-1) + " +
                "19*IFNULL(akkuEnde,-1) + 23*geladen + 29*IFNULL(messstufe,-1)),0) FROM boulder_nacht",
            "boulder_nacht_abschnitt" to "SELECT COUNT(*), IFNULL(SUM(beginnUtc),0), " +
                "IFNULL(SUM((beginnUtc % 100003) * (von + 3*bis + 5*phase + 1)),0) FROM boulder_nacht_abschnitt",
            "boulder_nacht_hrv" to "SELECT COUNT(*), IFNULL(SUM(beginnUtc),0), " +
                "IFNULL(SUM((beginnUtc % 100003) * (minute + 3*rmssd + 5*puls + 1)),0) FROM boulder_nacht_hrv",
        )

        private suspend fun summen(t: Transactor, da: Set<String>): List<List<Long>> = TABELLEN.map { name ->
            if (name !in da) listOf(0L, 0L, 0L)
            else t.usePrepared(SUMMEN.getValue(name)) { s ->
                s.step()
                listOf(s.getLong(0), s.getLong(1), s.getLong(2))
            }
        }
    }
}

/**
 * NachtDao, der vor jedem Zugriff den Umzug abwartet: nie in eine halb gefuellte
 * boulder.db schreiben, nie eine leere lesen. Scheitert der Umzug, wirft jeder
 * Zugriff - der Datalogging-Weg bestaetigt dann nicht, und die Uhr behaelt die Nacht.
 *
 * JEDE METHODE VON NachtDao GEHOERT HIER HIN (Waechtertest): eine nicht
 * ueberschriebene mit Rumpf liefe am echten Dao vorbei, ohne dessen Transaktion.
 */
class UmgezogenerNachtDao(private val echt: NachtDao, private val umzug: NachtUmzug) : NachtDao {
    override suspend fun speichereNacht(nacht: NachtEntity) {
        umzug.sicherstellen()
        echt.speichereNacht(nacht)
    }

    override suspend fun speichereAbschnitte(abschnitte: List<NachtAbschnittEntity>) {
        umzug.sicherstellen()
        echt.speichereAbschnitte(abschnitte)
    }

    override suspend fun loescheAbschnitte(beginnUtc: Long) {
        umzug.sicherstellen()
        echt.loescheAbschnitte(beginnUtc)
    }

    override suspend fun naechte(von: Long, bis: Long): List<NachtEntity> {
        umzug.sicherstellen()
        return echt.naechte(von, bis)
    }

    override suspend fun juengsteNacht(): NachtEntity? {
        umzug.sicherstellen()
        return echt.juengsteNacht()
    }

    override suspend fun nacht(beginnUtc: Long): NachtEntity? {
        umzug.sicherstellen()
        return echt.nacht(beginnUtc)
    }

    override suspend fun setzeHrvWennLeer(beginnUtc: Long, hrv: Int): Int {
        umzug.sicherstellen()
        return echt.setzeHrvWennLeer(beginnUtc, hrv)
    }

    override suspend fun abschnitte(beginnUtc: Long): List<NachtAbschnittEntity> {
        umzug.sicherstellen()
        return echt.abschnitte(beginnUtc)
    }

    override suspend fun abschnitteZwischen(von: Long, bis: Long): List<NachtAbschnittEntity> {
        umzug.sicherstellen()
        return echt.abschnitteZwischen(von, bis)
    }

    override suspend fun speichereHrv(fenster: List<NachtHrvEntity>) {
        umzug.sicherstellen()
        echt.speichereHrv(fenster)
    }

    override suspend fun loescheHrv(beginnUtc: Long) {
        umzug.sicherstellen()
        echt.loescheHrv(beginnUtc)
    }

    override suspend fun hrv(beginnUtc: Long): List<NachtHrvEntity> {
        umzug.sicherstellen()
        return echt.hrv(beginnUtc)
    }

    override suspend fun speichereNachtTeil(nacht: NachtEntity, ersterTeil: Boolean, abschnitte: List<NachtAbschnittEntity>) {
        umzug.sicherstellen()
        echt.speichereNachtTeil(nacht, ersterTeil, abschnitte)
    }

    override suspend fun speichereHrvTeil(beginnUtc: Long, ersterTeil: Boolean, fenster: List<NachtHrvEntity>) {
        umzug.sicherstellen()
        echt.speichereHrvTeil(beginnUtc, ersterTeil, fenster)
    }
}
