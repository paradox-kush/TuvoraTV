package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Twin of the KMP PlayerNextEpisodeStreamSelectionTest (upstream Desktop 8d8ee10f6, #319), on TV's
 * shape: "loading" is the all-addons search not having finished yet.
 */
class NextEpisodeStreamSelectionCoordinatorTest {

    // A "stream" is its binge group; the preferred tier only takes the current group.
    private fun coordinator(afterDelay: (List<String>) -> String? = { it.firstOrNull { s -> s == "current" } }) =
        NextEpisodeStreamSelectionCoordinator<List<String>, String>(
            selectAfterDelay = afterDelay,
            selectPreferred = { it.firstOrNull { s -> s == "current" } },
        )

    @Test
    fun `timeout keeps waiting while the matching addon is still loading`() {
        val c = coordinator()
        val partial = listOf("other")

        assertEquals(
            "unrelated streams only, search running",
            NextEpisodeStreamSelectionDecision.Waiting,
            c.onStreamsChanged(partial, searchComplete = false),
        )
        // The bug: the old flow gave up here ("respect the timeout and stop").
        assertEquals(
            "delay elapsed but the matching addon has not answered yet",
            NextEpisodeStreamSelectionDecision.Waiting,
            c.onSelectionDelayElapsed(partial, searchComplete = false),
        )
        assertEquals(
            "matching addon answers after the delay",
            NextEpisodeStreamSelectionDecision.Selected("current"),
            c.onStreamsChanged(listOf("other", "current"), searchComplete = false),
        )
    }

    @Test
    fun `timeout uses an available match without waiting for every addon`() {
        assertEquals(
            "match present at the delay",
            NextEpisodeStreamSelectionDecision.Selected("current"),
            coordinator().onSelectionDelayElapsed(listOf("current"), searchComplete = false),
        )
    }

    @Test
    fun `finished search without a match hands over to manual selection`() {
        assertEquals(
            "every addon answered, nothing matched",
            NextEpisodeStreamSelectionDecision.ManualSelection,
            coordinator().onStreamsChanged(listOf("other"), searchComplete = true),
        )
    }

    @Test
    fun `before the delay an unrelated stream is not taken while addons are still answering`() {
        val c = coordinator(afterDelay = { it.firstOrNull() })
        val partial = listOf("other")

        assertEquals(
            "only the preferred tier applies before the delay",
            NextEpisodeStreamSelectionDecision.Waiting,
            c.onStreamsChanged(partial, searchComplete = false),
        )
        assertEquals(
            "the full picker applies once the delay elapsed",
            NextEpisodeStreamSelectionDecision.Selected("other"),
            c.onSelectionDelayElapsed(partial, searchComplete = false),
        )
    }

    @Test
    fun `no results yet at the delay keeps waiting`() {
        assertEquals(
            "nothing has answered yet",
            NextEpisodeStreamSelectionDecision.Waiting,
            coordinator().onSelectionDelayElapsed(null, searchComplete = false),
        )
    }

    @Test
    fun `a zero-second delay applies the full picker to the first results`() {
        val c = coordinator(afterDelay = { it.firstOrNull() })
        c.onSelectionDelayElapsed(null, searchComplete = false)

        assertEquals(
            "delay already elapsed before any result",
            NextEpisodeStreamSelectionDecision.Selected("other"),
            c.onStreamsChanged(listOf("other"), searchComplete = false),
        )
    }
}
