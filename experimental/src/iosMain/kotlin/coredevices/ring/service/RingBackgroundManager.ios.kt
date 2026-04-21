package coredevices.ring.service

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

actual class RingBackgroundManager: KoinComponent {
    private val backgroundRingService: BackgroundRingService by inject()
    actual val isRunning = backgroundRingService.isRunning

    actual fun startBackground() {
        backgroundRingService.startRingSyncJob()
    }

    actual fun stopBackground() {
        backgroundRingService.stopRingSyncJob()
    }

    actual fun startBackgroundIfEnabled() {
        if (!isRunning.value) {
            startBackground()
        }
    }

    actual fun monitorToStartBackground(){
        // on ios bg service handles whether it runs as it isn't a foreground service so it can stay in memory
    }
}