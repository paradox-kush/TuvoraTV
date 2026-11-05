package com.nuvio.tv.core.iptv

import androidx.media3.common.PlaybackException
import com.nuvio.tv.core.iptv.CatchUpFailure.StalkerLinkError
import com.nuvio.tv.core.iptv.stalker.StalkerArchiveSource
import com.nuvio.tv.core.iptv.stalker.StalkerReplayOutcome
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A Stalker archive replay is ONE create_link from the portal's programme id, played as a single-use
 * session: no dialect walk, no engine re-open, no cached URL. Retry mints again.
 */
class CatchUpPlaybackCoordinatorStalkerTest {

    private val start = 1_791_054_000_000L
    private val programme = CatchUpPlaybackCoordinator.Programme("News", start, start + 3_600_000L)
    private val now = start + 2 * 3_600_000L
    private val account = XtreamAccount(
        id = "portal", name = "Portal", baseUrl = "", username = "", password = "",
        sourceType = XtreamAccount.SOURCE_STALKER, portalUrl = "http://portal.invalid", macAddress = "00:1A:79:00:00:01",
    )

    private class Memory : CatchUpWinnerStore.Persistence {
        var stored: Map<String, String> = emptyMap()
        override fun load(): Map<String, String> = stored
        override fun save(entries: Map<String, String>) { stored = entries.toMap() }
    }

    /** Mints `link-1`, `link-2`, ... and records what it was asked for; or [refusal]. */
    private class FakePortal(var refusal: StalkerLinkError? = null) : StalkerArchiveSource {
        val asked = mutableListOf<String>()
        override suspend fun mint(account: XtreamAccount, programmeId: String): StalkerArchiveSource.Minted {
            asked += programmeId
            return refusal?.let { StalkerArchiveSource.Minted.Refused(it) }
                ?: StalkerArchiveSource.Minted.Link("http://store.invalid/play/${asked.size}")
        }
    }

    private fun coordinator(portal: StalkerArchiveSource) =
        CatchUpPlaybackCoordinator(winners = CatchUpWinnerStore(Memory()), stalkerArchive = portal)

    private suspend fun CatchUpPlaybackCoordinator.openNews(id: String? = "9001") = open(
        account = account, channelContentId = "stalker:portal:live:12", channelName = "One", streamId = 12,
        programme = programme, nowMs = now, stalkerProgrammeId = id,
    )

    @Test
    fun `a programme id opens one single-use session from one create_link`() = runTest {
        val portal = FakePortal()
        val c = coordinator(portal)

        val opened = c.openNews() as CatchUpPlaybackCoordinator.OpenOutcome.Opened

        assertEquals("exactly one mint, for the portal's own id", listOf("9001"), portal.asked)
        assertEquals("http://store.invalid/play/1", opened.session.url)
        assertTrue(opened.session.singleUse)
        assertTrue(c.isSingleUse(opened.session.contentId))
    }

    @Test
    fun `no programme id is no recording and asks the portal nothing`() = runTest {
        val portal = FakePortal()
        val c = coordinator(portal)

        assertEquals(CatchUpPlaybackCoordinator.OpenOutcome.Unavailable, c.openNews(id = null))
        assertEquals(CatchUpPlaybackCoordinator.OpenOutcome.Unavailable, c.openNews(id = "  "))
        assertTrue(portal.asked.isEmpty())
    }

    @Test
    fun `each portal refusal comes back as its own outcome`() = runTest {
        for ((refusal, kind) in listOf(
            StalkerLinkError.SESSION_LIMIT to StalkerReplayOutcome.Kind.SESSION_LIMIT,
            StalkerLinkError.NOTHING_TO_PLAY to StalkerReplayOutcome.Kind.NOTHING_TO_PLAY,
            StalkerLinkError.LINK_FAULT to StalkerReplayOutcome.Kind.LINK_FAULT,
            StalkerLinkError.NONE to StalkerReplayOutcome.Kind.OPEN_FAILED,
        )) {
            val outcome = coordinator(FakePortal(refusal)).openNews()
            assertTrue("$refusal: $outcome", outcome is CatchUpPlaybackCoordinator.OpenOutcome.Refused)
            assertEquals(kind, (outcome as CatchUpPlaybackCoordinator.OpenOutcome.Refused).failure.kind)
            assertTrue(outcome.failure.retry)
        }
    }

    @Test
    fun `a failed single-use replay is never walked to another url`() = runTest {
        val portal = FakePortal()
        val c = coordinator(portal)
        val s = (c.openNews() as CatchUpPlaybackCoordinator.OpenOutcome.Opened).session

        assertNull("no dialect to try", c.onFailed(s.contentId, PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS))
        assertEquals("and nothing was asked of the portal", 1, portal.asked.size)
    }

    @Test
    fun `retry mints a fresh link and never hands back the spent one`() = runTest {
        val portal = FakePortal()
        val c = coordinator(portal)
        val s = (c.openNews() as CatchUpPlaybackCoordinator.OpenOutcome.Opened).session
        c.onPlayed(s.contentId)

        val again = c.reopenStalker(s.contentId) as CatchUpPlaybackCoordinator.Reopened.Link

        assertEquals(listOf("9001", "9001"), portal.asked)
        assertNotEquals("a different link", s.url, again.url)
        assertEquals("http://store.invalid/play/2", again.url)
        assertEquals("the session carries the new link", again.url, c.sessionFor(s.contentId)?.url)
    }

    @Test
    fun `a refused retry reports the refusal and a released replay cannot be retried`() = runTest {
        val portal = FakePortal()
        val c = coordinator(portal)
        val s = (c.openNews() as CatchUpPlaybackCoordinator.OpenOutcome.Opened).session

        portal.refusal = StalkerLinkError.SESSION_LIMIT
        val refused = c.reopenStalker(s.contentId) as CatchUpPlaybackCoordinator.Reopened.Refused
        assertEquals(StalkerReplayOutcome.Kind.SESSION_LIMIT, refused.failure.kind)

        c.release(s.contentId)
        assertFalse(c.isSingleUse(s.contentId))
        assertNull(c.reopenStalker(s.contentId))
    }

    @Test
    fun `playing a single-use replay does not touch the dialect memory`() = runTest {
        val memory = Memory()
        val c = CatchUpPlaybackCoordinator(winners = CatchUpWinnerStore(memory), stalkerArchive = FakePortal())
        val s = (c.openNews() as CatchUpPlaybackCoordinator.OpenOutcome.Opened).session

        c.onPlayed(s.contentId)

        assertTrue("nothing remembered", memory.stored.isEmpty())
        assertNotNull("but the mint stays held for Retry", c.reopenStalker(s.contentId))
    }

    @Test
    fun `an xtream or m3u account never takes the stalker route`() = runTest {
        val c = coordinator(FakePortal())
        assertTrue(c.supportsStalkerArchive(account))
        assertFalse(c.supportsStalkerArchive(account.copy(sourceType = XtreamAccount.SOURCE_XTREAM)))
        assertFalse(c.supportsStalkerArchive(account.copy(sourceType = XtreamAccount.SOURCE_URL)))
    }
}
