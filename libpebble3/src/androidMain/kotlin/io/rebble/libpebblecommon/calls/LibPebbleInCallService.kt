package io.rebble.libpebblecommon.calls

import android.os.Build
import android.provider.ContactsContract
import android.provider.ContactsContract.Contacts
import android.telecom.Call
import android.telecom.InCallService
import android.telecom.VideoProfile
import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.notification.LibPebbleNotificationListener
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class LibPebbleInCallService: InCallService(), KoinComponent {
    companion object {
        private val logger = Logger.withTag(LibPebbleNotificationListener::class.simpleName!!)
    }

    private val libPebble: LibPebble by inject()

    override fun onCreate() {
        super.onCreate()
        libPebble.currentCall.value = null
    }

    override fun onDestroy() {
        logger.d { "onDestroy()" }
        libPebble.currentCall.value = null
        super.onDestroy()
    }

    private val callCallback = object : Call.Callback() {
        override fun onStateChanged(call: Call?, state: Int) {
            call ?: return
            logger.d { "Call state changed: ${call.state}" }
            libPebble.currentCall.value = createLibPebbleCall(call)
        }
    }

    override fun onCallAdded(call: Call?) {
        call ?: return
        if (calls.size > 1) {
            logger.w { "Multiple calls detected" }
        }
        calls.filter { it != call }.forEach {
            call.unregisterCallback(callCallback)
        }
        logger.d { "New call in state: ${call.state}" }
        call.registerCallback(callCallback)
        libPebble.currentCall.value = createLibPebbleCall(call)
    }

    override fun onCallRemoved(call: Call?) {
        call ?: return
        libPebble.currentCall.value = null
    }

    private fun Call.resolveContactName(): String? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            details.contactDisplayName
        } else {
            val cursor = contentResolver.query(
                Contacts.CONTENT_URI,
                arrayOf(Contacts.DISPLAY_NAME),
                Contacts.HAS_PHONE_NUMBER + " = 1 AND " + ContactsContract.CommonDataKinds.Phone.NUMBER + " = ?",
                arrayOf(details.handle.schemeSpecificPart),
                null
            )
            val name = cursor?.use {
                if (it.moveToFirst()) {
                    it.getString(it.getColumnIndexOrThrow(Contacts.DISPLAY_NAME))
                } else {
                    null
                }
            }
            return name
        }
    }

    private fun Call.resolveContactNumber(): String {
        return this.details.handle?.schemeSpecificPart ?: "Unknown"
    }

    private fun createLibPebbleCall(call: Call): io.rebble.libpebblecommon.calls.Call? =
        when (call.state) {
            Call.STATE_RINGING -> io.rebble.libpebblecommon.calls.Call.RingingCall(
                contactName = call.resolveContactName(),
                contactNumber = call.resolveContactNumber(),
                onCallEnd = { call.disconnect() },
                onCallAnswer = { call.answer(VideoProfile.STATE_AUDIO_ONLY) }
            )
            Call.STATE_DIALING, Call.STATE_CONNECTING -> io.rebble.libpebblecommon.calls.Call.DialingCall(
                contactName = call.resolveContactName(),
                contactNumber = call.resolveContactNumber(),
                onCallEnd = { call.disconnect() }
            )
            Call.STATE_ACTIVE, Call.STATE_DISCONNECTING -> io.rebble.libpebblecommon.calls.Call.ActiveCall(
                contactName = call.resolveContactName(),
                contactNumber = call.resolveContactNumber(),
                onCallEnd = { call.disconnect() }
            )
            Call.STATE_HOLDING -> io.rebble.libpebblecommon.calls.Call.HoldingCall(
                contactName = call.resolveContactName(),
                contactNumber = call.resolveContactNumber(),
                onCallEnd = { call.disconnect() }
            )
            else -> null
        }
}