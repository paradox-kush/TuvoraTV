package com.nuvio.tv.core.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WP6 — the per-origin panel circuit breaker ([PanelHostGuard]), iptvnator's proven
 * HostConnectivityGuard contract as a pure policy. The clock is a `var` the tests advance by hand
 * and outcomes are reported explicitly, so every rule is pinned without any transport.
 *
 * JUnit port of the byte-identical NuvioMobile/NuvioDesktop commonTest twin.
 */
class PanelHostGuardTest {

    private var now = 0L
    private val guard = PanelHostGuard { now }

    private val url = "http://panel.example/player_api.php?username=u&password=p"

    private fun advance(ms: Long) {
        now += ms
    }

    /** Admits [u] asserting the breaker let it through; returns the token for reporting. */
    private fun allowed(u: String = url, discovery: Boolean = false): PanelAdmission.Allowed {
        val admission = guard.admit(u, discovery)
        assertTrue("expected admission for $u at t=$now", admission is PanelAdmission.Allowed)
        return admission as PanelAdmission.Allowed
    }

    private fun fastFail(u: String = url): PanelAdmission.FastFail {
        val admission = guard.admit(u)
        assertTrue("expected a fast-fail for $u at t=$now", admission is PanelAdmission.FastFail)
        return admission as PanelAdmission.FastFail
    }

    private fun failOnce(u: String = url) =
        guard.report(allowed(u), PanelRequestOutcome.CONNECTION_FAILURE)

    /** Two sequential connection failures — the documented trip threshold. */
    private fun openBreaker(u: String = url) {
        failOnce(u)
        failOnce(u)
    }

    @Test
    fun `two connection failures open the breaker for the origin`() {
        openBreaker()
        val refusal = fastFail()
        assertEquals("opens for a full window from the second failure", PanelHostGuard.OPEN_DURATION_MILLIS, refusal.retryAtMillis)
        assertEquals("http://panel.example", refusal.origin)
    }

    @Test
    fun `one failure alone does not open it`() {
        failOnce()
        allowed()
    }

    @Test
    fun `an http response never counts and clears the record`() {
        failOnce()
        // Any HTTP status — a 404 or a 502 as much as a 200 — proves the host is alive.
        guard.report(allowed(), PanelRequestOutcome.HTTP_RESPONSE)
        // The streak restarted from zero: one more failure is not enough...
        failOnce()
        allowed()
        // ...two fresh ones are.
        failOnce()
        fastFail()
    }

    @Test
    fun `a success resets the failure streak`() {
        failOnce()
        guard.report(allowed(), PanelRequestOutcome.SUCCESS)
        failOnce()
        allowed()
        failOnce()
        fastFail()
    }

    @Test
    fun `an http response while the breaker is open closes it`() {
        // Admitted before the breaker opened, still in flight while it did...
        val inFlight = allowed()
        openBreaker()
        fastFail()
        // ...and then the host answered (even a 5xx would do): the record clears immediately.
        guard.report(inFlight, PanelRequestOutcome.HTTP_RESPONSE)
        allowed()
    }

    @Test
    fun `econnreset never counts`() {
        // A reset mid-transfer happens on hosts that are very much alive: never evidence.
        repeat(3) { guard.report(allowed(), PanelRequestOutcome.CONNECTION_RESET) }
        allowed()
        // But it is inconclusive — it does NOT clear an existing streak either.
        failOnce()
        guard.report(allowed(), PanelRequestOutcome.CONNECTION_RESET)
        failOnce()
        fastFail()
    }

    @Test
    fun `the open breaker fast fails until the window passes`() {
        openBreaker()
        advance(PanelHostGuard.OPEN_DURATION_MILLIS - 1)
        assertEquals(PanelHostGuard.OPEN_DURATION_MILLIS, fastFail().retryAtMillis)
        advance(1)
        // Exactly at the boundary the half-open trial goes through.
        assertTrue("the first admission after the window is the trial", allowed().isTrial)
    }

    @Test
    fun `exactly one half open trial is allowed`() {
        openBreaker()
        advance(PanelHostGuard.OPEN_DURATION_MILLIS)
        assertTrue(allowed().isTrial)
        // While the trial is out, everyone else keeps fast-failing.
        fastFail()
        fastFail()
    }

