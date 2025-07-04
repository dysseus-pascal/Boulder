package io.rebble.libpebblecommon.connection.endpointmanager

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.ConnectedPebble
import io.rebble.libpebblecommon.connection.Transport
import io.rebble.libpebblecommon.connection.endpointmanager.putbytes.PutBytesSession
import io.rebble.libpebblecommon.di.ConnectionCoroutineScope
import io.rebble.libpebblecommon.disk.pbz.PbzFirmware
import io.rebble.libpebblecommon.metadata.WatchHardwarePlatform
import io.rebble.libpebblecommon.packets.ObjectType
import io.rebble.libpebblecommon.packets.SystemMessage
import io.rebble.libpebblecommon.services.SystemService
import io.rebble.libpebblecommon.web.FirmwareDownloader
import io.rebble.libpebblecommon.web.FirmwareUpdateManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.buffered
import kotlinx.io.files.Path

sealed class FirmwareUpdateException(message: String, cause: Throwable? = null) :
    Exception(message, cause) {
    class SafetyCheckFailed(message: String) : FirmwareUpdateException(message)
    class TransferFailed(message: String, cause: Throwable?, val bytesTransferred: UInt) :
        FirmwareUpdateException(message, cause)
}

class FirmwareUpdate(
    transport: Transport,
    private val systemService: SystemService,
    private val putBytesSession: PutBytesSession,
    private val firmwareDownloader: FirmwareDownloader,
    private val connectionCoroutineScope: ConnectionCoroutineScope,
    private val firmwareUpdateManager: FirmwareUpdateManager,
) : ConnectedPebble.Firmware {
    private val logger = Logger.withTag("FWUpdate-${transport.name}")
    private lateinit var watchPlatform: WatchHardwarePlatform
    private val _firmwareUpdateState =
        MutableStateFlow<FirmwareUpdateStatus>(FirmwareUpdateStatus.NotInProgress.Idle)
    override val firmwareUpdateState: StateFlow<FirmwareUpdateStatus> =
        _firmwareUpdateState.asStateFlow()

    sealed class FirmwareUpdateStatus {
        sealed class NotInProgress : FirmwareUpdateStatus() {
            data object Idle : NotInProgress()
            data object ErrorStarting : NotInProgress()
        }

        data object WaitingToStart : FirmwareUpdateStatus()
        data class InProgress(val progress: Float) : FirmwareUpdateStatus()

        /**
         * Won't be in this state for long (we'll be disconnected very soon, at which point no-one
         * is looking at this state).
         */
        data object WaitingForReboot : FirmwareUpdateStatus()
    }

    fun setPlatform(watchPlatform: WatchHardwarePlatform) {
        this.watchPlatform = watchPlatform
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

            watchPlatform != pbzFw.manifest.firmware.hwRev ->
                throw FirmwareUpdateException.SafetyCheckFailed("Firmware board does not match watch board: ${pbzFw.manifest.firmware.hwRev} != $watchPlatform")
        }
    }

    private suspend fun sendFirmwareParts(
        pbzFw: PbzFirmware,
        offset: UInt
    ) {
        var totalSent = 0u
        check(
            offset < (pbzFw.manifest.firmware.size + (pbzFw.manifest.resources?.size ?: 0)).toUInt()
        ) {
            "Resume offset greater than total transfer size"
        }
        var firmwareCookie: UInt? = null
        if (offset < pbzFw.manifest.firmware.size.toUInt()) {
            try {
                sendFirmware(pbzFw, offset).collect {
                    when (it) {
                        is PutBytesSession.SessionState.Open -> {
                            logger.d { "PutBytes session opened for firmware" }
                            _firmwareUpdateState.value = FirmwareUpdateStatus.InProgress(0f)
                        }

                        is PutBytesSession.SessionState.Sending -> {
                            totalSent = it.totalSent
                            val progress =
                                (it.totalSent.toFloat() / pbzFw.manifest.firmware.size) / 2.0f
                            logger.i { "Firmware update progress: $progress (putbytes cookie: ${it.cookie})" }
                            _firmwareUpdateState.value = FirmwareUpdateStatus.InProgress(progress)
                        }

                        is PutBytesSession.SessionState.Finished -> {
                            firmwareCookie = it.cookie
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) {
                    logger.d { "Firmware transfer cancelled" }
                    throw e
                } else {
                    throw FirmwareUpdateException.TransferFailed(
                        "Failed to transfer firmware",
                        e,
                        totalSent
                    )
                }
            }
            logger.d { "Completed firmware transfer" }
        } else {
            logger.d { "Firmware already sent, skipping firmware PutBytes" }
        }
        var resourcesCookie: UInt? = null
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
                            _firmwareUpdateState.value = FirmwareUpdateStatus.InProgress(0.5f)
                        }

                        is PutBytesSession.SessionState.Sending -> {
                            totalSent = pbzFw.manifest.firmware.size.toUInt() + it.totalSent
                            val progress =
                                0.5f + ((it.totalSent.toFloat() / res.size.toFloat()) / 2.0f)
                            logger.i { "Resources update progress: $progress (putbytes cookie: ${it.cookie})" }
                            _firmwareUpdateState.value = FirmwareUpdateStatus.InProgress(progress)
                        }

                        is PutBytesSession.SessionState.Finished -> {
                            resourcesCookie = it.cookie
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

        // Install both right at the end after all transfers are complete (i.e. don't install
        // one without both having been successfully transferred).
        firmwareCookie?.let { putBytesSession.sendInstall(it) }
        resourcesCookie?.let { putBytesSession.sendInstall(it) }
    }

    override fun updateFirmware(path: Path) {
        logger.d { "updateFirmware path: $path" }
        connectionCoroutineScope.launch {
            beginFirmwareUpdate(PbzFirmware(path), 0u)
        }
    }

    override fun updateFirmware(url: String) {
        logger.d { "updateFirmware url: $url" }
        connectionCoroutineScope.launch {
            val path = firmwareDownloader.downloadFirmware(url)
            if (path == null) {
                _firmwareUpdateState.value = FirmwareUpdateStatus.NotInProgress.ErrorStarting
                return@launch
            }
            beginFirmwareUpdate(PbzFirmware(path), 0u)
        }
    }

    override fun checkforFirmwareUpdate() {
        firmwareUpdateManager.checkForUpdates()
    }

    private val startMutex = Mutex()

    private suspend fun beginFirmwareUpdate(
        pbzFw: PbzFirmware,
        offset: UInt,
    ) {
        startMutex.withLock {
            if (_firmwareUpdateState.value !is FirmwareUpdateStatus.NotInProgress) {
                logger.w { "Firmware update already in progress!" }
                return
            }
            _firmwareUpdateState.value = FirmwareUpdateStatus.WaitingToStart
        }
        logger.d { "beginFirmwareUpdate" }

        val totalBytes = pbzFw.manifest.firmware.size + (pbzFw.manifest.resources?.size ?: 0)
        require(totalBytes > 0) { "Firmware size is 0" }
        performSafetyChecks(pbzFw)
        val result = systemService.sendFirmwareUpdateStart(offset, totalBytes.toUInt())
        if (result != SystemMessage.FirmwareUpdateStartStatus.Started) {
            error("Failed to start firmware update: $result")
        }
        sendFirmwareParts(pbzFw, offset)
        logger.d { "Firmware update completed, waiting for reboot" }
        _firmwareUpdateState.value = FirmwareUpdateStatus.WaitingForReboot
        systemService.sendFirmwareUpdateComplete()
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
            source = source,
            sendInstall = false,
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
            source = source,
            sendInstall = false,
        ).onCompletion { source.close() }
    }
}