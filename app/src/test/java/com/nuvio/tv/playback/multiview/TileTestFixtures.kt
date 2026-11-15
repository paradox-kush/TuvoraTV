package com.nuvio.tv.playback.multiview

import com.nuvio.tv.core.iptv.BoundedLoad
import com.nuvio.tv.core.multiview.TileLoadBounds
import com.nuvio.tv.core.multiview.TileOutcome
import com.nuvio.tv.core.picture.AspectMode
import com.nuvio.tv.core.picture.VideoZoom
import com.nuvio.tv.playback.core.ContentType
import com.nuvio.tv.playback.core.FailureCode
import com.nuvio.tv.playback.core.FailureDomain
import com.nuvio.tv.playback.core.FailurePhase
import com.nuvio.tv.playback.core.PlaybackFailure
import com.nuvio.tv.playback.core.PlaybackProfileId
import com.nuvio.tv.playback.core.PlaybackProgressEvidence
import com.nuvio.tv.playback.core.PlaybackSnapshot
import com.nuvio.tv.playback.core.PlaybackState
import com.nuvio.tv.playback.core.PlaybackTrackId
import com.nuvio.tv.playback.core.ProviderPlaybackSelection
import com.nuvio.tv.playback.core.ProviderSelectionId
import com.nuvio.tv.playback.core.ProviderSourceType
import com.nuvio.tv.playback.core.Retryability
import com.nuvio.tv.playback.core.SessionProfile
import com.nuvio.tv.playback.host.CleanLiveHost
import com.nuvio.tv.playback.live.LiveChannelTarget
import com.nuvio.tv.playback.live.LiveInitialFailure
import com.nuvio.tv.playback.live.LiveInitialResult
import com.nuvio.tv.playback.mediasession.CleanMediaSessionMetadata
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal val TEST_PROFILE = PlaybackProfileId("1")

internal fun failure(code: FailureCode, domain: FailureDomain = FailureDomain.NETWORK, httpStatus: Int? = null) =
    PlaybackFailure(code, domain, FailurePhase.PLAYBACK, Retryability.FATAL, httpStatus = httpStatus)

internal fun snap(
    state: PlaybackState,
    generation: Long = 1,
    isPlaying: Boolean = false,
    playWhenReady: Boolean = state == PlaybackState.PLAYING,
    progress: PlaybackProgressEvidence = PlaybackProgressEvidence(),
    failure: PlaybackFailure? = null,
    isReconnecting: Boolean = false,
    isBuffering: Boolean = false,
) = PlaybackSnapshot(
    generation = generation,
    state = state,
    profile = SessionProfile.GUIDE,
    isPlaying = isPlaying,
    playWhenReady = playWhenReady,
    progress = progress,
    failure = failure,
    isReconnecting = isReconnecting,
    isBuffering = isBuffering,
)

internal fun playingSnapshot(generation: Long = 1, playWhenReady: Boolean = true) = snap(
    PlaybackState.PLAYING,
    generation = generation,
    isPlaying = playWhenReady,
    playWhenReady = playWhenReady,
    progress = PlaybackProgressEvidence(
        receivedBytes = true, discoveredTracks = true, decoderReady = true, rendererReady = true,
        renderedAudio = true, renderedVideoFrame = true,
    ),
)

internal fun testChannel(id: String = "a") = TileChannel(key = id, contentId = ProviderSelectionId("content-$id"))

internal fun testTarget(id: String = "a", sourceType: ProviderSourceType = ProviderSourceType.XTREAM): LiveChannelTarget {
    val content = ProviderSelectionId("content-$id")
    val selection = ProviderPlaybackSelection(
        sourceType = sourceType,
        accountId = ProviderSelectionId("account"),
        itemId = ProviderSelectionId("item-$id"),
        contentKey = content,
        contentType = ContentType.LIVE,
    )
    return LiveChannelTarget.sanitized(
        selection = selection,
        contentId = content,
        title = "Channel $id",
        logo = null,
        playlistVersion = 1,
        boundProfileId = TEST_PROFILE,
    )
}

internal fun targetResult(id: String = "a", sourceType: ProviderSourceType = ProviderSourceType.XTREAM): LiveInitialResult =
    LiveInitialResult.Target(testTarget(id, sourceType))

internal fun rejectedResult(): LiveInitialResult = LiveInitialResult.Rejected(LiveInitialFailure.UNAVAILABLE)

/** A host that does nothing but record what it was asked and let a test drive its snapshot. */
internal class FakeLiveHost : CleanLiveHost {
    val state = MutableStateFlow(PlaybackSnapshot())
    override val snapshot: StateFlow<PlaybackSnapshot> = state
    val calls = ArrayList<String>()
    var released = false

    override suspend fun tune(
        selection: ProviderPlaybackSelection,
        profile: SessionProfile,
        metadata: CleanMediaSessionMetadata,
    ): Long {
        calls += "tune:$profile"
        state.value = state.value.copy(generation = state.value.generation + 1, state = PlaybackState.RESOLVING)
        return state.value.generation
    }

    override suspend fun zap(
        selection: ProviderPlaybackSelection,
        profile: SessionProfile,
        metadata: CleanMediaSessionMetadata,
    ): Long = error("a tile never zaps")

    override suspend fun pause() { calls += "pause" }
    override suspend fun resume() { calls += "resume" }
    override suspend fun retry() { calls += "retry" }
    override suspend fun setAudioEnabled(enabled: Boolean) { calls += "audio:$enabled" }
    override suspend fun selectAudioTrack(trackId: PlaybackTrackId) { calls += "selectAudio" }
    override suspend fun selectSubtitleTrack(trackId: PlaybackTrackId) { calls += "selectSubtitle" }
    override suspend fun disableSubtitles() { calls += "disableSubtitles" }
    override suspend fun applyPicture(mode: AspectMode, zoom: VideoZoom) { calls += "picture" }
    override suspend fun changeProfile(profile: SessionProfile) { calls += "profile" }
    override suspend fun stop() { calls += "stop" }
    override suspend fun release() { calls += "release"; released = true }

    fun count(call: String) = calls.count { it == call }
}

internal class RecordingBounds(override val stallMs: Long = 20_000L) : TileLoadBounds {
    data class Report(val outcome: TileOutcome, val sourceType: String, val tileCount: Int)

    val reports = ArrayList<Report>()
    override fun report(outcome: TileOutcome, sourceType: String, tileCount: Int) {
        reports += Report(outcome, sourceType, tileCount)
    }
}

/** Keeps BoundedLoad out of the real analytics for the duration of a test. */
internal object BoundedLoadSinkForTests {
    fun <T> capturing(into: MutableList<Pair<String, Map<String, Any>>>, block: () -> T): T {
        val real = BoundedLoad.sink
        BoundedLoad.resetReportsForTests()
        BoundedLoad.sink = { name, props -> into += name to props }
        return try {
            block()
        } finally {
            BoundedLoad.sink = real
            BoundedLoad.resetReportsForTests()
        }
    }
}
