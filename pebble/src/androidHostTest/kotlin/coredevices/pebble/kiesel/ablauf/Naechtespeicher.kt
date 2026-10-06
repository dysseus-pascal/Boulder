package coredevices.pebble.kiesel.ablauf

import android.content.Context
import androidx.room.Room
import io.rebble.libpebblecommon.connection.FakeLibPebble
import io.rebble.libpebblecommon.connection.HealthDataApi
import io.rebble.libpebblecommon.database.boulder.BoulderDatenbank
import io.rebble.libpebblecommon.database.dao.NachtDao

/**
 * Boulders echte Nachtdatenbank (boulder.db) im Speicher - die Tabellen und
 * Abfragen der App, wie im SchritthalbstundeTest von libpebble3, hier mit
 * dem SQLite von Robolectric.
 */
internal fun naechteImSpeicher(context: Context): BoulderDatenbank =
    Room.inMemoryDatabaseBuilder<BoulderDatenbank>(context).allowMainThreadQueries().build()

/**
 * Der Zugang der App zu den Naechten (HealthDataApi) auf [dao]: Lesen und
 * das bedingte Eintragen der HRV gehen an die echte Datenbank, wie in
 * libpebble3s Health; alles andere wie die Vorschau-Bibliothek.
 */
internal fun naechteApi(dao: NachtDao): HealthDataApi = object : HealthDataApi by FakeLibPebble() {
    override suspend fun getNaechte(von: Long, bis: Long) = dao.naechte(von, bis)
    override suspend fun setzeNachtHrvWennLeer(beginnUtc: Long, hrv: Int) = dao.setzeHrvWennLeer(beginnUtc, hrv) > 0
}
