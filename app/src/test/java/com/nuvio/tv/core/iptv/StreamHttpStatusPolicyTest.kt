package com.nuvio.tv.core.iptv

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * TV twin of the mobile/desktop policy test. NOTE the assertion order: JUnit is
 * `assertEquals(message, expected, actual)` — the reverse of commonTest's kotlin.test.
 *
 * Regression cover: a live VOD stream got HTTP 456 from a provider WAF and the player surfaced the
 * raw `HTTP 456 <none> [ERROR_CODE_IO_BAD_HTTP_STATUS]` with no hint at all (456 fell through the
 * status `when`). It now classifies as PROVIDER_FIREWALL so the viewer is told to set a UA.
 */
class StreamHttpStatusPolicyTest {

    @Test
    fun `456 is a provider firewall block, not an unexplained code`() {
        assertEquals(
            "the body-less anti-bot refusal gets its own actionable hint",
            StreamHttpStatusHint.PROVIDER_FIREWALL,
            StreamHttpStatusPolicy.hint(456),
        )
    }

    @Test
    fun `the ordinary provider codes keep their existing hints`() {
        assertEquals("400 blocked", StreamHttpStatusHint.BLOCKED, StreamHttpStatusPolicy.hint(400))
        assertEquals("403 blocked", StreamHttpStatusHint.BLOCKED, StreamHttpStatusPolicy.hint(403))
        assertEquals("401 expired", StreamHttpStatusHint.EXPIRED, StreamHttpStatusPolicy.hint(401))
        assertEquals("410 expired", StreamHttpStatusHint.EXPIRED, StreamHttpStatusPolicy.hint(410))
        assertEquals("404 removed", StreamHttpStatusHint.REMOVED, StreamHttpStatusPolicy.hint(404))
        assertEquals("429 rate limited", StreamHttpStatusHint.RATE_LIMITED, StreamHttpStatusPolicy.hint(429))
        assertEquals("503 unavailable", StreamHttpStatusHint.UNAVAILABLE, StreamHttpStatusPolicy.hint(503))
    }

    @Test
    fun `an unmapped code carries no hint`() {
        assertEquals("418 has no specific advice", StreamHttpStatusHint.NONE, StreamHttpStatusPolicy.hint(418))
        assertEquals("200 is not an error path", StreamHttpStatusHint.NONE, StreamHttpStatusPolicy.hint(200))
    }
}
