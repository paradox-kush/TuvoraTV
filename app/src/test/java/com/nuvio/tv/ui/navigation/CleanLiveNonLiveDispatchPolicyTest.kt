package com.nuvio.tv.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the Sports live-dispatch dead-click: from the Sports hub, a match card whose content id
 * is not classified as a live channel used to hit an empty `onNonLive = {}` and vanish with no
 * feedback. This asserts the pure policy now routes that case to a user-visible message, and that
 * NO ingress origin is ever a silent no-op.
 */
class CleanLiveNonLiveDispatchPolicyTest {

    @Test
    fun `sports non-live id surfaces feedback, never a silent no-op`() {
        // Old behaviour: SPORTS handed off to an empty callback (a dead-click). Regression guard:
        // it must now produce feedback. Written to fail if SPORTS is ever mapped to Handoff again.
        val outcome = CleanLiveNonLiveDispatchPolicy.outcome(CleanLiveLaunchOrigin.SPORTS)
        assertEquals(
            "Sports non-live dispatch must surface feedback, not a silent handoff",
            CleanLiveNonLiveOutcome.Feedback(CleanLiveIngressFeedback.INVALID_REQUEST),
            outcome,
        )
    }

    @Test
    fun `content-card ingresses hand a non-live id to their native detail route`() {
        // These origins list mixed content: a non-live id is a movie/series/collection item, so
        // handing off to the caller's detail navigation is the correct, non-silent behaviour.
        for (origin in listOf(
            CleanLiveLaunchOrigin.SEARCH,
            CleanLiveLaunchOrigin.LIBRARY,
            CleanLiveLaunchOrigin.FOLDER,
            CleanLiveLaunchOrigin.CATALOG_SEE_ALL,
        )) {
            assertEquals(
                "Content-card origin $origin must hand off its non-live id",
                CleanLiveNonLiveOutcome.Handoff,
                CleanLiveNonLiveDispatchPolicy.outcome(origin),
            )
        }
    }

    @Test
    fun `every ingress origin resolves to a defined, non-silent outcome`() {
        // A Handoff is only non-silent because its caller navigates; a Feedback is inherently
        // non-silent. Either way, no origin may fall through to "do nothing" — that is the whole
        // point of centralising the decision here.
        for (origin in CleanLiveLaunchOrigin.entries) {
            val outcome = CleanLiveNonLiveDispatchPolicy.outcome(origin)
            assertTrue(
                "Origin $origin produced an unknown outcome: $outcome",
                outcome is CleanLiveNonLiveOutcome.Handoff ||
                    outcome is CleanLiveNonLiveOutcome.Feedback,
            )
        }
    }
}
