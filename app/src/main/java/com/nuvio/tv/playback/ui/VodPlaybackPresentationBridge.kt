package com.nuvio.tv.playback.ui

import com.nuvio.tv.playback.core.PlaybackCompletionReason
import com.nuvio.tv.playback.core.PlaybackFailure
import com.nuvio.tv.playback.core.PlaybackSnapshot
import com.nuvio.tv.playback.core.PlaybackState
import com.nuvio.tv.playback.core.PlaybackTrackDescriptor
import com.nuvio.tv.playback.host.CleanVodHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Playback-only projection consumed by the existing VOD UI compatibility layer. */
internal data class VodPlaybackPresentationState(
    val generation: Long = 0,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val playbackEnded: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val bufferedPositionMs: Long = 0,
    val seekable: Boolean = false,
    val playbackRate: Float = 1f,
    val audioTracks: List<PlaybackTrackDescriptor> = emptyList(),
    val subtitleTracks: List<PlaybackTrackDescriptor> = emptyList(),
    val selectedAudioIndex: Int = -1,
    val selectedSubtitleIndex: Int = -1,
    val subtitlesEnabled: Boolean = false,
    val failure: PlaybackFailure? = null,
    val controlFailure: PlaybackFailure? = null,
)

internal sealed interface VodPlaybackIntent {
    data object PlayPause : VodPlaybackIntent
    data class SeekTo(val positionMs: Long) : VodPlaybackIntent
    data class SetPlaybackRate(val rate: Float) : VodPlaybackIntent
    data class SelectAudioIndex(val index: Int) : VodPlaybackIntent
    data class SelectSubtitleIndex(val index: Int) : VodPlaybackIntent
    data object DisableSubtitles : VodPlaybackIntent
    data object Retry : VodPlaybackIntent
}

/**
 * Thin compatibility boundary: maps UI intent to clean commands and clean snapshots to UI facts.
 * It owns no engine, request, recovery, decoder, watchdog, provider or device policy.
 */
internal class VodPlaybackPresentationBridge(
    private val host: CleanVodHost,
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow(present(host.snapshot.value))
    val state: StateFlow<VodPlaybackPresentationState> = mutableState.asStateFlow()
    private var collectionJob: Job? = null

    fun start() {
        if (collectionJob?.isActive == true) return
        collectionJob = scope.launch {
            host.snapshot.collect { mutableState.value = present(it) }
        }
    }

    fun dispatch(intent: VodPlaybackIntent) {
        scope.launch {
            when (intent) {
                VodPlaybackIntent.PlayPause -> {
                    if (host.snapshot.value.playWhenReady) host.pause() else host.resume()
                }
                is VodPlaybackIntent.SeekTo -> host.seekTo(intent.positionMs)
                is VodPlaybackIntent.SetPlaybackRate -> host.setPlaybackRate(intent.rate)
                is VodPlaybackIntent.SelectAudioIndex -> {
                    state.value.audioTracks.getOrNull(intent.index)?.let { host.selectAudioTrack(it.id) }
                }
                is VodPlaybackIntent.SelectSubtitleIndex -> {
                    state.value.subtitleTracks.getOrNull(intent.index)?.let { host.selectSubtitleTrack(it.id) }
                }
                VodPlaybackIntent.DisableSubtitles -> host.disableSubtitles()
                VodPlaybackIntent.Retry -> host.retry()
            }
        }
    }

    fun stop() {
        collectionJob?.cancel()
        collectionJob = null
    }

    companion object {
        fun present(snapshot: PlaybackSnapshot): VodPlaybackPresentationState {
            val catalog = snapshot.trackCatalog
            return VodPlaybackPresentationState(
                generation = snapshot.generation,
                isPlaying = snapshot.isPlaying,
                isBuffering = snapshot.isBuffering,
                playbackEnded = snapshot.completionReason == PlaybackCompletionReason.EOF &&
                    snapshot.state == PlaybackState.STOPPED,
                positionMs = snapshot.positionMs,
                durationMs = snapshot.durationMs ?: 0,
                bufferedPositionMs = snapshot.bufferedPositionMs,
                seekable = snapshot.seekable,
                playbackRate = snapshot.playbackRate,
                audioTracks = catalog.audio,
                subtitleTracks = catalog.subtitles,
                selectedAudioIndex = catalog.audio.indexOfFirst { it.id == catalog.selectedAudioTrackId },
                selectedSubtitleIndex = catalog.subtitles.indexOfFirst {
                    it.id == catalog.selectedSubtitleTrackId
                },
                subtitlesEnabled = catalog.subtitlesEnabled,
                failure = snapshot.failure,
                controlFailure = snapshot.controlFailure,
            )
        }
    }
}
