package io.rebble.libpebblecommon.io.rebble.libpebblecommon.music

import android.content.Context
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.connection.CompanionDevice
import io.rebble.libpebblecommon.connection.endpointmanager.musiccontrol.MusicTrack
import io.rebble.libpebblecommon.connection.endpointmanager.musiccontrol.toLibPebbleState
import io.rebble.libpebblecommon.di.LibPebbleCoroutineScope
import io.rebble.libpebblecommon.music.PlaybackStatus
import io.rebble.libpebblecommon.music.PlayerInfo
import io.rebble.libpebblecommon.music.RepeatType
import io.rebble.libpebblecommon.music.SystemMusicControl
import io.rebble.libpebblecommon.notification.LibPebbleNotificationListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlin.time.Duration.Companion.milliseconds

private fun createTrack(metadata: MediaMetadata): MusicTrack {
    return MusicTrack(
        title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE),
        artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST),
        album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM),
        length = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION).milliseconds,
        trackNumber = metadata.getLong(MediaMetadata.METADATA_KEY_TRACK_NUMBER).toInt()
            .takeIf {
                it > 0
            },
        totalTracks = metadata.getLong(MediaMetadata.METADATA_KEY_NUM_TRACKS).toInt()
            .takeIf {
                it > 0
            }
    )
}

class AndroidSystemMusicControl(
    appContext: AppContext,
    private val libPebbleCoroutineScope: LibPebbleCoroutineScope,
    private val companionDevice: CompanionDevice,
): SystemMusicControl {
    private val logger = Logger.withTag("AndroidSystemMusicControl")
    private val context = appContext.context
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mediaSessionManager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private val notificationServiceComponent = LibPebbleNotificationListener.componentName(context)

    private fun addCallbackSafely(listener: MediaSessionManager.OnActiveSessionsChangedListener): Boolean {
        try {
            mediaSessionManager.addOnActiveSessionsChangedListener(
                listener,
                notificationServiceComponent
            )
            return true
        } catch (e: SecurityException) {
            return false
        }
    }

    private val activeSessions = callbackFlow {
        val listener = MediaSessionManager.OnActiveSessionsChangedListener {
            trySend(
                mediaSessionManager.getActiveSessions(notificationServiceComponent)
            )
        }
        if (!addCallbackSafely(listener)) {
            logger.i { "Couldn't add media listener; waiting for notification access" }
            companionDevice.notificationAccessGranted.first()
            if (!addCallbackSafely(listener)) {
                logger.e { "Couldn't add media listener after notification access granted" }
            }
        }
        try {
            trySend(
                mediaSessionManager.getActiveSessions(notificationServiceComponent)
            )
        } catch (e: SecurityException) {
            logger.e(e) { "Error getting music sessions" }
        }
        awaitClose {
            mediaSessionManager.removeOnActiveSessionsChangedListener(listener)
        }
    }.flowOn(Dispatchers.Main).onEach {
        logger.d { "Active media sessions changed: ${it.size}" }
    }
    private val targetSession = activeSessions.map { sessions ->
        sessions.lastOrNull {
            it.playbackState?.state in setOf(
                PlaybackState.STATE_FAST_FORWARDING,
                PlaybackState.STATE_REWINDING,
                PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
                PlaybackState.STATE_SKIPPING_TO_NEXT,
                PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM,
                PlaybackState.STATE_BUFFERING,
                PlaybackState.STATE_CONNECTING,
                PlaybackState.STATE_PLAYING,
            )
        } ?: sessions.lastOrNull()
    }.stateIn(libPebbleCoroutineScope, SharingStarted.Eagerly, null)

    override val playbackState: StateFlow<PlaybackStatus?> = targetSession.flatMapLatest { session ->
        callbackFlow {
            var lastState = PlaybackStatus(
                playbackState = session?.playbackState?.toLibPebbleState()
                    ?: io.rebble.libpebblecommon.music.PlaybackState.Paused,
                currentTrack = session?.metadata?.let { createTrack(it) },
                playbackPosition = session?.playbackState?.position ?: 0L,
                playbackRate = session?.playbackState?.playbackSpeed ?: 0f,
                shuffle = false, // TODO: is this used / needed?
                repeat = RepeatType.Off // same as above
            )
            trySend(lastState)
            val listener = object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: MediaMetadata?) {
                    lastState = lastState.copy(
                        currentTrack = metadata?.let { createTrack(it) },
                        playbackPosition = 0L
                    )
                    trySend(lastState)
                }

                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    lastState = lastState.copy(
                        playbackState = state?.toLibPebbleState()
                            ?: io.rebble.libpebblecommon.music.PlaybackState.Paused,
                        playbackPosition = state?.position?.takeIf { it > 0 } ?: 0L,
                        playbackRate = state?.playbackSpeed?.takeIf { it > 0 } ?: 0f,
                    )
                    trySend(lastState)
                }

                override fun onSessionDestroyed() {
                    close()
                }
            }
            session?.registerCallback(listener)
                ?: run {
                    logger.w { "No active media session available" }
                    trySend(null)
                }
            awaitClose {
                session?.unregisterCallback(listener)
            }
        }.flowOn(Dispatchers.Main)
    }.stateIn(libPebbleCoroutineScope, SharingStarted.Eagerly, null)

    override val playerInfo: StateFlow<PlayerInfo?> = targetSession.map {
        it?.let { session ->
            PlayerInfo(
                packageId = session.packageName,
                name = "Player" //TODO: Get the actual player name
            )
        }
    }.stateIn(libPebbleCoroutineScope, SharingStarted.Eagerly, null)

    override fun play() {
        logger.d { "Playing media" }
        targetSession.value?.transportControls?.play() ?: run {
            // Fallback to audio manager if no session is available
            logger.w { "No active media session found, falling back to AudioManager for play" }
            audioManager.dispatchMediaKeyEvent(
                android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PLAY)
            )
        }
    }

    override fun pause() {
        logger.d { "Pausing playback" }
        targetSession.value?.transportControls?.pause()
    }

    override fun playPause() {
        targetSession.value?.playbackState?.state?.let {
            when (it) {
                PlaybackState.STATE_PLAYING,
                PlaybackState.STATE_BUFFERING,
                PlaybackState.STATE_CONNECTING -> pause()
                else -> play() // Fallback to play if not playing or paused
            }
        } ?: run {
            logger.i { "No playback state available, defaulting to play" }
            play()
        }
    }

    override fun nextTrack() {
        targetSession.value?.transportControls?.skipToNext()
    }

    override fun previousTrack() {
        targetSession.value?.transportControls?.skipToPrevious()
    }

    override fun volumeDown() {
        audioManager.adjustVolume(AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
    }

    override fun volumeUp() {
        audioManager.adjustVolume(AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
    }
}