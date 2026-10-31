package com.nuvio.tv.core.iptv.refresh

import com.nuvio.tv.core.iptv.LoadStatus
import com.nuvio.tv.core.iptv.ManualRefreshResult
import com.nuvio.tv.core.iptv.XtreamAccount
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** UX179 on TV — "Refresh now" ends honestly: only REFRESHED is success, a failure is Failed, one refresh at a time. */
class IptvManualRefreshControllerTest {
    private val account = XtreamAccount(id = "acc1", name = "Home", baseUrl = "http://panel.example:8080", username = "u", password = "p")

    private fun controller(
        lastRefreshAt: suspend (XtreamAccount) -> Long? = { null },
        refresh: suspend (XtreamAccount) -> ManualRefreshResult,
    ) = IptvManualRefreshController(CoroutineScope(SupervisorJob() + Dispatchers.Default), refresh, lastRefreshAt)

    private suspend fun IptvManualRefreshController.settled(): ManualRefreshUiState =
        withTimeout(5_000) { state.first { it.load !is LoadStatus.Loading && it.load != LoadStatus.Idle } }

    @Test
    fun `a refresh that landed is Loaded`(): Unit = runBlocking {
        val c = controller { ManualRefreshResult.REFRESHED }
        c.start(account)
        val done = c.settled()
        assertEquals("loaded", LoadStatus.Loaded, done.load)
        assertEquals("result", ManualRefreshResult.REFRESHED, done.result)
    }

    @Test
    fun `a failed or partial refresh is Failed, never done`(): Unit = runBlocking {
        for (bad in listOf(ManualRefreshResult.FAILED, ManualRefreshResult.PARTIAL)) {
            val c = controller { bad }
            c.start(account)
            val done = c.settled()
            assertTrue("failed for $bad: ${done.load}", done.load is LoadStatus.Failed)
            assertEquals("result", bad, done.result)
        }
    }

    @Test
    fun `an engine that throws ends Failed`(): Unit = runBlocking {
        val c = controller { error("provider down") }
        c.start(account)
        val done = c.settled()
        assertTrue("failed: ${done.load}", done.load is LoadStatus.Failed)
        assertEquals("result", ManualRefreshResult.FAILED, done.result)
    }

    @Test
    fun `a second press while one is running does not start another`(): Unit = runBlocking {
        var calls = 0
        val release = CompletableDeferred<Unit>()
        val c = controller { calls++; release.await(); ManualRefreshResult.REFRESHED }
        c.start(account); c.start(account); c.start(account)
        delay(100)
        assertTrue("still running", c.state.value.refreshing)
        release.complete(Unit)
        c.settled()
        assertEquals("one call", 1, calls)
    }

    @Test
    fun `a slow last-updated read cannot overwrite the result of a refresh that already finished`(): Unit = runBlocking {
        val release = CompletableDeferred<Unit>()
        var reads = 0
        val c = controller(lastRefreshAt = { if (reads++ == 0) { release.await(); 1L } else 999L }) { ManualRefreshResult.REFRESHED }
        c.load(account)
        c.start(account)
        val done = c.settled()
        assertEquals("fresh time", 999L, done.lastRefreshAtMs)
        release.complete(Unit)
        delay(150)
        assertEquals("the stale read must not win", 999L, c.state.value.lastRefreshAtMs)
    }
}
