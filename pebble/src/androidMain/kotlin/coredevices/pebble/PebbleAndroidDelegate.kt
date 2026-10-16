package coredevices.pebble

import android.content.Context
import coredevices.pebble.kiesel.GesundheitWidget
import coredevices.pebble.kiesel.Gesundheitsziele
import coredevices.pebble.kiesel.Kieselstrasse
import coredevices.pebble.kiesel.Rechtefrage
import coredevices.pebble.kiesel.Spiegel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import coredevices.util.Permission
import io.rebble.libpebblecommon.connection.HealthDataApi
import io.rebble.libpebblecommon.connection.KnownPebbleDevice
import io.rebble.libpebblecommon.connection.LibPebble
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class PebbleAndroidDelegate(
    private val libPebble: LibPebble,
    private val context: Context,
    private val healthDataApi: HealthDataApi,
) {
    /**
     * Boulder: aus MainApplication.onCreate, nach libPebble.init(). Beendet Android den Prozess,
     * startet es ihn ohne Oberflaeche neu (PebbleService ist START_STICKY), und die Uhr verbindet
     * sich wieder; initPostPermissions kaeme dann erst mit dem naechsten Oeffnen der App.
     */
    fun beimProzessstart() {
        // OsmAnds Abzweigungen an die Uhr-App Kieselstrasse.
        Kieselstrasse.begleite(context, libPebble)
        // Der eigene Server fuer Claude: Takt planen und nach jedem Schub der Uhr senden - auch ohne Oberflaeche.
        Spiegel.plane(context)
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            libPebble.healthDataUpdated.collect { Spiegel.stosseAn(context) }
        }
    }

    // Boulder: die Sammler unten leben so lange wie der Prozess. Jedes Oeffnen
    // der App ruft hierher - ohne die Sperre kam jedes Mal ein weiterer dazu.
    private var boulderGestartet = false

    fun initPostPermissions() {
        libPebble.doStuffAfterPermissionsGranted()
        if (boulderGestartet) return
        boulderGestartet = true
        // Boulder: neue Daten von der Uhr zeichnen das Gesundheits-Widget neu.
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            libPebble.healthDataUpdated.collect { GesundheitWidget.stosseAn(context) }
        }
        // Boulder: das Widget hat keinen Takt von Android mehr - einmal je Prozess zeichnen und neu planen,
        // sonst stuende es nach "Beenden erzwingen" ohne Uhr still, gleich welcher Reiter offen ist.
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch { GesundheitWidget.sichereTakt(context) }
        // Boulder: alle Rechte der Akte in einem Dialog, einmal je Fassung.
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch { Rechtefrage.beimStart(context) }
        // Boulder: Schritte und Naechte taeglich festschreiben, bevor das Housekeeping sie nach 90 Tagen loescht.
        coredevices.pebble.kiesel.Tagesarchiv.plane(context)
        // Boulder: die Ziele aus "Goals" wieder in der Datenbank Health, falls sie neu ist.
        Gesundheitsziele.beimStart(context, healthDataApi)
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