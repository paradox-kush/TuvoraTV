package com.nuvio.tv.playback.core

import com.nuvio.tv.playback.live.LivePlaybackOutcomePolicy
import com.nuvio.tv.playback.live.LiveTuneDeadlinePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B259: an automatic re-mint of a single-use (Stalker) link must not open a new connection inside the
 * window in which the provider still counts the old one. The viewer's Retry is never delayed.
 */
class SingleUseRemintSpacingPolicyTest {

    private val gap = SingleUseRemintSpacingPolicy.MIN_GAP_MS

    @Test
    fun `the gap is the Infomir temporary link lifetime`() {
        assertEquals("tv_tmp_link_ttl = nginx_secure_link_ttl = 5 s", 5_000L, gap)
    }

    @Test
    fun `automatic re-mint right after a single-use connection ended waits the remainder`() {
        for (trigger in listOf(ProviderResolutionTrigger.RECOVERY, ProviderResolutionTrigger.HANDOFF)) {
            assertEquals("$trigger immediately", gap, delay(trigger, endedAt = 10_000L, now = 10_000L))
            assertEquals("$trigger after 1.2 s", gap - 1_200L, delay(trigger, endedAt = 10_000L, now = 11_200L))
            assertEquals("$trigger after the gap", 0L, delay(trigger, endedAt = 10_000L, now = 10_000L + gap))
        }
    }

    @Test
    fun `the viewer's retry, a tune or a zap is never delayed`() {
        assertEquals("INITIAL", 0L, delay(ProviderResolutionTrigger.INITIAL, endedAt = 10_000L, now = 10_000L))
    }

    @Test
    fun `reusable links are never delayed`() {
        assertEquals(
            "Xtream/M3U keep the old cadence",
            0L,
            SingleUseRemintSpacingPolicy.delayBeforeResolveMs(
                ProviderResolutionTrigger.RECOVERY, previousLinkSingleUse = false, connectionEndedAtMs = 10_000L, nowMs = 10_000L,
            ),
        )
    }

    @Test
    fun `a re-mint that switches the stream identity after an auth rejection at open does not wait`() {
        assertEquals(
            "a 401/403 at open holds no connection",
            0L,
            SingleUseRemintSpacingPolicy.delayBeforeResolveMs(
                ProviderResolutionTrigger.RECOVERY,
                previousLinkSingleUse = true,
                connectionEndedAtMs = 10_000L,
                nowMs = 10_000L,
                identitySwitchAfterAuthRejection = true,
            ),
        )
        assertEquals(
            "every other re-mint keeps the spacing",
            gap,
            SingleUseRemintSpacingPolicy.delayBeforeResolveMs(
                ProviderResolutionTrigger.RECOVERY,
                previousLinkSingleUse = true,
                connectionEndedAtMs = 10_000L,
                nowMs = 10_000L,
                identitySwitchAfterAuthRejection = false,
            ),
        )
    }

    @Test
    fun `no recorded connection end means nothing to wait for`() {
        assertEquals("no anchor", 0L, delay(ProviderResolutionTrigger.RECOVERY, endedAt = null, now = 10_000L))
    }

    @Test
    fun `a clock that went backwards waits the full gap and never more`() {
        assertEquals("negative elapsed", gap, delay(ProviderResolutionTrigger.RECOVERY, endedAt = 20_000L, now = 10_000L))
    }

    @Test
    fun `spaced attempts still fit inside the tune deadline and the outcome backstop`() {
        val worstStart = LiveTuneDeadlinePolicy.worstCaseStartBudgetMs()
        assertTrue("one spaced handoff fits the tune deadline", worstStart + gap < LiveTuneDeadlinePolicy.DEADLINE_MS)
        assertTrue(
            "first attempt + 3 spaced reconnects end before the B256 backstop",
            4 * (worstStart + gap) < LivePlaybackOutcomePolicy.WAIT_BACKSTOP_MS,
        )
    }

    private fun delay(trigger: ProviderResolutionTrigger, endedAt: Long?, now: Long) =
        SingleUseRemintSpacingPolicy.delayBeforeResolveMs(trigger, previousLinkSingleUse = true, connectionEndedAtMs = endedAt, nowMs = now)
}
