package coredevices.ring

import co.touchlab.kermit.Logger
import com.russhwolf.settings.Settings
import coredevices.ring.database.firestore.dao.FirestoreRecordingsDao
import coredevices.ring.service.RingBackgroundManager
import coredevices.util.CoreConfigHolder
import coredevices.util.Permission

actual class RingDelegate(
    private val ringBackgroundManager: RingBackgroundManager,
    private val coreConfigHolder: CoreConfigHolder,
    private val recordingsDao: FirestoreRecordingsDao,
    private val settings: Settings
) {
    companion object {
        private val logger = Logger.withTag("RingDelegate")
    }
    /**
     * Called by activity onCreate / didFinishLaunching to initialize the Ring module.
     */
    actual suspend fun init() {
        listenForUserPresent(recordingsDao, coreConfigHolder, settings)
        ringBackgroundManager.startBackgroundIfEnabled()
    }

    actual fun requiredRuntimePermissions(): Set<Permission> {
        return setOf(Permission.Reminders)
    }
}