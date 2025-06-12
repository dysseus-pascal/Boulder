package io.rebble.libpebblecommon.music

import io.rebble.libpebblecommon.packets.MusicControl

enum class RepeatType(val protocolValue: MusicControl.RepeatState) {
    Off(MusicControl.RepeatState.Off),
    One(MusicControl.RepeatState.One),
    All(MusicControl.RepeatState.All)
}