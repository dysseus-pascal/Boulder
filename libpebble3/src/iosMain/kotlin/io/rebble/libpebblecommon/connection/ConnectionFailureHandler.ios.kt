package io.rebble.libpebblecommon.connection

import io.rebble.libpebblecommon.LibPebbleAnalytics
import io.rebble.libpebblecommon.metadata.WatchColor

actual fun AppContext.handleMtuGattError(identifier: PebbleIdentifier, color: WatchColor, analytics: LibPebbleAnalytics) {
}

actual fun AppContext.handleGattInsufficientAuth(identifier: PebbleIdentifier, color: WatchColor, analytics: LibPebbleAnalytics) {
}

actual fun AppContext.handleCreateBondFailed(identifier: PebbleIdentifier, color: WatchColor, analytics: LibPebbleAnalytics) {
}