    @Test
    fun `a successful trial closes the breaker`() {
        openBreaker()
        advance(PanelHostGuard.OPEN_DURATION_MILLIS)
        val trial = allowed()
        assertTrue(trial.isTrial)
        guard.report(trial, PanelRequestOutcome.SUCCESS)
        // Closed and cleared: ordinary admissions, and the old streak is gone.
        assertFalse(allowed().isTrial)
        failOnce()
        allowed()
    }

    @Test
    fun `a failed trial reopens the window`() {
        openBreaker()
        advance(PanelHostGuard.OPEN_DURATION_MILLIS)
        val trial = allowed()
        advance(5_000)   // the trial itself took five seconds to fail
        guard.report(trial, PanelRequestOutcome.CONNECTION_FAILURE)
        // Re-opened for a FULL window from the trial's failure, not the original trip.
        assertEquals(now + PanelHostGuard.OPEN_DURATION_MILLIS, fastFail().retryAtMillis)
        advance(PanelHostGuard.OPEN_DURATION_MILLIS - 1)
        fastFail()
        advance(1)
        assertTrue("a fresh trial after the reopened window", allowed().isTrial)
    }

    @Test
    fun `user reset clears the origin`() {
        openBreaker()
        fastFail()
        guard.reset(url)
        // A user pressing Retry must never be met with a fast-fail.
        assertFalse("reset admits plain requests and not a half-open trial", allowed().isTrial)
        // Resetting an origin the guard has never seen is a safe no-op.
        guard.reset("http://never-seen.example/player_api.php")
    }

    @Test
    fun `a straggler failure from before the reset does not reopen the breaker`() {
        val first = allowed()
        advance(1_000)
        guard.report(first, PanelRequestOutcome.CONNECTION_FAILURE)
        val second = allowed()
        val straggler = allowed()   // in flight across the reset
        advance(1_000)
        guard.report(second, PanelRequestOutcome.CONNECTION_FAILURE)
        fastFail()
        advance(500)
        guard.reset(url)
        val retry = allowed()
        // The straggler settles right after Retry cleared the record: it reports into the old
        // epoch and is discarded — otherwise it would count against the retry underneath the user.
        guard.report(straggler, PanelRequestOutcome.CONNECTION_FAILURE)
        // Even if the retry itself then fails once, that is ONE fresh failure — still closed.
        guard.report(retry, PanelRequestOutcome.CONNECTION_FAILURE)
        val fresh = allowed()
        // Post-reset evidence still works at full strength: a second fresh failure opens.
        guard.report(fresh, PanelRequestOutcome.CONNECTION_FAILURE)
        fastFail()
    }

    @Test
    fun `parallel failures that started together are one piece of evidence`() {
        val a = allowed()
        val b = allowed()
        advance(30_000)   // both hung for their full timeout — the expensive case worth protecting
        guard.report(a, PanelRequestOutcome.CONNECTION_FAILURE)
        // b started BEFORE a's failure was recorded: one network hiccup, not a streak.
        guard.report(b, PanelRequestOutcome.CONNECTION_FAILURE)
        val c = allowed()
        // A request admitted at or after the recorded failure is genuinely new evidence.
        guard.report(c, PanelRequestOutcome.CONNECTION_FAILURE)
        fastFail()
    }

    @Test
    fun `discovery probes bypass and do not count but successes clear`() {
        // Fresh origin: discovery walks candidates and expects most to fail — never evidence.
        val probeUrl = "http://probe.example/portal.php"
        repeat(5) { guard.report(allowed(probeUrl, discovery = true), PanelRequestOutcome.CONNECTION_FAILURE) }
        allowed(probeUrl)

        // Open breaker: discovery is admitted anyway (bypass)...
        openBreaker()
        val probe = allowed(url, discovery = true)
        // ...its failure changes nothing...
        guard.report(probe, PanelRequestOutcome.CONNECTION_FAILURE)
        fastFail()
        // ...but its success proves the host lives and clears the record.
        guard.report(allowed(url, discovery = true), PanelRequestOutcome.SUCCESS)
        allowed()
    }

    @Test
    fun `origins are independent`() {
        openBreaker("http://a.example/player_api.php")
        // Same origin, different path: still refused — the record is per origin, not per URL.
        fastFail("http://a.example/get.php?type=m3u")
        // A different host is untouched.
        allowed("http://b.example/player_api.php")
    }

