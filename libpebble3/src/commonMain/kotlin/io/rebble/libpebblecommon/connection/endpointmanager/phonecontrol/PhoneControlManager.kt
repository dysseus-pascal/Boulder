package io.rebble.libpebblecommon.connection.endpointmanager.phonecontrol

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.calls.Call
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.di.ConnectionCoroutineScope
import io.rebble.libpebblecommon.packets.PhoneControl
import io.rebble.libpebblecommon.services.PhoneControlService
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlin.random.Random
import kotlin.random.nextUInt

class PhoneControlManager(
    private val watchScope: ConnectionCoroutineScope,
    private val libPebble: LibPebble,
    private val phoneControlService: PhoneControlService
) {
    companion object {
        private val logger = Logger.withTag(PhoneControlManager::class.simpleName!!)
    }
    private var lastCookie: UInt? = null
    fun init() {
        phoneControlService.callActions.onEach {
            val call = libPebble.currentCall.value
            when (it) {
                CallAction.Answer -> if (call is Call.AnswerableCall) {
                    call.answerCall()
                } else {
                    logger.e { "Watch requested answer call on unanswerable call type ${call?.let { it::class.simpleName }}" }
                }
                CallAction.Hangup -> if (call is Call.EndableCall) {
                    call.endCall()
                } else {
                    logger.e { "Watch requested hangup call on unendable call type ${call?.let { it::class.simpleName }}" }
                }
            }
        }.launchIn(watchScope)
        libPebble.currentCall.onEach {
            when (it) {
                is Call.RingingCall -> {
                    if (lastCookie != null) {
                        logger.w { "Ringing call received but last cookie is not null, this should not happen" }
                    }
                    val cookie = Random.nextUInt()
                    lastCookie = cookie
                    phoneControlService.send(PhoneControl.IncomingCall(
                        cookie = cookie,
                        callerNumber = it.contactNumber.take(31),
                        callerName = it.contactName?.take(31) ?: it.contactNumber.take(31)
                    ))
                }
                is Call.ActiveCall -> {
                    if (lastCookie == null) {
                        logger.w { "Active call received but last cookie is null, this should not happen" }
                    }
                    lastCookie?.let {
                        phoneControlService.send(PhoneControl.Start(cookie = it))
                    }
                }
                is Call.HoldingCall, is Call.DialingCall -> {/* no-op */}
                null -> {
                    lastCookie?.let {
                        phoneControlService.send(PhoneControl.End(cookie = it))
                        lastCookie = null
                    }
                }
            }
        }.launchIn(watchScope)
    }
}