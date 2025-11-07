package io.rebble.libpebblecommon.health

import io.rebble.libpebblecommon.connection.HealthApi
import io.rebble.libpebblecommon.database.entity.HealthGender
import io.rebble.libpebblecommon.database.entity.WatchSettingsDao
import io.rebble.libpebblecommon.database.entity.getWatchSettings
import io.rebble.libpebblecommon.database.entity.setWatchSettings
import io.rebble.libpebblecommon.di.LibPebbleCoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

class Health(
    private val watchSettingsDao: WatchSettingsDao,
    private val libPebbleCoroutineScope: LibPebbleCoroutineScope,
): HealthApi {
    override val healthSettings: Flow<HealthSettings> = watchSettingsDao.getWatchSettings()

    override fun updateHealthSettings(healthSettings: HealthSettings) {
        libPebbleCoroutineScope.launch {
            watchSettingsDao.setWatchSettings(healthSettings)
        }
    }
}

data class HealthSettings(
    val heightMm: Short = 165,
    val weightDag: Short = 6500,
    val trackingEnabled: Boolean = false,
    val activityInsightsEnabled: Boolean = false,
    val sleepInsightsEnabled: Boolean = false,
    val ageYears: Int = 35,
    val gender: HealthGender = HealthGender.Female,
)