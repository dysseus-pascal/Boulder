package io.rebble.libpebblecommon.calls

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.endpointmanager.blobdb.TimeProvider
import io.rebble.libpebblecommon.database.dao.TimelinePinRealDao
import io.rebble.libpebblecommon.di.LibPebbleCoroutineScope
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlin.time.Duration.Companion.seconds

class MissedCallSyncer(
    private val timelinePinDao: TimelinePinRealDao,
    private val timeProvider: TimeProvider,
    private val libPebbleCoroutineScope: LibPebbleCoroutineScope,
    private val systemCallLog: SystemCallLog
) {
    companion object {
        private val logger = Logger.withTag("MissedCallSyncer")
    }

    fun init() {
        try {
            systemCallLog.registerForMissedCallChanges().debounce(3.seconds).onEach {
                logger.d { "Missed call change detected, syncing missed calls..." }
                //TODO: see if we need to persist last sync time + push all calls in time window to avoid missing any
                val startTime = timeProvider.now() - 10.seconds
                val missedCalls = systemCallLog.getMissedCalls(start = startTime)
                logger.d { "Got ${missedCalls.size} missed calls since last sync" }
                addToTimeline(missedCalls.take(1))
            }.launchIn(libPebbleCoroutineScope)
        } catch (e: Exception) {
            logger.e(e) { "Failed to initialize missed call syncer" }
        }
    }

    private suspend fun addToTimeline(missedCalls: List<MissedCall>) {
        val pins = missedCalls.map { it.toTimelinePin() }
        timelinePinDao.insertOrReplace(pins)
    }
}