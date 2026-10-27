package io.rebble.libpebblecommon.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Eine Nacht, wie die Boulder-Firmware sie gerechnet hat (Datalogging-Tag 88).
 *
 * DIE UHR RECHNET, DAS TELEFON BEWAHRT. Schlaf, Phasen, Ruhepuls und HRV
 * kommen fertig von der Uhr; hier stehen sie so, wie sie ankamen. Alle
 * Minutenangaben zaehlen ab [beginnUtc], dem Beginn des Nachtfensters.
 */
@Entity(tableName = "boulder_nacht")
data class NachtEntity(
    @PrimaryKey
    val beginnUtc: Long,
    /** Sekunden, die zur UTC-Zeit kommen, um Ortszeit zu erhalten. */
    val utcZuLokal: Int,
    val einschlafen: Int,
    val aufwachen: Int,
    val schlafMinuten: Int,
    /** Schlaege je Minute; 0 = keiner. */
    val ruhepuls: Int,
    /** RMSSD in ms; 0 = keine Messung. */
    val hrv: Int,
    /** Akku in % beim Einschlafen und am Ende; null, wo die Firmware es nicht schickt. */
    @ColumnInfo(defaultValue = "NULL")
    val akkuBeginn: Int? = null,
    @ColumnInfo(defaultValue = "NULL")
    val akkuEnde: Int? = null,
    /** Hing die Uhr nachts am Kabel? Dann sagt der Verbrauch nichts. */
    @ColumnInfo(defaultValue = "0")
    val geladen: Boolean = false,
    /** Die Messstufe dieser Nacht (1..5); null bei aelterer Firmware. */
    @ColumnInfo(defaultValue = "NULL")
    val messstufe: Int? = null,
    /**
     * Von Hand begonnen und beendet (Schlafmodus der Uhr, ab b40). [beginnUtc] ist dann der
     * Beginn von Hand und kann zu jeder Tageszeit liegen, nicht nur am Abend.
     */
    @ColumnInfo(defaultValue = "0")
    val manuell: Boolean = false,
)

/**
 * Diese Fassung einer Nacht, aber mit der HRV von [gespeichert], wenn diese
 * keine hat (0) und die gespeicherte eine gemessene (ueber 0). Sonst
 * unveraendert - siehe NachtDao.speichereNachtTeil.
 */
fun NachtEntity.mitHrvVon(gespeichert: NachtEntity?): NachtEntity =
    if (hrv <= 0 && gespeichert != null && gespeichert.hrv > 0) copy(hrv = gespeichert.hrv) else this

/**
 * Ein Abschnitt einer Nacht: [von] bis vor [bis], in Minuten ab dem Beginn der
 * Nacht. [phase] wie SchlafPhase in der Firmware: 0 wach, 1 leicht, 2 tief,
 * 3 REM, 4 Schlaf ohne Phase (Nacht ohne Puls).
 */
@Entity(tableName = "boulder_nacht_abschnitt", primaryKeys = ["beginnUtc", "von"])
data class NachtAbschnittEntity(
    val beginnUtc: Long,
    val von: Int,
    val bis: Int,
    val phase: Int,
)

/**
 * Ein HRV-Fenster einer Nacht (Datalogging-Tag 89): fuenf Minuten ab [minute]
 * (Minuten ab [beginnUtc], wie die Abschnitte), daraus der RMSSD und der Puls.
 *
 * EIGENE TABELLE STATT SPALTE: die Uhr schickt je nach Messstufe bis zu 96
 * Fenster je Nacht, und eine aeltere Firmware gar keine - dann bleibt die
 * Tabelle fuer diese Nacht leer.
 */
@Entity(tableName = "boulder_nacht_hrv", primaryKeys = ["beginnUtc", "minute"])
data class NachtHrvEntity(
    val beginnUtc: Long,
    val minute: Int,
    /** RMSSD in ms. */
    val rmssd: Int,
    /** Schlaege je Minute im Fenster; 0 = unbekannt. */
    val puls: Int,
)
