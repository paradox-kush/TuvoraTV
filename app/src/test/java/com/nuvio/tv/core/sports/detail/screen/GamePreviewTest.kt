package com.nuvio.tv.core.sports.detail.screen

import com.nuvio.tv.core.sports.detail.GameState
import org.junit.Assert.assertEquals
import org.junit.Test

class GamePreviewTest {
    private fun c(id: String, conf: Int = 0, score: Int = 10, single: Boolean = false, logo: String? = "l.png") = WatchCandidate(id, "Ch $id", conf, score, logo, single)

    private fun slot(
        state: GameState? = GameState.LIVE, watching: String? = null, watchingName: String? = null, cands: List<WatchCandidate> = emptyList(),
        matching: Boolean = false, failed: Boolean = false, playlists: Boolean = true, previewFailure: PreviewFailure? = null,
    ) = PreviewResolver.resolve(state, watching, watchingName, cands, matching, failed, playlists, previewFailure)

    @Test fun `a confirmed reusable channel previews live`() =
        assertEquals(PreviewSlot.Live("a", "Ch a"), slot(cands = listOf(c("a"))))
    @Test fun `the best confirmed channel is the one previewed`() =
        assertEquals(PreviewSlot.Live("hi", "Ch hi"), slot(cands = listOf(c("lo", 0, 5), c("hi", 0, 50), c("poss", 1, 99))))
    @Test fun `a single-use link is never previewed`() =
        assertEquals(PreviewSlot.PressOk("Ch a", "l.png"), slot(cands = listOf(c("a", single = true))))
    @Test fun `a merely possible match is not previewed either`() =
        assertEquals(PreviewSlot.PressOk("Ch p", "l.png"), slot(cands = listOf(c("p", conf = 1))))
    @Test fun `no match shows the action not a black box`() =
        assertEquals(PreviewSlot.NoMatch(failed = false), slot())
    @Test fun `a failed lookup is flagged`() = assertEquals(PreviewSlot.NoMatch(failed = true), slot(failed = true))
    @Test fun `while still looking there is a quiet searching state`() = assertEquals(PreviewSlot.Searching, slot(matching = true))
    @Test fun `no playlist offers the action`() = assertEquals(PreviewSlot.NoMatch(failed = false), slot(playlists = false))
    @Test fun `opened while watching shows what is playing and never starts a stream`() =
        assertEquals(PreviewSlot.Watching("Sky Sports 1"), slot(watching = "cur", watchingName = "Sky Sports 1", cands = listOf(c("a"))))
    @Test fun `only live games have a preview`() {
        assertEquals(PreviewSlot.Hidden, slot(state = GameState.FINAL, cands = listOf(c("a"))))
        assertEquals(PreviewSlot.Hidden, slot(state = GameState.UPCOMING, cands = listOf(c("a"))))
        assertEquals(PreviewSlot.Hidden, slot(state = null, cands = listOf(c("a"))))
    }
    @Test fun `a preview failure ends quietly with no loop and carries its reason`() =
        assertEquals(PreviewSlot.Unavailable(PreviewFailure.TIMEOUT), slot(cands = listOf(c("a")), previewFailure = PreviewFailure.TIMEOUT))
    @Test fun `a failed preview does not hide the single-use message`() =
        assertEquals(PreviewSlot.PressOk("Ch a", "l.png"), slot(cands = listOf(c("a", single = true)), previewFailure = PreviewFailure.TIMEOUT))
    @Test fun `dwell is one second`() = assertEquals(1_000L, PreviewResolver.DWELL_MS)
    @Test fun `the no-match box has no action of its own - the source row owns the single Find channels button`() {
        val watch = WatchResolver.resolve(null, null, emptyList(), false, false, true)
        val ui = GameDetailUiState.Loaded(com.nuvio.tv.core.sports.detail.GameDetail.skeleton(com.nuvio.tv.core.sports.detail.GameRef("tsdb:1", "1", "A", "B", 0L)))
        assertEquals(setOf(FocusKey.PRIMARY), GameDetailFocus.controls(ui, watch, PreviewSlot.NoMatch(false)))
        assertEquals(FocusKey.PRIMARY, GameDetailFocus.initial(ui, watch, PreviewSlot.NoMatch(false)))
    }

    @Test fun `every failure has a telemetry code and a short human reason`() {
        assertEquals("no_lineup", PreviewFailure.NO_LINEUP.code)
        assertEquals("rejected_host_creation_failed", PreviewFailure.rejected("HOST_CREATION_FAILED").code)
        assertEquals("player could not start", PreviewFailure.rejected("HOST_CREATION_FAILED").label)
        assertEquals("stream_network_timeout", PreviewFailure.stream("NETWORK_TIMEOUT").code)
        assertEquals("stream timed out", PreviewFailure.stream("NETWORK_TIMEOUT").label)
        assertEquals("stream timed out", PreviewFailure.stream("NO_PROGRESS").label)
        assertEquals("decoder error", PreviewFailure.stream("VIDEO_DECODER_FAILED").label)
        assertEquals("provider refused the stream", PreviewFailure.stream("PROVIDER_CONNECTION_LIMIT").label)
        assertEquals("unknown code still maps", "stream error", PreviewFailure.stream("SOMETHING_NEW").label)
        assertEquals("missing code", "stream_unknown", PreviewFailure.stream(null).code)
    }
    @Test fun `codes never contain anything but lowercase letters digits and underscores`() {
        listOf(PreviewFailure.NO_LINEUP, PreviewFailure.TIMEOUT, PreviewFailure.rejected("TUNE_FAILED"), PreviewFailure.stream("MANIFEST_INVALID"))
            .forEach { assertEquals(it.code, true, Regex("[a-z0-9_]+").matches(it.code)) }
    }
}
