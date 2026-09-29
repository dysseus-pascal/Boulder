package coredevices.pebble

import android.content.Context
import coredevices.pebble.kiesel.GesundheitWidget
import coredevices.pebble.kiesel.Kieselstrasse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import coredevices.util.Permission
import io.rebble.libpebblecommon.connection.KnownPebbleDevice
import io.rebble.libpebblecommon.connection.LibPebble
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class PebbleAndroidDelegate(
    private val libPebble: LibPebble,
    private val context: Context,
) {
    fun initPostPermissions() {
        libPebble.doStuffAfterPermissionsGranted()
        // Boulder: OsmAnds Abzweigungen an die Uhr-App Kieselstrasse.
        Kieselstrasse.begleite(context, libPebble)
        // Boulder: neue Daten von der Uhr zeichnen das Gesundheits-Widget neu.
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            libPebble.healthDataUpdated.collect { GesundheitWidget.stosseAn(context) }
        }
    }

    /**
     * We only expect the notification permission to be granted after first connecting
     * to a watch (via companion device manager).
     */
    val requiredPermissions: Flow<Set<Permission>> = libPebble.watches.map { watches ->
        if (watches.any { it is KnownPebbleDevice }) {
            BASE_PERMISSIONS + AFTER_FIRST_CONNECTION_PERMISSIONS
        } else {
            BASE_PERMISSIONS
        }
    }

    companion object {
        private val BASE_PERMISSIONS = setOf(
            Permission.Location,
            Permission.BackgroundLocation,
            Permission.Bluetooth,
            Permission.PostNotifications,
        )
        private val AFTER_FIRST_CONNECTION_PERMISSIONS = setOf(
            Permission.ReadNotifications,
            Permission.ReadCallLog,
            Permission.Calendar,
            Permission.Contacts,
            Permission.ReadPhoneState,
        )
    }
}