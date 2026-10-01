package com.nuvio.tv.core.iptv.match

import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/**
 * B63: one unreachable playlist (30 s connect timeout per panel call) held every TV source list
 * open until it timed out. Virtual time, fakes only — no network.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IptvSourceWaitPolicyTest {

    private val budget = IptvSourceWaitPolicy.PLAYLIST_BUDGET_MS
    private val deadHostMs = 30_000L

    private fun settled(): CompletableJob = Job().also { it.complete() }

    @Test
    fun `the source list settles at the budget instead of waiting for a dead playlist`() = runTest {
        // The shape StreamRepositoryImpl runs: addon + healthy playlist + dead playlist, all concurrent.
        val others = Job()
        val delivered = mutableListOf<String>()
        val addon = launch { delay(2_000); delivered += "addon"; others.complete() }
        val playlists = listOf("healthy" to 1_500L, "dead" to deadHostMs).map { (name, latency) ->
            async {
                IptvSourceWaitPolicy.await(others) { delay(latency); name }?.also { delivered += it }
            }
        }
        playlists.awaitAll()
        addon.join()

        assertEquals("list completes at the budget, not at the dead host's timeout", budget, currentTime)
        assertEquals("healthy sources still delivered", listOf("healthy", "addon"), delivered)
    }

    @Test
    fun `a dead playlist is abandoned at the budget and its request is cancelled`() = runTest {
        var cancelled = false
        val result = IptvSourceWaitPolicy.await(settled()) {
            try {
                delay(deadHostMs); "late"
            } catch (c: kotlinx.coroutines.CancellationException) {
                cancelled = true; throw c
            }
        }
        runCurrent()
        assertNull("abandoned playlist contributes nothing", result)
        assertEquals("waited exactly the budget", budget, currentTime)
        assertTrue("the in-flight fetch is cancelled, not left running", cancelled)
    }

    @Test
    fun `a playlist answering within the budget is always kept`() = runTest {
        val result = IptvSourceWaitPolicy.await(settled()) { delay(budget - 1); "ok" }
        assertEquals("result kept", "ok", result)
        assertEquals("no extra waiting", budget - 1, currentTime)
    }

    @Test
    fun `a slow playlist is kept while other sources are still loading anyway`() = runTest {
        val others = Job()
        launch { delay(20_000); others.complete() }
        val result = IptvSourceWaitPolicy.await(others) { delay(12_000); "slow but alive" }
        assertEquals("not dropped: the list was still waiting on an addon", "slow but alive", result)
        assertEquals("delivered when it answered", 12_000L, currentTime)
    }

    @Test
    fun `past the budget a playlist is cut the moment the last other source settles`() = runTest {
        val others = Job()
        launch { delay(10_000); others.complete() }
        val result = IptvSourceWaitPolicy.await(others) { delay(deadHostMs); "late" }
        assertNull("cut", result)
        assertEquals("cut when the addons finished", 10_000L, currentTime)
    }

    @Test
    fun `a failing playlist propagates its error to the caller`() = runTest {
        try {
            IptvSourceWaitPolicy.await<String>(settled()) { delay(100); throw IOException("refused") }
            fail("expected the fetch error")
        } catch (e: IOException) {
            assertEquals("same error", "refused", e.message)
        }
    }

    @Test
    fun `cancelling the caller cancels the fetch`() = runTest {
        var cancelled = false
        val caller = launch {
            IptvSourceWaitPolicy.await(Job()) {
                try {
                    delay(deadHostMs); "late"
                } catch (c: kotlinx.coroutines.CancellationException) {
                    cancelled = true; throw c
                }
            }
        }
        advanceTimeBy(1_000)
        caller.cancel()
        runCurrent()
        assertTrue("fetch cancelled with its caller", cancelled)
        assertFalse("caller gone", caller.isActive)
    }
}
