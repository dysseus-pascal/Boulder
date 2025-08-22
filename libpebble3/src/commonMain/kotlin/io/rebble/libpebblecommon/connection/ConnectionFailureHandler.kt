package io.rebble.libpebblecommon.connection

import co.touchlab.kermit.Logger

interface ConnectionFailureHandler {
    suspend fun handleRepeatFailure(identifier: PebbleIdentifier, reason: ConnectionFailureReason)
}

class RealConnectionFailureHandler(
    private val appContext: AppContext,
) : ConnectionFailureHandler {
    private val logger = Logger.withTag("RealConnectionFailureHandler")

    override suspend fun handleRepeatFailure(identifier: PebbleIdentifier, reason: ConnectionFailureReason) {
        logger.d { "handleRepeatFailureReason: $identifier - $reason" }
        when (reason) {
            ConnectionFailureReason.MtuGattError -> appContext.handleMtuGattError(identifier)
            ConnectionFailureReason.GattInsufficientAuth -> appContext.handleMtuGattError(identifier)
            ConnectionFailureReason.CreateBondFailed -> appContext.handleCreateBondFailed(identifier)
            else -> Unit
        }
    }
}

expect fun AppContext.handleMtuGattError(identifier: PebbleIdentifier)
expect fun AppContext.handleGattInsufficientAuth(identifier: PebbleIdentifier)
expect fun AppContext.handleCreateBondFailed(identifier: PebbleIdentifier)