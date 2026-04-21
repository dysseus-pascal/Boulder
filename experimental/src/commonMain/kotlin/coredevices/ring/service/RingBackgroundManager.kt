package coredevices.ring.service

import kotlinx.coroutines.flow.StateFlow

expect class RingBackgroundManager() {
    fun startBackground()
    fun stopBackground()
    fun startBackgroundIfEnabled()
    fun monitorToStartBackground()
    val isRunning: StateFlow<Boolean>
}