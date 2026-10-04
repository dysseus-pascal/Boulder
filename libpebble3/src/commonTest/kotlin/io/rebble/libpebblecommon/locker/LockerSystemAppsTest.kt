package io.rebble.libpebblecommon.locker

import io.rebble.libpebblecommon.database.dao.LockerEntryRealDao
import io.rebble.libpebblecommon.database.entity.LockerEntry
import io.rebble.libpebblecommon.database.entity.LockerEntryEntity
import io.rebble.libpebblecommon.database.entity.LockerEntrySyncEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

/**
 * Boulder: wohin TimeStyle Bedrock in einen SCHON BESTEHENDEN Locker kommt
 * (Telefon mit Vorgeschichte, Update auf 0.48+): hinter Kickstart, seinen
 * Vorgaenger - nicht mit seiner Standardstelle 0 irgendwo vorn.
 *
 * Der Locker liegt hier im Speicher ([LockerImSpeicher]): die SQL-Abfragen,
 * die systemAppsEinfuegen benutzt, sind darin Zeile fuer Zeile nachgebildet
 * (Reihenfolge nach orderIndex und Titel, Verschieben BETWEEN einschliesslich,
 * nur derselbe Typ). Geprueft wird die Logik von systemAppsEinfuegen, nicht Room.
 */
class LockerSystemAppsTest {
    private val ts = SystemApps.TimeStyleBedrock.uuid

    private fun app(titel: String, typ: AppType, stelle: Int, system: Boolean = false, id: Uuid = Uuid.random()) =
        LockerEntry(
            id = id, version = "1", title = titel, type = typ.code, developerName = "", configurable = false,
            pbwVersionCode = "", platforms = emptyList(), orderIndex = stelle, systemApp = system,
        )

    /** Ein Locker von vor 0.48: alle Systemapps ausser TimeStyle, dazu zwei Zifferblaetter aus dem Store. */
    private fun alterLocker(vararg zifferblaetter: Pair<String, Int>): LockerImSpeicher {
        val dao = LockerImSpeicher()
        SystemApps.entries.filter { it != SystemApps.TimeStyleBedrock }.forEach {
            dao.lege(app(it.name, it.type, it.defaultOrder, system = true, id = it.uuid))
        }
        zifferblaetter.forEach { (titel, stelle) -> dao.lege(app(titel, AppType.Watchface, stelle)) }
        dao.ordne(AppType.Watchface.code)
        dao.ordne(AppType.Watchapp.code)
        return dao
    }

    @Test
    fun hinterKickstartInDieVorgabeReihenfolge() = runTest {
        val dao = alterLocker("Store A" to 5, "Store B" to 6)
        assertEquals(listOf("Tictoc", "Kickstart", "Store A", "Store B"), dao.zifferblaetter())
        systemAppsEinfuegen(dao, force = false, syncLimit = 20)
        assertEquals(listOf("Tictoc", "Kickstart", "TimeStyleBedrock", "Store A", "Store B"), dao.zifferblaetter())
        assertEquals(2, dao.stelle(ts))
    }

    @Test
    fun hinterKickstartAuchWennDerNutzerUmsortiertHat() = runTest {
        // Nach Nutzung sortiert: Kickstart steht hinter den Store-Zifferblaettern.
        val dao = alterLocker("Store A" to -10, "Store B" to -9)
        assertEquals(listOf("Store A", "Store B", "Tictoc", "Kickstart"), dao.zifferblaetter())
        systemAppsEinfuegen(dao, force = false, syncLimit = 20)
        assertEquals(listOf("Store A", "Store B", "Tictoc", "Kickstart", "TimeStyleBedrock"), dao.zifferblaetter())
    }

    @Test
    fun einZweiterLaufAendertNichts() = runTest {
        val dao = alterLocker("Store A" to 5)
        systemAppsEinfuegen(dao, force = false, syncLimit = 20)
        val vorher = dao.zifferblaetter()
        systemAppsEinfuegen(dao, force = false, syncLimit = 20)
        assertEquals(vorher, dao.zifferblaetter())
    }

    @Test
    fun appsBleibenWoSieSind() = runTest {
        // TimeStyle ist ein Zifferblatt: die Reihenfolge der Apps bleibt unberuehrt.
        val dao = alterLocker("Store A" to 5)
        val apps = dao.apps()
        systemAppsEinfuegen(dao, force = false, syncLimit = 20)
        assertEquals(apps, dao.apps())
    }
}

/** Ein Locker im Speicher mit den Abfragen, die systemAppsEinfuegen braucht; alles andere wirft. */
class LockerImSpeicher : LockerEntryRealDao {
    private val zeilen = linkedMapOf<Uuid, LockerEntryEntity>()

    fun lege(e: LockerEntry) {
        zeilen[e.id] = LockerEntryEntity(0, false, e)
    }