    @Test
    fun `http and https origins are separate records`() {
        // A dead TLS listener must not fast-fail the working plain-HTTP one: routine IPTV setup.
        openBreaker("https://panel.example/player_api.php")
        allowed("http://panel.example/player_api.php")
        openBreaker("http://panel.example/player_api.php")
        fastFail("https://panel.example/player_api.php")
        fastFail("http://panel.example/player_api.php")
    }

    @Test
    fun `origin keying ignores path query case and default port`() {
        assertEquals("http://panel.example", PanelHostGuard.originOf("HTTP://Panel.Example:80/get.php?u=1#f"))
        assertEquals("https://panel.example", PanelHostGuard.originOf("https://panel.example:443/live"))
        assertEquals("https://panel.example:8443", PanelHostGuard.originOf("https://panel.example:8443/live"))
        assertEquals("http://panel.example:8080", PanelHostGuard.originOf("http://panel.example:8080"))
        assertEquals("http://[2001:db8::1]:8080", PanelHostGuard.originOf("http://[2001:DB8::1]:8080/x"))
        // Behavior, not just the helper: an origin opened under one spelling refuses them all.
        openBreaker("HTTP://Panel.Example:80/a?b=c")
        fastFail("http://panel.example/player_api.php")
    }

    @Test
    fun `credentials never reach the origin key or the fast fail message`() {
        val credUrl = "http://alice:hunter2@panel.example:8080/player_api.php"
        assertEquals("http://panel.example:8080", PanelHostGuard.originOf(credUrl))
        openBreaker(credUrl)
        val refusal = fastFail(credUrl)
        val error = refusal.toException()
        assertEquals("http://panel.example:8080", error.origin)
        assertEquals(refusal.retryAtMillis, error.retryAtMillis)
        val message = error.message ?: ""
        assertTrue("names the endpoint with its scheme", message.contains("http://panel.example:8080"))
        assertFalse("userinfo must never be logged", message.contains("alice"))
        assertFalse("credentials must never be logged", message.contains("hunter2"))
        // The wording is load-bearing: these phrases would be misread as an HTTP error or a
        // timeout by callers that classify transport failures from message text.
        assertFalse(message.contains("HTTP Error"))
        assertFalse(message.lowercase().contains("timed out"))
        assertFalse(message.lowercase().contains("timeout"))
    }

    @Test
    fun `an abandoned trial expires and a new trial is admitted`() {
        openBreaker()
        advance(PanelHostGuard.OPEN_DURATION_MILLIS)
        assertTrue(allowed().isTrial)   // admitted, then never reports back (leaked/cancelled)
        advance(PanelHostGuard.TRIAL_EXPIRY_MILLIS - 1)
        assertEquals("refused until the leaked slot expires", now + 1, fastFail().retryAtMillis)
        advance(1)
        assertTrue("the leaked slot is reclaimed instead of wedging the breaker", allowed().isTrial)
    }

    @Test
    fun `a late report from an expired trial cannot settle the replacement trial`() {
        openBreaker()
        advance(PanelHostGuard.OPEN_DURATION_MILLIS)
        val abandoned = allowed()
        advance(PanelHostGuard.TRIAL_EXPIRY_MILLIS)
        val replacement = allowed()
        assertTrue(replacement.isTrial)
        // The abandoned trial finally fails: ordinary evidence, but it must NOT free or settle
        // the replacement's slot — no third request slips through.
        guard.report(abandoned, PanelRequestOutcome.CONNECTION_FAILURE)
        fastFail()
        // The replacement still owns the slot and its success still closes the breaker.
        guard.report(replacement, PanelRequestOutcome.SUCCESS)
        allowed()
    }

    @Test
    fun `an econnreset trial releases the slot for the next trial`() {
        openBreaker()
        advance(PanelHostGuard.OPEN_DURATION_MILLIS)
        val trial = allowed()
        fastFail()
        // Inconclusive: says nothing about reachability, only releases the half-open slot.
        guard.report(trial, PanelRequestOutcome.CONNECTION_RESET)
        val next = allowed()
        assertTrue("the slot is free for the next candidate trial", next.isTrial)
        guard.report(next, PanelRequestOutcome.SUCCESS)
        assertFalse(allowed().isTrial)
    }
}
