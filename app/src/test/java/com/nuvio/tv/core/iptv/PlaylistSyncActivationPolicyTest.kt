package com.nuvio.tv.core.iptv

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * B24 — TV twin of the production activation policy test. JUnit arg order: assertEquals(msg, expected,
 * actual). The load-bearing case is that an adopted profile is never downgraded to v1 when v2 is off.
 */
class PlaylistSyncActivationPolicyTest {

    private fun act(rollout: PlaylistV2Rollout, debugLocal: Boolean, adopted: Boolean) =
        PlaylistSyncActivationPolicy.activation(rollout, debugLocal, adopted)

    @Test
    fun enabledActivatesV2ForAnyProfile() {
        assertEquals("enabled + fresh", PlaylistSyncActivation.V2_ACTIVE, act(PlaylistV2Rollout.ENABLED, false, false))
        assertEquals("enabled + adopted", PlaylistSyncActivation.V2_ACTIVE, act(PlaylistV2Rollout.ENABLED, false, true))
    }

    @Test
    fun debugOnlyActivatesV2OnlyUnderDebugLocal() {
        assertEquals("debug local", PlaylistSyncActivation.V2_ACTIVE, act(PlaylistV2Rollout.DEBUG_ONLY, true, false))
        assertEquals("release", PlaylistSyncActivation.V1_LEGACY, act(PlaylistV2Rollout.DEBUG_ONLY, false, false))
    }

    @Test
    fun unadoptedProfileUsesV1WhenOff() {
        assertEquals(PlaylistSyncActivation.V1_LEGACY, act(PlaylistV2Rollout.DISABLED, false, false))
    }

    @Test
    fun adoptedProfileIsPausedNotDowngradedWhenDisabled() {
        assertEquals(PlaylistSyncActivation.V2_PAUSED, act(PlaylistV2Rollout.DISABLED, false, true))
    }

    @Test
    fun adoptedProfileIsPausedNotDowngradedUnderDebugOnlyRelease() {
        assertEquals(PlaylistSyncActivation.V2_PAUSED, act(PlaylistV2Rollout.DEBUG_ONLY, false, true))
    }

    @Test
    fun rolloutParsingMapsAliasesAndFallsBack() {
        assertEquals(PlaylistV2Rollout.ENABLED, PlaylistV2Rollout.parse("enabled"))
        assertEquals(PlaylistV2Rollout.DISABLED, PlaylistV2Rollout.parse("OFF"))
        assertEquals(PlaylistV2Rollout.DEBUG_ONLY, PlaylistV2Rollout.parse(null))
        assertEquals(PlaylistV2Rollout.DISABLED, PlaylistV2Rollout.parse("nonsense", PlaylistV2Rollout.DISABLED))
    }
}
