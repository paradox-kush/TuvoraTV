package com.nuvio.tv.playback.mediasession

import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.nuvio.tv.playback.core.ContentType
import com.nuvio.tv.playback.core.PlaybackSnapshot
import com.nuvio.tv.playback.core.PlaybackState
import com.nuvio.tv.playback.ui.PlaybackSessionController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Media3 control facade for the engine-neutral clean session.
 *
 * This is not a playback engine and owns no decoder, network request, or Surface. It projects the
 * secret-free [PlaybackSnapshot] into Media3's controller contract and sends the three V1 transport
 * commands only through [PlaybackSessionController]. Media3 and libmpv therefore expose identical
 * system controls. V1 intentionally exposes only live play, pause, and stop; VOD/catch-up seeking
 * remains unavailable until the clean core owns an engine-neutral seek command and position model.
 */
@UnstableApi
internal class CleanMediaSessionPlayer(
    applicationLooper: Looper,
    parentScope: CoroutineScope,
    private val controller: PlaybackSessionController,
    metadata: CleanMediaSessionMetadata,
) : SimpleBasePlayer(applicationLooper) {
    private val handler = Handler(applicationLooper)
    private val playerJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + playerJob)

    @Volatile
    private var latestSnapshot: PlaybackSnapshot = controller.snapshot.value

    @Volatile
    private var latestMetadata: CleanMediaSessionMetadata = metadata

    init {
        scope.launch {
            controller.snapshot.collectLatest(::publishSnapshot)
        }
    }

    override fun getState(): State = stateFor(latestSnapshot, latestMetadata)

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> =
        dispatch {
            if (playWhenReady) controller.resume() else controller.pause()
        }

    override fun handleStop(): ListenableFuture<*> = dispatch(controller::stop)

    override fun handleRelease(): ListenableFuture<*> {
        playerJob.cancel()
        return completedFuture()
    }

    fun updateMetadata(metadata: CleanMediaSessionMetadata) {
        runOnApplicationLooper {
            latestMetadata = metadata
            invalidateState()
        }
    }

    private fun publishSnapshot(snapshot: PlaybackSnapshot) {
        runOnApplicationLooper {
            val previous = latestSnapshot
            latestSnapshot = snapshot
            // The system session projects only discrete fields; a live position tick (500ms)
            // must not rebuild MediaMetadata/State and ping system UI twice a second.
            val discreteChange = previous.generation != snapshot.generation ||
                previous.state != snapshot.state ||
                previous.playWhenReady != snapshot.playWhenReady ||
                previous.isPlaying != snapshot.isPlaying ||
                previous.isBuffering != snapshot.isBuffering ||
                previous.durationMs != snapshot.durationMs ||
                previous.seekable != snapshot.seekable ||
                previous.playbackRate != snapshot.playbackRate ||
                previous.failure != snapshot.failure
            if (discreteChange) invalidateState()
        }
    }

    private fun runOnApplicationLooper(block: () -> Unit) {
        if (Looper.myLooper() == applicationLooper) {
            block()
        } else {
            check(handler.post(block)) { "MediaSession application looper is shutting down" }
        }
    }

    private fun dispatch(command: suspend () -> Unit): ListenableFuture<*> {
        val result = SettableFuture.create<Unit>()
        scope.launch {
            runCatching { command() }
                .onSuccess { result.set(Unit) }
                .onFailure(result::setException)
        }
        return result
    }

    private fun stateFor(
        snapshot: PlaybackSnapshot,
        metadata: CleanMediaSessionMetadata,
    ): State {
        val mediaMetadata = MediaMetadata.Builder()
            .setTitle(metadata.title)
            .setDisplayTitle(metadata.title)
            .setSubtitle(metadata.subtitle)
            .setStation(metadata.station)
            .setIsPlayable(true)
            .build()
        val isLive = snapshot.requestSummary?.contentType == ContentType.LIVE
        val liveConfiguration = if (isLive) MediaItem.LiveConfiguration.Builder().build() else null
        val mediaItemBuilder = MediaItem.Builder()
            .setMediaId(metadata.safeMediaId)
            .setMediaMetadata(mediaMetadata)
        if (liveConfiguration != null) mediaItemBuilder.setLiveConfiguration(liveConfiguration)
        val mediaItem = mediaItemBuilder.build()
        val durationUs = snapshot.durationMs
            ?.coerceAtMost(Long.MAX_VALUE / 1_000L)
            ?.times(1_000L)
            ?: C.TIME_UNSET
        val itemData = MediaItemData.Builder(metadata.safeMediaId)
            .setMediaItem(mediaItem)
            .setMediaMetadata(mediaMetadata)
            .setIsDynamic(isLive)
            .setIsSeekable(false)
            .setDurationUs(durationUs)
            .apply { if (liveConfiguration != null) setLiveConfiguration(liveConfiguration) }
            .build()
        return State.Builder()
            .setAvailableCommands(AVAILABLE_COMMANDS)
            .setPlaylist(listOf(itemData))
            .setCurrentMediaItemIndex(0)
            .setContentPositionMs(snapshot.positionMs.coerceAtLeast(0L))
            .setContentBufferedPositionMs { snapshot.positionMs.coerceAtLeast(0L) }
            .setPlaybackState(snapshot.toMedia3PlaybackState())
            .setPlayWhenReady(
                snapshot.playWhenReady,
                Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST,
            )
            .setIsLoading(snapshot.isBuffering || snapshot.state.isStartupOrRecovery())
            .build()
    }

    private fun PlaybackSnapshot.toMedia3PlaybackState(): Int = when (state) {
        PlaybackState.PLAYING,
        PlaybackState.DEGRADED,
        -> Player.STATE_READY

        PlaybackState.RESOLVING,
        PlaybackState.SELECTING_GRAPH,
        PlaybackState.ATTACHING_SURFACE,
        PlaybackState.STARTING_PRIMARY,
        PlaybackState.RECOVERING_IN_PLACE,
        PlaybackState.HANDING_OFF_ONCE,
        PlaybackState.LIVE_RECONNECTING,
        -> Player.STATE_BUFFERING

        PlaybackState.IDLE,
        PlaybackState.RELEASING,
        PlaybackState.STOPPED,
        PlaybackState.FAILED,
        -> Player.STATE_IDLE
    }

    private fun PlaybackState.isStartupOrRecovery(): Boolean = when (this) {
        PlaybackState.RESOLVING,
        PlaybackState.SELECTING_GRAPH,
        PlaybackState.ATTACHING_SURFACE,
        PlaybackState.STARTING_PRIMARY,
        PlaybackState.RECOVERING_IN_PLACE,
        PlaybackState.HANDING_OFF_ONCE,
        PlaybackState.LIVE_RECONNECTING,
        -> true

        else -> false
    }

    private companion object {
        val AVAILABLE_COMMANDS: Player.Commands = Player.Commands.Builder()
            .add(Player.COMMAND_PLAY_PAUSE)
            .add(Player.COMMAND_STOP)
            .add(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
            .add(Player.COMMAND_GET_TIMELINE)
            .add(Player.COMMAND_GET_METADATA)
            .add(Player.COMMAND_RELEASE)
            .build()

        fun completedFuture(): ListenableFuture<Unit> = SettableFuture.create<Unit>().apply {
            set(Unit)
        }
    }
}
