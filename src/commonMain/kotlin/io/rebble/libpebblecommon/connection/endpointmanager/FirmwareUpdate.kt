package io.rebble.libpebblecommon.connection.endpointmanager

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.ConnectedPebble
import io.rebble.libpebblecommon.connection.endpointmanager.putbytes.PutBytesSession
import io.rebble.libpebblecommon.disk.pbz.PbzFirmware
import io.rebble.libpebblecommon.packets.ObjectType
import io.rebble.libpebblecommon.packets.SystemMessage
import io.rebble.libpebblecommon.services.SystemService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.withTimeout
import kotlinx.io.buffered
import kotlinx.io.files.Path

sealed class FirmwareUpdateException(message: String, cause: Throwable? = null) :
    Exception(message, cause) {
    class SafetyCheckFailed(message: String) : FirmwareUpdateException(message)
    class TransferFailed(message: String, cause: Throwable?, val bytesTransferred: UInt) :
        FirmwareUpdateException(message, cause)
}

class FirmwareUpdate(
    watchName: String,
    private val watchDisconnected: Deferred<Unit>,
    private val watchBoard: String,
    private val systemService: SystemService,
    private val putBytesSession: PutBytesSession,
) : ConnectedPebble.Firmware {
    private val logger = Logger.withTag("FWUpdate-${watchName}")
    private val scope = CoroutineScope(Dispatchers.Default)

    sealed class FirmwareUpdateStatus {
        data object WaitingToStart : FirmwareUpdateStatus()
        data class InProgress(val progress: Float) : FirmwareUpdateStatus()
        data object WaitingForReboot : FirmwareUpdateStatus()
    }

    private fun performSafetyChecks(pbzFw: PbzFirmware) {
        val manifest = pbzFw.manifest
        when {
            manifest.firmware.type != "normal" && manifest.firmware.type != "recovery" ->
                throw FirmwareUpdateException.SafetyCheckFailed("Invalid firmware type: ${manifest.firmware.type}")

            manifest.firmware.crc <= 0L ->
                throw FirmwareUpdateException.SafetyCheckFailed("Invalid firmware CRC: ${manifest.firmware.crc}")

            manifest.firmware.size <= 0 ->
                throw FirmwareUpdateException.SafetyCheckFailed("Invalid firmware size: ${manifest.firmware.size}")

            manifest.resources != null && manifest.resources.size <= 0 ->
                throw FirmwareUpdateException.SafetyCheckFailed("Invalid resources size: ${manifest.resources.size}")

            manifest.resources != null && manifest.resources.crc <= 0L ->
                throw FirmwareUpdateException.SafetyCheckFailed("Invalid resources CRC: ${manifest.resources.crc}")

            !watchBoard.startsWith(pbzFw.manifest.firmware.hwRev.revision) ->
                throw FirmwareUpdateException.SafetyCheckFailed("Firmware board does not match watch board: ${pbzFw.manifest.firmware.hwRev.revision} != $watchBoard")
        }
    }

    private suspend fun FlowCollector<FirmwareUpdateStatus>.sendFirmwareParts(
        pbzFw: PbzFirmware,
        offset: UInt
    ) {
        var totalSent = 0u
        check(
            offset < (pbzFw.manifest.firmware.size + (pbzFw.manifest.resources?.size ?: 0)).toUInt()
        ) {
            "Resume offset greater than total transfer size"
        }
        if (offset < pbzFw.manifest.firmware.size.toUInt()) {
            try {
                sendFirmware(pbzFw, offset).collect {
                    when (it) {
                        is PutBytesSession.SessionState.Open -> {
                            logger.d { "PutBytes session opened for firmware" }
                            emit(FirmwareUpdateStatus.InProgress(0f))
                        }

                        is PutBytesSession.SessionState.Sending -> {
                            totalSent = it.totalSent
                            val progress =
                                (it.totalSent.toFloat() / pbzFw.manifest.firmware.size) / 2.0f
                            logger.i { "Firmware update progress: $progress (putbytes cookie: ${it.cookie})" }
                            emit(FirmwareUpdateStatus.InProgress(progress))
                        }
                    }
                }
            } catch (e: Exception) {
                throw FirmwareUpdateException.TransferFailed(
                    "Failed to transfer firmware",
                    e,
                    totalSent
                )
            }
            logger.d { "Completed firmware transfer" }
        } else {
            logger.d { "Firmware already sent, skipping firmware PutBytes" }
        }
        pbzFw.manifest.resources?.let { res ->
            val resourcesOffset = if (offset < pbzFw.manifest.firmware.size.toUInt()) {
                0u
            } else {
                offset - pbzFw.manifest.firmware.size.toUInt()
            }
            try {
                sendResources(pbzFw, resourcesOffset).collect {
                    when (it) {
                        is PutBytesSession.SessionState.Open -> {
                            logger.d { "PutBytes session opened for resources" }
                            emit(FirmwareUpdateStatus.InProgress(0.5f))
                        }

                        is PutBytesSession.SessionState.Sending -> {
                            totalSent = pbzFw.manifest.firmware.size.toUInt() + it.totalSent
                            val progress =
                                0.5f + ((it.totalSent.toFloat() / res.size.toFloat()) / 2.0f)
                            logger.i { "Resources update progress: $progress (putbytes cookie: ${it.cookie})" }
                            emit(FirmwareUpdateStatus.InProgress(progress))
                        }
                    }
                }
            } catch (e: Exception) {
                throw FirmwareUpdateException.TransferFailed(
                    "Failed to transfer resources",
                    e,
                    totalSent
                )
            }
            logger.d { "Completed resources transfer" }
        } ?: logger.d { "No resources to send, resource PutBytes skipped" }
    }

    override fun sideloadFirmware(path: Path): Flow<FirmwareUpdate.FirmwareUpdateStatus> {
        return beginFirmwareUpdate(PbzFirmware(path), 0u)
    }

    private fun beginFirmwareUpdate(pbzFw: PbzFirmware, offset: UInt) = flow {
        val totalBytes = pbzFw.manifest.firmware.size + (pbzFw.manifest.resources?.size ?: 0)
        require(totalBytes > 0) { "Firmware size is 0" }
        performSafetyChecks(pbzFw)
        emit(FirmwareUpdateStatus.WaitingToStart)
        val result = systemService.sendFirmwareUpdateStart(offset, totalBytes.toUInt())
        if (result != SystemMessage.FirmwareUpdateStartStatus.Started) {
            error("Failed to start firmware update: $result")
        }
        sendFirmwareParts(pbzFw, offset)
        logger.d { "Firmware update completed, waiting for reboot" }
        emit(FirmwareUpdateStatus.WaitingForReboot)
        systemService.sendFirmwareUpdateComplete()
        withTimeout(60_000) {
            // TODO this needs to be managed outside of the connection scope (this code might never
            //  be called because we cancel the scope when we disconnect).
            watchDisconnected.await()
        }
    }

    private fun sendFirmware(
        pbzFw: PbzFirmware,
        skip: UInt = 0u,
    ): Flow<PutBytesSession.SessionState> {
        val firmware = pbzFw.manifest.firmware
        val source = pbzFw.getFile(firmware.name).buffered()
        if (skip > 0u) {
            source.skip(skip.toLong())
        }
        return putBytesSession.beginSession(
            size = firmware.size.toUInt(),
            type = when (firmware.type) {
                "normal" -> ObjectType.FIRMWARE
                "recovery" -> ObjectType.RECOVERY
                else -> error("Unknown firmware type: ${firmware.type}")
            },
            bank = 0u,
            filename = "",
            source = source
        ).onCompletion { source.close() } // Can't do use block because of the flow
    }

    private fun sendResources(
        pbzFw: PbzFirmware,
        skip: UInt = 0u,
    ): Flow<PutBytesSession.SessionState> {
        val resources = pbzFw.manifest.resources
            ?: throw IllegalArgumentException("Resources not found in firmware manifest")
        require(resources.size > 0) { "Resources size is 0" }
        val source = pbzFw.getFile(resources.name).buffered()
        if (skip > 0u) {
            source.skip(skip.toLong())
        }
        return putBytesSession.beginSession(
            size = resources.size.toUInt(),
            type = ObjectType.SYSTEM_RESOURCE,
            bank = 0u,
            filename = "",
            source = source
        ).onCompletion { source.close() }
    }
}