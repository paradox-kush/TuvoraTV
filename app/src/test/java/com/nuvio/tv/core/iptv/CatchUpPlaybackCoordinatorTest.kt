package com.nuvio.tv.core.iptv

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B117 regression: "Start over" asked a non-UTC panel for the wrong hour.
 *
 * The guide places programmes by their true UTC instant; an Xtream panel reads the replay `start`
 * string in ITS OWN wall clock. A Europe/London panel in BST (+1h) asked for "19-00" serves what
 * aired at 18:00 UTC — the previous programme, i.e. "a different show".
 */
class CatchUpPlaybackCoordinatorTest {

    // 2026-10-03 19:00:00 UTC — BST, so the panel's wall clock reads 20:00.
    private val programmeStart = 1_791_054_000_000L
    private val programmeEnd = programmeStart + 60 * 60_000L
    private val now = programmeStart + 20 * 60_000L

    @Test
    fun `start over on a +1h panel asks for the panel-local start`() = runTest {
        val coordinator = coordinator(panelOffsetMs = 60 * 60_000L)

        val session = coordinator.begin(
            account = account(),
            channelContentId = "xtream:acc:live:7",
            channelName = "BBC Two SD",
            streamId = 7,
            programme = CatchUpPlaybackCoordinator.Programme("Show", programmeStart, programmeEnd),
            nowMs = now,
        )

        assertNotNull("an Xtream archive channel must mint a replay", session)
        assertTrue(
            "the start must be the panel's 20:00, not UTC 19:00: ${session!!.url}",
            session.url.contains("/2026-10-03:20-00/"),
        )
    }

    @Test
    fun `a UTC panel keeps the byte-identical UTC start`() = runTest {
        val session = coordinator(panelOffsetMs = null).begin(
            account = account(),
            channelContentId = "xtream:acc:live:7",
            channelName = "BBC Two SD",
            streamId = 7,
            programme = CatchUpPlaybackCoordinator.Programme("Show", programmeStart, programmeEnd),
            nowMs = now,
        )!!
        assertEquals(
            "unmeasured panels keep the URL Tuvora always sent",
            "http://panel.invalid/timeshift/user/pass/60/2026-10-03:19-00/7.ts",
            session.url,
        )
    }

    @Test
    fun `the manual correction still corrects a wrong panel clock`() = runTest {
        val session = coordinator(panelOffsetMs = 60 * 60_000L).begin(
            account = account(catchUpCorrectionMinutes = -60),
            channelContentId = "xtream:acc:live:7",
            channelName = "BBC Two SD",
            streamId = 7,
            programme = CatchUpPlaybackCoordinator.Programme("Show", programmeStart, programmeEnd),
            nowMs = now,
        )!!
        assertTrue("measured +1h minus the user's -1h is UTC: ${session.url}", session.url.contains("/2026-10-03:19-00/"))
    }

    private fun coordinator(panelOffsetMs: Long?) = CatchUpPlaybackCoordinator(
        winners = CatchUpWinnerStore(object : CatchUpWinnerStore.Persistence {
            private var stored: Map<String, String> = emptyMap()
            override fun load(): Map<String, String> = stored
            override fun save(entries: Map<String, String>) { stored = entries.toMap() }
        }),
        panelClock = PanelClockSource { panelOffsetMs },
    )

    private fun account(catchUpCorrectionMinutes: Int = 0) = XtreamAccount(
        id = "acc",
        name = "Panel",
        baseUrl = "http://panel.invalid",
        username = "user",
        password = "pass",
        catchUpCorrectionMinutes = catchUpCorrectionMinutes,
    )
}
