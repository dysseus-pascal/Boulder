package coredevices.ring.reminders

import coredevices.indexai.data.entity.ItemDocument.ItemMetadata
import coredevices.ring.database.room.dao.CachedItemDao
import coredevices.ring.database.room.dao.LocalReminderDao
import coredevices.ring.ui.navigation.RingRoutes

/**
 * Resolves the deep link a fired reminder's notification should open.
 */
class ReminderDeepLinkResolver(
    private val localReminderDao: LocalReminderDao,
    private val cachedItemDao: CachedItemDao,
) {
    /**
     * Finds the feed item created from this reminder and returns a deep link that opens
     * it. Falls back to opening the Index tab when the item can't be located (e.g.
     * reminders created before this linkage existed, outside a recording, or not yet synced).
     */
    suspend fun resolveDeepLink(reminderId: Int): String {
        val recordingId = runCatching { localReminderDao.getReminder(reminderId) }
            .getOrNull()?.recordingId ?: return FALLBACK_DEEP_LINK
        val firestoreId = runCatching {
            cachedItemDao.getByRecording(recordingId).firstOrNull { item ->
                (item.metadata as? ItemMetadata.Reminder)?.localReminderId == reminderId
            }?.firestoreId
        }.getOrNull()
        return firestoreId?.let { RingRoutes.objectDeepLink(it) } ?: FALLBACK_DEEP_LINK
    }

    companion object {
        const val FALLBACK_DEEP_LINK = "pebble://navbar/index"

        /** Key under which the iOS reminder notification carries its local reminder id,
         *  so the tap handler can resolve the deep link then (the feed item isn't linked
         *  yet when the notification is scheduled). */
        const val USERINFO_REMINDER_ID = "ring-reminder-id"
    }
}
