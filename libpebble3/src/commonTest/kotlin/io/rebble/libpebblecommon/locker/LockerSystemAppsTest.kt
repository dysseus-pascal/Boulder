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
 * Boulder: was systemAppsEinfuegen mit einem SCHON BESTEHENDEN Locker macht (Telefon mit
 * Vorgeschichte): eine Systemapp, die spaeter dazukommt (Kieselstrasse), steht hinter ihrem
 * Vorgaenger statt mit ihrer Standardstelle irgendwo vorn; eine Systemapp, die es in der Firmware
 * nicht mehr gibt (Workout, TimeStyle Bedrock aus 0.53), faellt heraus.
 *
 * Der Locker liegt hier im Speicher ([LockerImSpeicher]): die SQL-Abfragen,
 * die systemAppsEinfuegen benutzt, sind darin Zeile fuer Zeile nachgebildet
 * (Reihenfolge nach orderIndex und Titel, Verschieben BETWEEN einschliesslich,
 * nur derselbe Typ). Geprueft wird die Logik von systemAppsEinfuegen, nicht Room.
 */
class LockerSystemAppsTest {
    private val strasse = SystemApps.Kieselstrasse.uuid

    private fun app(titel: String, typ: AppType, stelle: Int, system: Boolean = false, id: Uuid = Uuid.random()) =
        LockerEntry(
            id = id, version = "1", title = titel, type = typ.code, developerName = "", configurable = false,
            pbwVersionCode = "", platforms = emptyList(), orderIndex = stelle, systemApp = system,
        )

    /** Ein Locker ohne Kieselstrasse: alle anderen Systemapps, dazu Apps aus dem Store. */
    private fun alterLocker(vararg apps: Pair<String, Int>): LockerImSpeicher {
        val dao = LockerImSpeicher()
        SystemApps.entries.filter { it != SystemApps.Kieselstrasse }.forEach {
            dao.lege(app(it.name, it.type, it.defaultOrder, system = true, id = it.uuid))
        }
        apps.forEach { (titel, stelle) -> dao.lege(app(titel, AppType.Watchapp, stelle)) }
        dao.ordne(AppType.Watchface.code)
        dao.ordne(AppType.Watchapp.code)
        return dao
    }

    @Test
    fun hinterKieselsportInDieVorgabeReihenfolge() = runTest {
        val dao = alterLocker("Store A" to 50, "Store B" to 51)
        systemAppsEinfuegen(dao, force = false, syncLimit = 20)
        val apps = dao.apps()
        assertEquals(apps.indexOf("Kieselsport") + 1, apps.indexOf("Kieselstrasse"), apps.toString())
        assertEquals(listOf("Store A", "Store B", "Timeline"), apps.takeLast(3))
    }

    @Test
    fun hinterKieselsportAuchWennDerNutzerUmsortiertHat() = runTest {
        // Nach Nutzung sortiert: die Store-Apps stehen vor den Systemapps.
        val dao = alterLocker("Store A" to -30, "Store B" to -29)
        assertEquals(listOf("Store A", "Store B"), dao.apps().take(2))
        systemAppsEinfuegen(dao, force = false, syncLimit = 20)
        val apps = dao.apps()
        assertEquals(listOf("Store A", "Store B"), apps.take(2))
        assertEquals(apps.indexOf("Kieselsport") + 1, apps.indexOf("Kieselstrasse"), apps.toString())
    }

    @Test
    fun einZweiterLaufAendertNichts() = runTest {
        val dao = alterLocker("Store A" to 50)
        systemAppsEinfuegen(dao, force = false, syncLimit = 20)
        val vorher = dao.apps()
        systemAppsEinfuegen(dao, force = false, syncLimit = 20)
        assertEquals(vorher, dao.apps())
        assertEquals(vorher.indexOf("Kieselstrasse"), dao.stelle(strasse))
    }

    @Test
    fun zifferblaetterBleibenWoSieSind() = runTest {
        // Kieselstrasse ist eine App: die Reihenfolge der Zifferblaetter bleibt unberuehrt.
        val dao = alterLocker("Store A" to 50)
        val zifferblaetter = dao.zifferblaetter()
        systemAppsEinfuegen(dao, force = false, syncLimit = 20)
        assertEquals(zifferblaetter, dao.zifferblaetter())
    }

    @Test
    fun systemappsOhneFirmwareFallenHeraus() = runTest {
        // Ein Locker von Boulder 0.53 auf b26: Workout aus frueher Zeit und TimeStyle Bedrock.
        val dao = alterLocker("Store A" to 50)
        dao.lege(app("Workout", AppType.Watchapp, -4, system = true, id = SystemAppIDsFuerTest.WORKOUT))
        dao.lege(app("TimeStyleBedrock", AppType.Watchface, 0, system = true, id = SystemAppIDsFuerTest.TIMESTYLE_053))
        systemAppsEinfuegen(dao, force = false, syncLimit = 20)
        assertEquals(false, "Workout" in dao.apps())
        assertEquals(false, "TimeStyleBedrock" in dao.zifferblaetter())
        assertEquals(true, "Kieselsport" in dao.apps() && "Kieselstrasse" in dao.apps())
    }
}

private object SystemAppIDsFuerTest {
    val WORKOUT = io.rebble.libpebblecommon.SystemAppIDs.WORKOUT_APP_UUID
    // TimeStyle Bedrock stand bis 0.53 als Systemapp im Locker (Firmware b22 bis b26).
    val TIMESTYLE_053 = Uuid.parse("d8af11d3-1e92-4f6d-b494-c5572f7bb7df")
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
    override fun getPluginEntriesFlow(): Flow<List<LockerEntry>> = fehlt()
    override fun getPluginEntriesFlow(searchQuery: String?, limit: Int): Flow<List<LockerEntry>> = fehlt()
    override fun getEntriesFlow(ids: List<Uuid>): Flow<List<LockerEntry>> = fehlt()
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
