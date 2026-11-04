package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Test

class CompletedEpisodeSavePolicyTest {

    @Test
    fun `in-progress save before completion saves progress`() {
        assertEquals(
            "a normal mid-episode save persists progress",
            ProgressSaveAction.SAVE_PROGRESS,
            CompletedEpisodeSavePolicy.decide(isCompleted = false, alreadyMarkedCompleted = false),
        )
    }

    @Test
    fun `first completed save marks completed`() {
        assertEquals(
            "the save that crosses the completion threshold marks the episode completed",
            ProgressSaveAction.MARK_COMPLETED,
            CompletedEpisodeSavePolicy.decide(isCompleted = true, alreadyMarkedCompleted = false),
        )
    }

    @Test
    fun `stale low-progress save after completion is skipped`() {
        // Regression: after natural completion the player reported duration=0, which becomes a 5%
        // progress value; saving it overwrote the completed entry and pushed 5% to remote.
        assertEquals(
            "a stale in-progress save must not overwrite a completed episode",
            ProgressSaveAction.SKIP,
            CompletedEpisodeSavePolicy.decide(isCompleted = false, alreadyMarkedCompleted = true),
        )
    }

    @Test
    fun `repeat completed save after completion is skipped`() {
        assertEquals(
            "completion is recorded once per session",
            ProgressSaveAction.SKIP,
            CompletedEpisodeSavePolicy.decide(isCompleted = true, alreadyMarkedCompleted = true),
        )
    }
}
