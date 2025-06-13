package io.rebble.libpebblecommon.io.rebble.libpebblecommon.music

import io.rebble.libpebblecommon.music.PlaybackStatus
import io.rebble.libpebblecommon.music.PlayerInfo
import io.rebble.libpebblecommon.music.SystemMusicControl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class IosSystemMusicControl : SystemMusicControl {
    override fun play() {
        TODO("Not yet implemented")
    }

    override fun pause() {
        TODO("Not yet implemented")
    }

    override fun playPause() {
        TODO("Not yet implemented")
    }

    override fun nextTrack() {
        TODO("Not yet implemented")
    }

    override fun previousTrack() {
        TODO("Not yet implemented")
    }

    override fun volumeDown() {
        TODO("Not yet implemented")
    }

    override fun volumeUp() {
        TODO("Not yet implemented")
    }

    override val playbackState: StateFlow<PlaybackStatus?> = MutableStateFlow(null).asStateFlow()
    override val playerInfo: StateFlow<PlayerInfo?> = MutableStateFlow(null).asStateFlow()
}