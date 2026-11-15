package com.nuvio.tv.ui.screens.iptv

import com.nuvio.tv.core.iptv.BoundedLoad
import com.nuvio.tv.core.iptv.LoadStatus
import com.nuvio.tv.core.iptv.LoadSurface
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * UX275: a past window whose catch-up history was not loaded (or failed) said "No guide data for this time",
 * which reads as "nothing exists". It now says Loading, or Failed with Retry (bounded-load rule).
 */
class GuideHistoryStatusPolicyTest {
    private val loading = BoundedLoad.begin(LoadSurface.GUIDE_HISTORY, nowMs = 0L)

    @Test
    fun `a past window with no programmes while history loads says loading`() {
        assertEquals(GuideHistoryStatusPolicy.View.LOADING,
            GuideHistoryStatusPolicy.view(awayFromNow = true, hasProgrammesInWindow = false, load = loading))
    }

    @Test
    fun `a failed history fetch is Failed with Retry and never the empty text`() {
        assertEquals(GuideHistoryStatusPolicy.View.FAILED,
            GuideHistoryStatusPolicy.view(awayFromNow = true, hasProgrammesInWindow = false, load = LoadStatus.Failed(timedOut = false)))
        assertEquals(GuideHistoryStatusPolicy.View.FAILED,
            GuideHistoryStatusPolicy.view(awayFromNow = true, hasProgrammesInWindow = false, load = LoadStatus.Failed(timedOut = true)))
    }

    @Test
    fun `a loaded history that holds nothing is plainly empty`() {
        assertEquals(GuideHistoryStatusPolicy.View.NONE,
            GuideHistoryStatusPolicy.view(awayFromNow = true, hasProgrammesInWindow = false, load = LoadStatus.Loaded))
        assertEquals(GuideHistoryStatusPolicy.View.NONE,
            GuideHistoryStatusPolicy.view(awayFromNow = true, hasProgrammesInWindow = false, load = LoadStatus.Idle))
    }

    @Test
    fun `programmes in the window or the live window never show a history state`() {
        assertEquals(GuideHistoryStatusPolicy.View.NONE,
            GuideHistoryStatusPolicy.view(awayFromNow = true, hasProgrammesInWindow = true, load = LoadStatus.Failed(false)))
        assertEquals(GuideHistoryStatusPolicy.View.NONE,
            GuideHistoryStatusPolicy.view(awayFromNow = false, hasProgrammesInWindow = false, load = LoadStatus.Failed(false)))
    }

    @Test
    fun `the guide says why in the info pane`() {
        assertEquals(GuideEmptyMessagePolicy.Kind.HISTORY_LOADING,
            GuideEmptyMessagePolicy.kind(true, "g", false, GuideHistoryStatusPolicy.View.LOADING))
        assertEquals(GuideEmptyMessagePolicy.Kind.HISTORY_FAILED,
            GuideEmptyMessagePolicy.kind(true, "g", false, GuideHistoryStatusPolicy.View.FAILED))
        assertEquals(GuideEmptyMessagePolicy.Kind.EMPTY_WINDOW,
            GuideEmptyMessagePolicy.kind(true, "g", false, GuideHistoryStatusPolicy.View.NONE))
    }

    @Test
    fun `a history load that never returns ends Failed at its deadline`() {
        assertEquals(LoadStatus.Failed(timedOut = true), BoundedLoad.effectiveAt(loading, loading.deadlineAtMs))
    }
}
