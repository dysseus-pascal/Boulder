package io.rebble.libpebblecommon.health

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.WatchConfigFlow
import io.rebble.libpebblecommon.database.entity.WatchSettings
import io.rebble.libpebblecommon.database.entity.WatchSettingsDao
import io.rebble.libpebblecommon.di.LibPebbleCoroutineScope
import kotlinx.coroutines.launch

class Health(
    private val watchSettingsDao: WatchSettingsDao,
    private val watchSettings: WatchConfigFlow,
    private val libPebbleCoroutineScope: LibPebbleCoroutineScope,
) {
    fun init() {
        libPebbleCoroutineScope.launch {
            var healthEnabled = false
            watchSettings.flow.collect {
                val newHealthEnabled = it.watchConfig.enableHealth
                if (newHealthEnabled != healthEnabled) {
                    healthEnabled = newHealthEnabled
                    Logger.d { "Health enabled: $healthEnabled" }
                    watchSettingsDao.insertOrReplace(WatchSettings.create(healthEnabled = newHealthEnabled))
                }
            }
        }
    }
}