    /** UPDATE ... ROW_NUMBER() OVER (ORDER BY orderIndex, title) - 1 fuer einen Typ. */
    fun ordne(typ: String) {
        zeilen.values.filter { !it.deleted && it.record.type == typ }
            .sortedWith(compareBy({ it.record.orderIndex }, { it.record.title }))
            .forEachIndexed { i, z -> zeilen[z.record.id] = z.copy(record = z.record.copy(orderIndex = i)) }
    }

    private fun liste(typ: AppType) = zeilen.values.filter { !it.deleted && it.record.type == typ.code }
        .sortedWith(compareBy({ it.record.orderIndex }, { it.record.title })).map { it.record.title }

    fun zifferblaetter() = liste(AppType.Watchface)
    fun apps() = liste(AppType.Watchapp)
    fun stelle(id: Uuid) = zeilen.getValue(id).record.orderIndex

    override suspend fun getAllBasicInfo(): List<LockerEntryRealDao.DbAppBasicProperties> =
        zeilen.values.filter { !it.deleted }.map {
            val r = it.record
            LockerEntryRealDao.DbAppBasicProperties(r.id, r.title, r.type, r.developerName, r.orderIndex, r.active, r.grantedPermissions, r.sideloaded, r.systemApp)
        }

    override suspend fun markForDeletion(id: Uuid) {
        zeilen[id]?.let { zeilen[id] = it.copy(deleted = true) }
    }

    override suspend fun insertOrReplace(item: LockerEntryEntity) {
        zeilen[item.record.id] = item
    }

    override suspend fun insertOrReplaceAll(items: List<LockerEntryEntity>) = items.forEach { zeilen[it.record.id] = it }

    override suspend fun updateOrder(type: String) = ordne(type)

    override suspend fun updateOrder(id: Uuid, orderIndex: Int) {
        zeilen[id]?.let { zeilen[id] = it.copy(record = it.record.copy(orderIndex = orderIndex)) }
    }

    private fun verschiebe(von: Int, bis: Int, typ: String, um: Int) {
        zeilen.values.filter { it.record.type == typ && it.record.orderIndex in von..bis }.forEach {
            zeilen[it.record.id] = it.copy(record = it.record.copy(orderIndex = it.record.orderIndex + um))
        }
    }

    override suspend fun incrementOrderIndexes(fromIndex: Int, toIndex: Int, type: String) = verschiebe(fromIndex, toIndex, type, 1)
    override suspend fun decrementOrderIndexes(fromIndex: Int, toIndex: Int, type: String) = verschiebe(fromIndex, toIndex, type, -1)
    override suspend fun updateSync(syncLimit: Int) = Unit
    override suspend fun getEntry(id: Uuid): LockerEntry? = zeilen[id]?.takeIf { !it.deleted }?.record

    // --- im Test nicht gebraucht ---
    private fun fehlt(): Nothing = error("LockerImSpeicher: im Test nicht gebraucht")
    override fun getAllFlow(type: String, searchQuery: String?, limit: Int): Flow<List<LockerEntry>> = fehlt()
    override fun getAllUuidsFlow(): Flow<List<Uuid>> = fehlt()
    override fun getAppOrderFlow(type: String, limit: Int): Flow<List<Uuid>> = fehlt()
    override fun getAllBasicInfoFlow(): Flow<List<LockerEntryRealDao.DbAppBasicProperties>> = fehlt()
    override suspend fun getAll(): List<LockerEntry> = fehlt()
    override suspend fun _setActiveForUuid(uuid: String) = fehlt()
    override suspend fun _setNonActiveExceptUuid(uuid: String) = fehlt()
    override fun getActiveWatchface(): Flow<LockerEntry?> = fehlt()
    override fun dirtyRecordsForWatchInsert(identifier: String, timestampMs: Long, insertOnlyAfterMs: Long): Flow<List<LockerEntryEntity>> = fehlt()
    override fun dirtyRecordsForWatchDelete(identifier: String, timestampMs: Long): Flow<List<LockerEntryEntity>> = fehlt()
    override suspend fun deleteStaleRecords(timestampMs: Long) = fehlt()
    override suspend fun markSyncedToWatch(syncRecord: LockerEntrySyncEntity) = fehlt()
    override suspend fun markDeletedFromWatch(syncRecord: LockerEntrySyncEntity) = fehlt()
    override fun existsOnWatch(identifier: String, primaryKey: Uuid): Flow<Boolean> = fehlt()
    override suspend fun markAllForDeletion(ids: List<Uuid>) = fehlt()
    override suspend fun markAllDeletedFromWatch(identifier: String) = fehlt()
    override suspend fun deleteSyncRecordsForDevicesWhichDontExist() = fehlt()
    override fun getEntryFlow(id: Uuid): Flow<LockerEntry?> = fehlt()
}
