package io.rebble.libpebblecommon.connection.endpointmanager.musiccontrol

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.di.ConnectionCoroutineScope
import io.rebble.libpebblecommon.music.MusicAction
import io.rebble.libpebblecommon.music.PlaybackState
import io.rebble.libpebblecommon.music.PlaybackStatus
import io.rebble.libpebblecommon.music.RepeatType
import io.rebble.libpebblecommon.music.SystemMusicControl
import io.rebble.libpebblecommon.services.MusicService
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlin.math.absoluteValue
import kotlin.time.Duration

class MusicControlManager(
    private val watchScope: ConnectionCoroutineScope,
    private val musicControlService: MusicService,
    private val systemMusicControl: SystemMusicControl
) {
    private val logger = Logger.withTag("MusicControlManager")
    private var lastPlaybackStatus: PlaybackStatus? = null
    fun init() {
        musicControlService.updateRequestTrigger.onEach {
            val status = systemMusicControl.playbackState.value
            val player = systemMusicControl.playerInfo.value
            musicControlService.updateTrack(
                status?.currentTrack ?: MusicTrack(
                    title = "",
                    artist = "",
                    album = "",
                    length = Duration.ZERO
                )
            )
            player?.let {
                musicControlService.updatePlayerInfo(
                    packageId = it.packageId,
                    name = it.name
                )
            } ?: run {
                musicControlService.updatePlayerInfo(
                    packageId = "",
                    name = ""
                )
            }
            musicControlService.updateVolumeInfo(100u) // TODO: Update with actual volume info if available
            musicControlService.updatePlaybackState(
                state = status?.playbackState ?: PlaybackState.Paused,
                trackPosMs = status?.playbackPosition?.toUInt() ?: 0u,
                playbackRatePct = ((status?.playbackRate ?: (1f * 100))).toUInt(),
                shuffle = status?.shuffle ?: false,
                repeatType = status?.repeat ?: RepeatType.Off
            )
        }.launchIn(watchScope)
        systemMusicControl.playerInfo.debounce(50).onEach { playerInfo ->
            logger.d { "Player info updated: $playerInfo" }
            musicControlService.updatePlayerInfo(
                packageId = playerInfo?.packageId ?: "",
                name = playerInfo?.name ?: ""
            )
        }.launchIn(watchScope)
        systemMusicControl.playbackState.debounce(50).distinctUntilChanged { a, b ->
            return@distinctUntilChanged when {
                a == b -> true // No change in playback state
                a == null && b != null -> false // Transition from no playback state to a valid one
                a != null && b == null -> false // Transition from a valid playback state to no playback state
                a?.playbackState != b?.playbackState ||
                        // Check changes in current track without checking album as we don't use it (and it sometimes changes afterwards)
                        a?.currentTrack?.title != b?.currentTrack?.title ||
                        a?.currentTrack?.artist != b?.currentTrack?.artist ||
                        a?.currentTrack?.length != b?.currentTrack?.length ||
                        a?.shuffle != b?.shuffle ||
                        a?.repeat != b?.repeat ||
                        a?.playbackRate != b?.playbackRate -> false // Change of meaningful playback state
                a?.playbackPosition != null && b?.playbackPosition != null &&
                        (b.playbackPosition - a.playbackPosition).absoluteValue >= 200 -> false // Significant change in playback position
                else -> {
                    // No significant change, treat as unchanged
                    logger.d { "Playback state not significantly changed: ${a?.playbackState} ${a?.playbackPosition}" }
                    true
                }
            }
        }.onEach { status ->
            if (status == lastPlaybackStatus) return@onEach
            logger.d {
                "Playback state changed: ${status?.playbackState} ${status?.playbackPosition} " +
                        "(${status?.currentTrack?.title ?: "No Track"})"
            }
            status?.currentTrack?.let { musicControlService.updateTrack(it) }
            musicControlService.updateVolumeInfo(100u) // TODO: Update with actual volume info if available
            status?.let {
                musicControlService.updatePlaybackState(
                    state = it.playbackState,
                    trackPosMs = it.playbackPosition.toUInt(),
                    playbackRatePct = (it.playbackRate * 100).toUInt(),
                    shuffle = it.shuffle,
                    repeatType = it.repeat
                )
            } ?: run {
                musicControlService.updatePlaybackState(
                    state = PlaybackState.Paused,
                    trackPosMs = 0u,
                    playbackRatePct = 100u,
                    shuffle = false,
                    repeatType = RepeatType.Off
                )
            }
            lastPlaybackStatus = status
        }.launchIn(watchScope)
        musicControlService.musicActions.onEach {
            logger.d { "Received music action: $it" }
            when (it) {
                MusicAction.Play -> systemMusicControl.play()
                MusicAction.Pause -> systemMusicControl.pause()
                MusicAction.PlayPause -> systemMusicControl.playPause()
                MusicAction.NextTrack -> systemMusicControl.nextTrack()
                MusicAction.PreviousTrack -> systemMusicControl.previousTrack()
                MusicAction.VolumeDown -> systemMusicControl.volumeDown()
                MusicAction.VolumeUp -> systemMusicControl.volumeUp()
            }
        }.launchIn(watchScope)
    }
}