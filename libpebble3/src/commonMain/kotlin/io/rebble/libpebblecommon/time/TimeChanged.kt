package io.rebble.libpebblecommon.time

import io.rebble.libpebblecommon.connection.AppContext

interface TimeChanged {
    fun registerForTimeChanges(onChanged: () -> Unit)
}

expect fun createTimeChanged(appContext: AppContext): TimeChanged