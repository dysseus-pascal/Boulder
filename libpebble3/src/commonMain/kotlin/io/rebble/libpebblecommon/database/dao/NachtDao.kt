package io.rebble.libpebblecommon.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.rebble.libpebblecommon.database.entity.NachtAbschnittEntity
import io.rebble.libpebblecommon.database.entity.NachtEntity

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
}
