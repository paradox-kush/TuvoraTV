package com.nuvio.tv.core.iptv

import com.nuvio.tv.core.iptv.stalker.StalkerProtocol
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Step 0.3b leftovers that share the failover code (twin of NuvioMobile's FailoverRetryAndErrorHostTest):
 *  (a1) the hub's Retry resets the circuit breakers of ALL of a playlist's hosts, not just main;
 *  (a2) the hub error card names the server that actually failed last.
 */
class FailoverRetryAndErrorHostTest {

    private val acc = XtreamAccount(
        id = "retry|u", name = "P", baseUrl = "http://retry-main.test:80", username = "u", password = "p",
        backupUrls = listOf("http://retry-b1.test:8080", "http://retry-b2.test"),
    )

    private val failover = PlaylistServerFailover(InMemoryServerFailoverStateStore(), { 5_000L }, { 1 })

    private inline fun <reified T : Throwable> assertThrows(block: () -> Unit): T {
        try {
            block()
        } catch (t: Throwable) {
            if (t is T) return t
            throw AssertionError("expected ${T::class.simpleName}, got $t", t)
        }
        fail("expected ${T::class.simpleName}")
        throw IllegalStateException()
    }

    /** Two counted failures, the second admitted after the first was recorded (siblings are not a streak). */
    private suspend fun openBreaker(url: String) {
        repeat(2) {
            val a = IptvPanelGuard.guard.admit(url)
            if (a is PanelAdmission.Allowed) IptvPanelGuard.guard.report(a, PanelRequestOutcome.CONNECTION_FAILURE)
            delay(3)   // the guard runs on a real monotonic clock
        }
    }

    @Test
    fun `retry clears the breaker of main and of every backup`() = runBlocking {
        val urls = IptvPanelGuard.panelOriginUrlsOf(acc)
        assertEquals(listOf("http://retry-main.test:80", "http://retry-b1.test:8080", "http://retry-b2.test"), urls)
        for (u in urls) openBreaker(u)
        for (u in urls) assertTrue("precondition: $u's breaker is open", IptvPanelGuard.guard.admit(u) is PanelAdmission.FastFail)
        IptvPanelGuard.resetForAccount(acc)
        for (u in urls) assertTrue("$u is admitted after Retry", IptvPanelGuard.guard.admit(u) is PanelAdmission.Allowed)
    }

    @Test
    fun `a stalker playlist resets the normalized portal bases of every portal`() {
        val stalker = acc.copy(sourceType = XtreamAccount.SOURCE_STALKER, portalUrl = acc.baseUrl, macAddress = "00:1A:79:00:00:01")
        val urls = IptvPanelGuard.panelOriginUrlsOf(stalker)
        assertEquals(3, urls.size)
        assertEquals(StalkerProtocol.normalizePortalBase("http://retry-b1.test:8080"), urls[1])
    }

    @Test
    fun `an m3u playlist has no panel hosts to reset`() {
        assertEquals(
            emptyList<String>(),
            IptvPanelGuard.panelOriginUrlsOf(acc.copy(sourceType = XtreamAccount.SOURCE_URL, backupUrls = listOf("http://x.test/a.m3u"))),
        )
    }

    @Test
    fun `with every server down the card names the main server and the next success clears it`() = runBlocking {
        assertThrows<HttpStatusException> {
            runBlocking { failover.run(acc) { a -> throw HttpStatusException(503, a.baseUrl) } }
        }
        assertEquals("http://retry-main.test:80", failover.lastFailedServerUrl(acc))
        failover.run(acc) { "ok" }
        assertNull("a success clears the failed host", failover.lastFailedServerUrl(acc))
    }

    @Test
    fun `a definitive refusal from a backup names that backup and not main`() = runTest {
        val failover = PlaylistServerFailover(InMemoryServerFailoverStateStore(), { 5_000L }, { 1 }, raceClock = { testScheduler.currentTime })
        assertThrows<FailoverAuthRejectedException> {
            failover.run(
                acc,
                probe = { a -> if (a.baseUrl == "http://retry-b1.test:8080") throw FailoverAuthRejectedException("auth=0") else delay(10) },
                attempt = { a -> delay(60_000); throw HttpStatusException(504, a.baseUrl) },   // main hangs
            )
        }
        assertEquals("http://retry-b1.test:8080", failover.lastFailedServerUrl(acc))
        // The hub turns that into the breadcrumb host.
        assertEquals("http://retry-b1.test:8080", IptvPanelGuard.panelOriginUrlOf(acc, failover.lastFailedServerUrl(acc)!!))
    }

    @Test
    fun `editing the server list forgets which server failed`() = runBlocking {
        assertThrows<HttpStatusException> { runBlocking { failover.run(acc) { a -> throw HttpStatusException(503, a.baseUrl) } } }
        failover.reset(acc.id)
        assertNull(failover.lastFailedServerUrl(acc))
    }
}
