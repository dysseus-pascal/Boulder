package coredevices.pebble.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.database.dao.WatchPreference
import io.rebble.libpebblecommon.database.entity.BoolWatchPref
import io.rebble.libpebblecommon.database.entity.HRMonitoringInterval
import io.rebble.libpebblecommon.database.entity.Spo2Einstellungen
import io.rebble.libpebblecommon.health.HealthSettings
import kotlinx.coroutines.flow.map

/**
 * Boulder: was die SpO2-Zeilen brauchen. Cores Einstellungsliste fuehrt es als Schluessel ihres
 * remember, damit sie die Zeilen mit neuem Stand neu baut.
 */
data class BoulderSpo2Stand(
    val einstellungen: Spo2Einstellungen?,
    val schlaf: WatchPreference<Boolean>?,
)

@Composable
fun rememberBoulderSpo2(libPebble: LibPebble): BoulderSpo2Stand {
    val einstellungen by remember(libPebble) { libPebble.spo2Einstellungen }.collectAsState(null)
    val schlaf by remember(libPebble) {
        libPebble.watchPrefs.map { prefs -> prefs.firstOrNull { it.pref == BoolWatchPref.BoulderSpo2Schlaf } }
    }.collectAsState(null)
    @Suppress("UNCHECKED_CAST")
    return BoulderSpo2Stand(einstellungen, schlaf as WatchPreference<Boolean>?)
}

/** Schaltet die App HR During Activities aus, geht SpO2 im Sport mit aus - wie auf der Uhr. */
fun BoulderSpo2Stand.nachHrImSport(libPebble: LibPebble, an: Boolean) {
    val e = einstellungen ?: return
    if (!an && e.imSport) libPebble.setzeSpo2Einstellungen(e.copy(imSport = false))
}

/** SpO2 im Schlaf steht bei den SpO2-Zeilen, nicht in der Liste der Uhr-Einstellungen. */
internal fun List<WatchPreference<*>>.ohneEigenenPlatz(): List<WatchPreference<*>> =
    filterNot { it.pref == BoolWatchPref.BoulderSpo2Schlaf }

/** Die SpO2-Zeilen unter Cores "HR During Activities", mit den Titeln der Uhr. */
fun boulderSpo2Zeilen(libPebble: LibPebble, health: HealthSettings, stand: BoulderSpo2Stand): List<SettingsItem> {
    val e = stand.einstellungen ?: return emptyList()
    val pulsAn = { health.trackingEnabled && health.hrmEnabled }
    return listOfNotNull(
        basicSettingsToggleItem(
            id = "BloodOxygenEnabled",
            title = "SpO2",
            description = "Allow the watch to measure blood oxygen (SpO2). The sampling rate is configurable below.",
            topLevelType = TopLevelType.Phone,
            section = Section.Health,
            checked = e.an,
            show = pulsAn,
            onCheckChanged = { libPebble.setzeSpo2Einstellungen(e.copy(an = it)) },
        ),
        basicSettingsDropdownItem(
            id = "Spo2MeasurementInterval",
            title = "SpO2 Monitoring",
            description = "How often the watch takes a background blood oxygen (SpO2) reading. Set to Off to pause background sampling. A faster rate can have an impact on battery life.",
            topLevelType = TopLevelType.Phone,
            section = Section.Health,
            selectedItem = e.intervall,
            // Fuenf Minuten gibt es nur fuer den Puls; die Uhr bietet fuer SpO2 10, 30 und 60.
            items = HRMonitoringInterval.entries - HRMonitoringInterval.FiveMin,
            onItemSelected = { libPebble.setzeSpo2Einstellungen(e.copy(intervall = it)) },
            itemText = {
                when (it) {
                    HRMonitoringInterval.FiveMin -> "Every 5 minutes"
                    HRMonitoringInterval.TenMin -> "Every 10 minutes"
                    HRMonitoringInterval.ThirtyMin -> "Every 30 minutes"
                    HRMonitoringInterval.OneHour -> "Every hour"
                    HRMonitoringInterval.Disabled -> "Off"
                }
            },
            show = { pulsAn() && e.an },
        ),
        stand.schlaf?.let { spo2SchlafPref(it, libPebble, pulsAn) },
        basicSettingsToggleItem(
            id = "BloodOxygenActivityEnabled",
            title = "SpO2 During Activity",
            description = "Take a blood oxygen (SpO2) reading roughly every 5 minutes during detected activities. Requires 'HR During Activities' to be enabled.",
            topLevelType = TopLevelType.Phone,
            section = Section.Health,
            checked = e.imSport,
            enabled = health.hrmActivityTrackingEnabled,
            show = pulsAn,
            onCheckChanged = { libPebble.setzeSpo2Einstellungen(e.copy(imSport = it)) },
        ),
    )
}

internal fun spo2SchlafPref(item: WatchPreference<Boolean>, libPebble: LibPebble, show: () -> Boolean): SettingsItem =
    basicSettingsToggleItem(
        id = item.pref.id,
        title = item.pref.displayName,
        description = item.pref.description,
        topLevelType = item.pref.topLevelType(),
        section = item.pref.section(),
        checked = item.valueOrDefault(),
        show = show,
        onCheckChanged = { enabled -> libPebble.setWatchPref(item.copy(value = enabled)) },
    )
