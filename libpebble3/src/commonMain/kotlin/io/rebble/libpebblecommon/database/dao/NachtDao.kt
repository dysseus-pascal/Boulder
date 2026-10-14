package io.rebble.libpebblecommon.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import io.rebble.libpebblecommon.database.entity.NachtAbschnittEntity
import io.rebble.libpebblecommon.database.entity.NachtEntity
import io.rebble.libpebblecommon.database.entity.NachtHrvEntity
import io.rebble.libpebblecommon.database.entity.mitHrvVon

@Dao
interface NachtDao {
    // ERSETZEN, NICHT DOPPELN: kommt eine Nacht noch einmal (die Uhr schickt
    // nach einem Neustart erneut), steht sie danach einmal da.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun speichereNacht(nacht: NachtEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun speichereAbschnitte(abschnitte: List<NachtAbschnittEntity>)

    // Kommt eine Nacht gewachsen noch einmal (die Uhr schickt sie nach weiterem
    // Schlaf erneut), passen die alten Abschnitte nicht mehr zu den neuen.
    @Query("DELETE FROM boulder_nacht_abschnitt WHERE beginnUtc = :beginnUtc")
    suspend fun loescheAbschnitte(beginnUtc: Long)

    @Query("SELECT * FROM boulder_nacht WHERE beginnUtc >= :von AND beginnUtc < :bis ORDER BY beginnUtc ASC")
    suspend fun naechte(von: Long, bis: Long): List<NachtEntity>

    @Query("SELECT * FROM boulder_nacht ORDER BY beginnUtc DESC LIMIT 1")
    suspend fun juengsteNacht(): NachtEntity?

    @Query("SELECT * FROM boulder_nacht WHERE beginnUtc = :beginnUtc")
    suspend fun nacht(beginnUtc: Long): NachtEntity?

    /**
     * Die HRV einer Nacht eintragen - NUR WENN SIE KEINE HAT (0 oder null).
     * Die Bedingung steht in der Abfrage selbst: kommt die Nacht zwischen
     * Lesen und Schreiben mit einer gemessenen HRV neu, bleibt die neue.
     * Liefert die Zahl der geaenderten Naechte (0 oder 1).
     */
    @Query("UPDATE boulder_nacht SET hrv = :hrv WHERE beginnUtc = :beginnUtc AND (hrv IS NULL OR hrv <= 0)")
    suspend fun setzeHrvWennLeer(beginnUtc: Long, hrv: Int): Int

    @Query("SELECT * FROM boulder_nacht_abschnitt WHERE beginnUtc = :beginnUtc ORDER BY von ASC")
    suspend fun abschnitte(beginnUtc: Long): List<NachtAbschnittEntity>

    // Die Trends brauchen die Phasen von bis zu 180 Naechten: eine Abfrage
    // fuer alle statt einer je Nacht.
    @Query("SELECT * FROM boulder_nacht_abschnitt WHERE beginnUtc >= :von AND beginnUtc < :bis ORDER BY beginnUtc ASC, von ASC")
    suspend fun abschnitteZwischen(von: Long, bis: Long): List<NachtAbschnittEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun speichereHrv(fenster: List<NachtHrvEntity>)

    // Wie bei den Abschnitten: schickt die Uhr die Nacht gewachsen noch einmal,
    // kommen alle Fenster neu.
    @Query("DELETE FROM boulder_nacht_hrv WHERE beginnUtc = :beginnUtc")
    suspend fun loescheHrv(beginnUtc: Long)

    @Query("SELECT * FROM boulder_nacht_hrv WHERE beginnUtc = :beginnUtc ORDER BY minute ASC")
    suspend fun hrv(beginnUtc: Long): List<NachtHrvEntity>

    // Ein Teil am Stueck: endete die App zwischen Loeschen und Schreiben, fehlten
    // sonst die Abschnitte dieser Nacht.
    //
    // KEINE LEERE HRV UEBER EINE GEMESSENE: bis Firmware b24 lagen die
    // HRV-Fenster nur im RAM der Uhr. Nach einem Firmware-Update am Morgen
    // rechnete sie die Nacht ohne HRV neu und schickte sie mit demselben
    // Beginn noch einmal - das Ersetzen nahm hrv = 0 mit, und die gemessene
    // war weg (1.10. und 3.10.2026). Alles andere ersetzt die neue Fassung
    // wie bisher, und eine neue HRV ueber 0 ersetzt die alte.
    @Transaction
    suspend fun speichereNachtTeil(nacht: NachtEntity, ersterTeil: Boolean, abschnitte: List<NachtAbschnittEntity>) {
        speichereNacht(nacht.mitHrvVon(nacht(nacht.beginnUtc)))
        if (ersterTeil) loescheAbschnitte(nacht.beginnUtc)
        speichereAbschnitte(abschnitte)
    }

    @Transaction
    suspend fun speichereHrvTeil(beginnUtc: Long, ersterTeil: Boolean, fenster: List<NachtHrvEntity>) {
        if (ersterTeil) loescheHrv(beginnUtc)
        speichereHrv(fenster)
    }
}
