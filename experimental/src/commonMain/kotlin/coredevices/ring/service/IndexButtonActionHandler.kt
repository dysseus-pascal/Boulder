package coredevices.ring.service

import co.touchlab.kermit.Logger
import coredevices.ring.database.MusicControlMode
import coredevices.ring.database.Preferences

class IndexButtonActionHandler(prefs: Preferences, sequenceRecorder: IndexButtonSequenceRecorder) {
    companion object {
        private val logger = Logger.withTag("IndexButtonActionHandler")
    }
    private val sequenceEvents = sequenceRecorder.sequenceEvents()

    //TODO: expand configurability
    private val actions = mapOf<List<ButtonPress>, suspend () -> Unit>(
        listOf(ButtonPress.Short, ButtonPress.Short) to {
            if (prefs.musicControlMode.value == MusicControlMode.DoubleClick) {
                onPlayPause()
            }
        },
        listOf(ButtonPress.Short) to {
            if (prefs.musicControlMode.value == MusicControlMode.SingleClick) {
                onPlayPause()
            }
        }
    )

    suspend fun handleButtonActions() {
        sequenceEvents.collect { buttonPresses ->
            val action = actions[buttonPresses]
            action?.invoke()?.let {
                logger.i("Handled button action for sequence: ${buttonPresses.joinToString(", ") { it.name }}")
            }
        }
    }
}