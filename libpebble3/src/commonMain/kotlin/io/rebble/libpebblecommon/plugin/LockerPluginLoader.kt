package io.rebble.libpebblecommon.plugin

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.WatchConfigFlow
import io.rebble.libpebblecommon.database.dao.LockerEntryRealDao
import io.rebble.libpebblecommon.database.entity.LockerEntry
import io.rebble.libpebblecommon.di.LibPebbleCoroutineScope
import io.rebble.libpebblecommon.locker.AppFileReader
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlin.uuid.Uuid

/**
 * Projects the locker into the [PluginRegistry]: an installed pbw whose LockerEntry carries a plugin
 * manifest is registered, and unregistered when the app leaves the locker or plugins are switched
 * off.
 */
class LockerPluginLoader(
    private val registry: PluginRegistry,
    private val watchConfig: WatchConfigFlow,
    private val scope: LibPebbleCoroutineScope,
    private val lockerEntryDao: LockerEntryRealDao,
    private val appFileReader: AppFileReader,
    private val factory: JsPluginFactory,
) {
    private val logger = Logger.withTag("LockerPluginLoader")
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** uuid -> the version registered, so a re-sideload with a changed manifest re-registers. */
    private val registered = mutableMapOf<Uuid, String>()

    fun init() {
        scope.launch {
            combine(
                watchConfig.flow.map { it.watchConfig.enablePlugins }.distinctUntilChanged(),
                lockerEntryDao.getPluginEntriesFlow(),
            ) { enabled, rows -> if (enabled) rows else emptyList() }
                .collect { rows -> reconcile(rows) }
        }
    }

    private fun reconcile(rows: List<LockerEntry>) {
        val wanted = rows.mapNotNull { row ->
            val manifestJson = row.pluginManifest ?: return@mapNotNull null
            row.id to row
        }.toMap()

        (registered.keys - wanted.keys).toList().forEach { uuid ->
            registry.unregisterPlugin(uuid)
            registered.remove(uuid)
            logger.i { "unregistered plugin $uuid" }
        }

        wanted.forEach { (uuid, row) ->
            if (registered[uuid] == row.version) return@forEach
            val manifest = try {
                json.decodeFromString(PluginManifest.serializer(), row.pluginManifest!!)
            } catch (e: Exception) {
                logger.e(e) { "$uuid: bad stored plugin manifest" }
                return@forEach
            }
            registry.registerPlugin(
                factory.create(
                    pluginUuid = uuid,
                    name = row.title,
                    manifest = manifest,
                    script = { appFileReader.getAppFile(uuid, manifest.script).orEmpty() },
                )
            )
            registered[uuid] = row.version
            logger.i { "registered plugin ${row.title} ($uuid)" }
        }
    }
}
