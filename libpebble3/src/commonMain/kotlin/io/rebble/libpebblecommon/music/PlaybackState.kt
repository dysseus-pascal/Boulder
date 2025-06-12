package io.rebble.libpebblecommon.music

import io.rebble.libpebblecommon.packets.MusicControl

enum class PlaybackState(val protocolValue: MusicControl.PlaybackState) {
    Playing(MusicControl.PlaybackState.Playing),
    Paused(MusicControl.PlaybackState.Paused),
    Buffering(MusicControl.PlaybackState.Playing),
    Error(MusicControl.PlaybackState.Paused),
}