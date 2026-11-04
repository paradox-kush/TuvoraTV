package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.contracts.PlaybackResumeOffer
import com.nuvio.tv.ui.screens.player.ServerResumePolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

/** Resume from the server (D3, owner decisions 2026-10-06): auto when nothing is local, an offer when something is. */
class ServerResumePolicyTest {
    @Test
    fun noOfferMeansTuvorasOwnRecordResumesAsUsual() {
        assertEquals(Decision.None, ServerResumePolicy.decide(null))
        assertEquals("a zero offer is no offer", Decision.None, ServerResumePolicy.decide(PlaybackResumeOffer(0)))
    }

    @Test
    fun withNothingLocalTheServersPlaceIsTheStartingPlaceWithoutAQuestion() {
        assertEquals(Decision.AutoResume(754_000), ServerResumePolicy.decide(PlaybackResumeOffer(754_000, autoStart = true)))
    }

    @Test
    fun withALocalRecordTheServersPositionIsOnlyEverOffered() {
        assertEquals(Decision.Offer(2_400_000), ServerResumePolicy.decide(PlaybackResumeOffer(2_400_000, autoStart = false)))
    }
}
