package com.nuvio.tv.updater

import com.nuvio.tv.updater.UpdaterHttpErrorPolicy.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** B175: a GitHub 403 must say whether it is a rate limit (and when it lifts) or a plain refusal. */
class UpdaterHttpErrorPolicyTest {
    private val now = 1_000_000L

    private fun classify(status: Int, headers: Map<String, String> = emptyMap(), body: String = "") =
        UpdaterHttpErrorPolicy.classify(status, headers, body, now)

    @Test
    fun `403 with remaining zero is rate limited until the reset header`() {
        val outcome = classify(
            403,
            mapOf("x-ratelimit-remaining" to "0", "x-ratelimit-reset" to "${now + 1800}"),
            """{"message":"API rate limit exceeded for 1.2.3.4."}""",
        )
        assertEquals("reset header wins", Outcome.RateLimited(now + 1800), outcome)
    }

    @Test
    fun `header names are matched case-insensitively`() {
        val outcome = classify(403, mapOf("X-RateLimit-Remaining" to "0", "X-RateLimit-Reset" to "${now + 600}"))
        assertEquals("okhttp may capitalise", Outcome.RateLimited(now + 600), outcome)
    }

    @Test
    fun `429 with retry-after waits that many seconds`() {
        assertEquals("retry-after", Outcome.RateLimited(now + 120), classify(429, mapOf("retry-after" to "120")))
    }

    @Test
    fun `retry-after wins over the reset header on a secondary limit`() {
        val outcome = classify(
            403,
            mapOf("retry-after" to "300", "x-ratelimit-remaining" to "0", "x-ratelimit-reset" to "${now + 3000}"),
        )
        assertEquals("secondary limit", Outcome.RateLimited(now + 300), outcome)
    }

    @Test
    fun `403 with a rate limit message but no headers backs off by the default`() {
        val outcome = classify(403, body = """{"message":"You have exceeded a secondary rate limit."}""")
        assertEquals(
            "unknown reset",
            Outcome.RateLimited(now + UpdaterHttpErrorPolicy.UNKNOWN_RESET_BACKOFF_SECONDS),
            outcome,
        )
    }

    @Test
    fun `403 without any rate limit evidence is forbidden not limited`() {
        assertEquals("bare 403", Outcome.Forbidden, classify(403))
        assertEquals(
            "quota left, so this is not the limit",
            Outcome.Forbidden,
            classify(403, mapOf("x-ratelimit-remaining" to "42"), """{"message":"Forbidden"}"""),
        )
    }

    @Test
    fun `a reset already in the past still waits the minimum`() {
        val outcome = classify(403, mapOf("x-ratelimit-remaining" to "0", "x-ratelimit-reset" to "${now - 500}"))
        assertEquals("clock skew", Outcome.RateLimited(now + UpdaterHttpErrorPolicy.MIN_BACKOFF_SECONDS), outcome)
    }

    @Test
    fun `other statuses are not rate limit decisions`() {
        assertEquals("500", Outcome.Other(500), classify(500))
        assertEquals("301", Outcome.Other(301), classify(301))
    }

    @Test
    fun `minutes until reset round up and never read zero`() {
        assertEquals("1s", 1, UpdaterHttpErrorPolicy.minutesUntil(now + 1, now))
        assertEquals("60s", 1, UpdaterHttpErrorPolicy.minutesUntil(now + 60, now))
        assertEquals("61s", 2, UpdaterHttpErrorPolicy.minutesUntil(now + 61, now))
        assertEquals("past", 1, UpdaterHttpErrorPolicy.minutesUntil(now - 5, now))
    }

    @Test
    fun `checks are blocked until the known reset then allowed again`() {
        assertTrue("never limited", UpdaterHttpErrorPolicy.mayCheck(null, now))
        assertFalse("still limited", UpdaterHttpErrorPolicy.mayCheck(now + 10, now))
        assertTrue("the reset instant lifts the block", UpdaterHttpErrorPolicy.mayCheck(now + 10, now + 10))
    }
}
