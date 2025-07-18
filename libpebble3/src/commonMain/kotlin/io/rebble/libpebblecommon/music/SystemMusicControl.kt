package io.rebble.libpebblecommon.music

import io.rebble.libpebblecommon.connection.endpointmanager.musiccontrol.MusicTrack
import kotlinx.coroutines.flow.StateFlow

interface SystemMusicControl {
    fun play()
    fun pause()
    fun playPause()
    fun nextTrack()
    fun previousTrack()
    fun volumeDown()
    fun volumeUp()
    val playbackState: StateFlow<PlaybackStatus?>
}

data class PlayerInfo(
    val packageId: String,
    val name: String,
)

data class PlaybackStatus(
    val playerInfo: PlayerInfo?,
    val playbackState: PlaybackState,
    val currentTrack: MusicTrack? = null,
    val playbackPositionMs: Long, // Position in milliseconds
    val playbackRate: Float, // Playback rate, 1.0 is normal speed
    val shuffle: Boolean,
    val repeat: RepeatType,
    val volume: Int,
)