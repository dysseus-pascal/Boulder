package io.rebble.libpebblecommon.connection

data class ConnectionException(
    val reason: ConnectionFailureReason,
) : Exception()
