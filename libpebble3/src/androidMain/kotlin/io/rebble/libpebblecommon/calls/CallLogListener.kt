package io.rebble.libpebblecommon.io.rebble.libpebblecommon.calls

import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.calls.BlockedReason
import io.rebble.libpebblecommon.calls.MissedCall
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

class CallLogListener(private val context: Context): AutoCloseable {
    companion object {
        private val logger = Logger.withTag(CallLogListener::class.simpleName!!)
    }
    private val handler = Handler(Looper.getMainLooper())
    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            if (selfChange) return
            if (uri != CallLog.Calls.CONTENT_URI) return

            updateMissedCalls()
        }
    }

    private fun getLastSyncTime(): Instant {
        val prefs = context.getSharedPreferences("call_log_prefs", Context.MODE_PRIVATE)
        val lastSyncTime = Instant.fromEpochMilliseconds(prefs.getLong("last_sync_time", 0L))
        return lastSyncTime
    }

    private fun setLastSyncTime(time: Instant) {
        val prefs = context.getSharedPreferences("call_log_prefs", Context.MODE_PRIVATE)
        prefs.edit().putLong("last_sync_time", time.toEpochMilliseconds()).apply()
    }

    private fun updateMissedCalls() {
        val lastSyncTime = getLastSyncTime()
        val missedCalls = mutableListOf<MissedCall>()
        setLastSyncTime(Clock.System.now())
        context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            buildList {
                add(CallLog.Calls.NUMBER)
                add(CallLog.Calls.CACHED_NAME)
                add(CallLog.Calls.DATE)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    add(CallLog.Calls.BLOCK_REASON)
                }
            }.toTypedArray(),
            "${CallLog.Calls.TYPE} = ? AND ${CallLog.Calls.DATE} > ?",
            arrayOf(CallLog.Calls.MISSED_TYPE.toString(), lastSyncTime.toEpochMilliseconds().toString()),
            "${CallLog.Calls.DATE} DESC"
        )?.use { cursor ->
            val numberIdx = cursor.getColumnIndex(CallLog.Calls.NUMBER)
            if (numberIdx == -1) {
                logger.e { "Call log cursor does not contain number column." }
                return
            }
            val nameIdx = cursor.getColumnIndex(CallLog.Calls.CACHED_NAME)
            if (nameIdx == -1) {
                logger.e { "Call log cursor does not contain cached name column." }
            }
            val dateIdx = cursor.getColumnIndex(CallLog.Calls.DATE)
            if (dateIdx == -1) {
                logger.e { "Call log cursor does not contain date column." }
                return
            }
            val blockReasonIdx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                cursor.getColumnIndex(CallLog.Calls.BLOCK_REASON)
            } else {
                null
            }
            if (blockReasonIdx == -1) {
                logger.e { "Call log cursor does not contain block reason column." }
            }

            while (cursor.moveToNext()) {
                val callerNumber = cursor.getString(numberIdx) ?: "Unknown"
                val callerName = cursor.getString(nameIdx) ?: null
                val timestamp = Instant.fromEpochMilliseconds(cursor.getLong(dateIdx))
                val blockReason = if (blockReasonIdx != null) {
                    when (cursor.getInt(blockReasonIdx)) {
                        CallLog.Calls.BLOCK_REASON_NOT_BLOCKED -> BlockedReason.NotBlocked
                        CallLog.Calls.BLOCK_REASON_BLOCKED_NUMBER -> BlockedReason.BlockedNumber
                        CallLog.Calls.BLOCK_REASON_CALL_SCREENING_SERVICE -> BlockedReason.CallScreening
                        CallLog.Calls.BLOCK_REASON_DIRECT_TO_VOICEMAIL -> BlockedReason.DirectToVoicemail
                        else -> BlockedReason.Other
                    }
                } else {
                    BlockedReason.NotBlocked
                }

                missedCalls.add(MissedCall(callerNumber, callerName, blockReason, timestamp))
            }
        }
        if (missedCalls.isNotEmpty()) {
            logger.d { "Detected ${missedCalls.size} missed calls since last sync." }
            //TODO: push to watches
        } else {
            logger.d { "No new missed calls detected since last sync." }
        }
    }

    fun init() {
        if (context.checkSelfPermission(android.Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
            logger.w { "READ_CALL_LOG permission not granted, cannot listen to call log changes." }
            return
        }
        context.contentResolver.registerContentObserver(
            CallLog.Calls.CONTENT_URI,
            true,
            observer
        )
    }

    override fun close() {
        try {
            context.contentResolver.unregisterContentObserver(observer)
        } catch (e: IllegalArgumentException) {
            logger.w { "Failed to unregister content observer, it may not have been registered." }
        }
    }
}