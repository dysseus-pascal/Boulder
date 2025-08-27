package io.rebble.libpebblecommon.connection

interface ConnectionFailureHandler {
    suspend fun handleRepeatFailure(identifier: PebbleIdentifier, reason: ConnectionFailureReason)
}

class RealConnectionFailureHandler(
    private val appContext: AppContext,
) : ConnectionFailureHandler {
    override suspend fun handleRepeatFailure(identifier: PebbleIdentifier, reason: ConnectionFailureReason) {
        when (reason) {
            ConnectionFailureReason.MtuGattError -> appContext.handleMtuGattError(identifier)
            ConnectionFailureReason.GattInsufficientAuth -> appContext.handleGattInsufficientAuth(identifier)
            ConnectionFailureReason.CreateBondFailed -> appContext.handleCreateBondFailed(identifier)
            else -> Unit
        }
    }
}

expect fun AppContext.handleMtuGattError(identifier: PebbleIdentifier)
expect fun AppContext.handleGattInsufficientAuth(identifier: PebbleIdentifier)
expect fun AppContext.handleCreateBondFailed(identifier: PebbleIdentifier)