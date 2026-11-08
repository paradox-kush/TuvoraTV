package com.nuvio.tv.core.analytics

import com.nuvio.tv.playback.core.ContainerType
import com.nuvio.tv.playback.core.DeliveryType
import com.nuvio.tv.playback.core.EvidenceFact
import com.nuvio.tv.playback.core.EvidenceProvenance
import com.nuvio.tv.playback.core.PlaybackProgressEvidence
import com.nuvio.tv.playback.core.PlaybackSnapshot
import com.nuvio.tv.playback.core.PlaybackState
import com.nuvio.tv.playback.core.StreamEvidence
import com.nuvio.tv.playback.live.LivePlaybackBreadcrumbs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackBreadcrumbPolicyTest {

    private val events = mutableListOf<String>()

    private val sink = object : PlaybackBreadcrumbSink {
        override fun started(owner: Any, identity: PlaybackBreadcrumbIdentity, nowMs: Long) {
            events += "start:${identity.kind}/${identity.engine}/${identity.surface}/${identity.container}"
        }

        override fun stopped(owner: Any) {
            events += "stop"
        }
    }

    private val liveTs = PlaybackBreadcrumbIdentity("live", "media3", "fullscreen", "ts")
    private val liveM3u8 = PlaybackBreadcrumbIdentity("live", "media3", "fullscreen", "m3u8")

    private fun snapshot(state: PlaybackState, rendered: Boolean = false) = PlaybackSnapshot(
        state = state,
        progress = PlaybackProgressEvidence(renderedVideoFrame = rendered),
    )

    /** Drives the tracker exactly as the clean live view models do: snapshot → phase. */
    private fun PlaybackBreadcrumbTracker.feed(snapshot: PlaybackSnapshot, identity: PlaybackBreadcrumbIdentity) =
        onPhase(LivePlaybackBreadcrumbs.phaseOf(snapshot), identity)

    @Test
    fun playingThenZapThenPlayingThenErrorThenStoppedPairsOneStartWithOneStop() {
        val tracker = PlaybackBreadcrumbTracker(sink) { 0L }
        tracker.feed(snapshot(PlaybackState.RESOLVING), liveTs)
        tracker.feed(snapshot(PlaybackState.STARTING_PRIMARY), liveTs)
        tracker.feed(snapshot(PlaybackState.PLAYING, rendered = true), liveTs)
        // zap: the new channel resolves, buffers, then renders — the same viewing
        tracker.feed(snapshot(PlaybackState.RESOLVING), liveTs)
        tracker.feed(snapshot(PlaybackState.PLAYING, rendered = false), liveTs)
        tracker.feed(snapshot(PlaybackState.PLAYING, rendered = true), liveTs)
        tracker.feed(snapshot(PlaybackState.FAILED), liveTs)
        tracker.feed(snapshot(PlaybackState.STOPPED), liveTs)
        tracker.stop() // release after the failure
        assertEquals(listOf("start:live/media3/fullscreen/ts", "stop"), events)
        assertNull(tracker.active)
    }

    @Test
    fun resolvingAloneNeverStarts() {
        val tracker = PlaybackBreadcrumbTracker(sink) { 0L }
        tracker.feed(snapshot(PlaybackState.RESOLVING), liveTs)
        tracker.feed(snapshot(PlaybackState.PLAYING, rendered = false), liveTs)
        tracker.feed(snapshot(PlaybackState.FAILED), liveTs)
        tracker.stop()
        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun zapToADifferentContainerStopsBeforeTheNextStart() {
        val tracker = PlaybackBreadcrumbTracker(sink) { 0L }
        tracker.onOutputStarted(liveTs)
        tracker.onPhase(PlaybackBreadcrumbPhase.TRANSIENT, liveM3u8)
        tracker.onOutputStarted(liveM3u8)
        tracker.stop()
        assertEquals(
            listOf("start:live/media3/fullscreen/ts", "stop", "start:live/media3/fullscreen/m3u8", "stop"),
            events,
        )
    }

    @Test
    fun retryAfterAnErrorIsANewPairedViewing() {
        val tracker = PlaybackBreadcrumbTracker(sink) { 0L }
        tracker.onOutputStarted(liveTs)
        tracker.onPhase(PlaybackBreadcrumbPhase.ENDED, liveTs)
        tracker.onOutputStarted(liveTs)
        tracker.stop()
        tracker.stop()
        assertEquals(listOf("start:live/media3/fullscreen/ts", "stop", "start:live/media3/fullscreen/ts", "stop"), events)
    }

    @Test
    fun outputWithoutAnIdentityStartsNothing() {
        val tracker = PlaybackBreadcrumbTracker(sink) { 0L }
        tracker.onPhase(PlaybackBreadcrumbPhase.OUTPUT, null)
        tracker.stop()
        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun aSupersededSurfaceCannotClearTheNewSurfacesPlayback() {
        val calls = mutableListOf<String>()
        val arbiter = PlaybackBreadcrumbArbiter(
            onStarted = { identity, _ -> calls += "start:${identity.surface}" },
            onStopped = { calls += "stop" },
        )
        val preview = PlaybackBreadcrumbTracker(arbiter) { 0L }
        val fullscreen = PlaybackBreadcrumbTracker(arbiter) { 0L }
        preview.onOutputStarted(liveTs.copy(surface = "preview"))
        fullscreen.onOutputStarted(liveTs)
        preview.stop() // the guide's detach lands after the fullscreen first frame
        fullscreen.stop()
        assertEquals(listOf("start:preview", "start:fullscreen", "stop"), calls)
    }

    @Test
    fun containerCodesNeverCarryMoreThanACode() {
        fun evidence(delivery: DeliveryType? = null, container: ContainerType? = null) = StreamEvidence(
            delivery = delivery?.let { EvidenceFact(it, EvidenceProvenance.PROVIDER_DECLARED) },
            container = container?.let { EvidenceFact(it, EvidenceProvenance.PROVIDER_DECLARED) },
        )
        assertEquals("unknown", LivePlaybackBreadcrumbs.containerCodeOf(StreamEvidence()))
        assertEquals("m3u8", LivePlaybackBreadcrumbs.containerCodeOf(evidence(delivery = DeliveryType.HLS)))
        assertEquals("ts", LivePlaybackBreadcrumbs.containerCodeOf(evidence(delivery = DeliveryType.RAW_TRANSPORT_STREAM)))
        assertEquals(
            "ts",
            LivePlaybackBreadcrumbs.containerCodeOf(evidence(DeliveryType.HLS, ContainerType.MPEG_TS)),
        )
        assertEquals("other", LivePlaybackBreadcrumbs.containerCodeOf(evidence(delivery = DeliveryType.RTSP)))
    }
}
