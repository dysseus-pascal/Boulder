package io.rebble.libpebblecommon.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.rebble.libpebblecommon.database.entity.NachtAbschnittEntity
import io.rebble.libpebblecommon.database.entity.NachtEntity
import io.rebble.libpebblecommon.database.entity.NachtHrvEntity

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
}
