package com.nuvio.tv.core.iptv.stalker

import com.nuvio.tv.core.iptv.CatchUpFailure.StalkerLinkError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Four ways a Stalker archive replay fails look the same on screen unless they are told apart: the
 * session cap is fixed by closing another device, "nothing to play" by picking another programme,
 * a link fault by trying later, and a link that never starts by a fresh link. All of them carry
 * Retry, and Retry always mints a fresh create_link (the opened one is single-use).
 */
class StalkerReplayOutcomeTest {

    @Test
    fun `each portal refusal has its own kind and its own words`() {
        val limit = StalkerReplayOutcome.forOpen(StalkerLinkError.SESSION_LIMIT)
        val nothing = StalkerReplayOutcome.forOpen(StalkerLinkError.NOTHING_TO_PLAY)
        val fault = StalkerReplayOutcome.forOpen(StalkerLinkError.LINK_FAULT)

        assertEquals(StalkerReplayOutcome.Kind.SESSION_LIMIT, limit.kind)
        assertEquals(StalkerReplayOutcome.Kind.NOTHING_TO_PLAY, nothing.kind)
        assertEquals(StalkerReplayOutcome.Kind.LINK_FAULT, fault.kind)
        assertEquals("the session cap names the other device", StalkerArchiveLink.SESSION_LIMIT_MESSAGE, limit.message)
        assertEquals("no recording is not a fault", StalkerArchiveLink.NOTHING_TO_PLAY_MESSAGE, nothing.message)
        assertEquals("the archive fault says it is about this programme", StalkerArchiveLink.ARCHIVE_LINK_FAULT_MESSAGE, fault.message)
        assertEquals("all distinct", 3, setOf(limit.message, nothing.message, fault.message).size)
    }

    @Test
    fun `a startup hang is its own outcome and not a portal refusal`() {
        val hang = StalkerReplayOutcome.forStartupHang()

        assertEquals(StalkerReplayOutcome.Kind.NO_ANSWER, hang.kind)
        for (refusal in listOf(StalkerLinkError.SESSION_LIMIT, StalkerLinkError.NOTHING_TO_PLAY, StalkerLinkError.LINK_FAULT)) {
            assertNotEquals(StalkerReplayOutcome.forOpen(refusal).message, hang.message)
        }
    }

    @Test
    fun `a create_link that never answered reads as no answer and one that failed outright as an open failure`() {
        assertEquals(StalkerReplayOutcome.Kind.NO_ANSWER, StalkerReplayOutcome.forOpen(StalkerLinkError.NONE, timedOut = true).kind)
        assertEquals(StalkerReplayOutcome.Kind.OPEN_FAILED, StalkerReplayOutcome.forOpen(StalkerLinkError.NONE, timedOut = false).kind)
    }

    @Test
    fun `an engine failure on an opened link is told apart from the others`() {
        val failed = StalkerReplayOutcome.forPlaybackError()

        assertEquals(StalkerReplayOutcome.Kind.PLAYBACK_FAILED, failed.kind)
        assertNotEquals(StalkerReplayOutcome.forStartupHang().message, failed.message)
    }

    @Test
    fun `every outcome can be retried`() {
        val all = StalkerLinkError.values().flatMap { err ->
            listOf(StalkerReplayOutcome.forOpen(err, timedOut = false), StalkerReplayOutcome.forOpen(err, timedOut = true))
        } + StalkerReplayOutcome.forStartupHang() + StalkerReplayOutcome.forPlaybackError()
        assertTrue("every failure carries Retry", all.all { it.retry })
        assertTrue("and has words", all.all { it.message.isNotBlank() })
    }
}
