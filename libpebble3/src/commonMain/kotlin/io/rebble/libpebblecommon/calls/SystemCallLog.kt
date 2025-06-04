package io.rebble.libpebblecommon.calls

import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.Instant

interface SystemCallLog {
    suspend fun getMissedCalls(start: Instant): List<MissedCall>
    fun registerForMissedCallChanges(): Flow<Unit>
}