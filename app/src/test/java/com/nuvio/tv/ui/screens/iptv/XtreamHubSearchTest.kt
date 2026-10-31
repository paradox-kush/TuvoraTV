package com.nuvio.tv.ui.screens.iptv

import com.nuvio.tv.core.iptv.LoadStatus
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** F48 on TV — the hub's Movies/Series search inside one playlist, run on submit (OK on Search opens the keyboard). */
class XtreamHubSearchTest {
    private fun item(name: String) = XtreamHubItem(
        cardId = "x:$name", name = name, poster = null, isLive = false, contentId = "x:$name", streamUrl = null,
    )

    private fun controller(
        calls: MutableList<Triple<String, String, String>>,
        search: suspend (String, String, String) -> List<XtreamHubItem>,
    ) = XtreamHubSearchController(CoroutineScope(SupervisorJob() + Dispatchers.Default)) { id, type, q ->
        calls += Triple(id, type, q); search(id, type, q)
    }

    private suspend fun XtreamHubSearchController.settled(): HubSearchUiState =
        withTimeout(5_000) { state.first { it.open && it.load !is LoadStatus.Loading && it.load != LoadStatus.Idle } }

    @Test
    fun `only the movie and series sections search`() {
        assertEquals("movies section", XtreamAccount.TYPE_MOVIES, IptvHubSearchPolicy.contentTypeFor(XtreamSection.MOVIES))
        assertEquals("series section", XtreamAccount.TYPE_SERIES, IptvHubSearchPolicy.contentTypeFor(XtreamSection.SERIES))
        assertNull("live has its own guide search", IptvHubSearchPolicy.contentTypeFor(XtreamSection.LIVE))
    }

    @Test
    fun `a query under two letters makes no request and spaces are trimmed`() {
        assertNull("empty", IptvHubSearchPolicy.request(XtreamSection.MOVIES, ""))
        assertNull("one letter", IptvHubSearchPolicy.request(XtreamSection.MOVIES, " a "))
        assertEquals("trimmed", "ab", IptvHubSearchPolicy.request(XtreamSection.MOVIES, "  ab "))
        assertNull("live", IptvHubSearchPolicy.request(XtreamSection.LIVE, "bbc one"))
    }

    @Test
    fun `a short submit never calls the provider and does not open the results`(): Unit = runBlocking {
        val calls = mutableListOf<Triple<String, String, String>>()
        val c = controller(calls) { _, _, _ -> emptyList() }
        c.submit("acc1", XtreamSection.MOVIES, "a")
        delay(100)
        assertTrue("no request", calls.isEmpty())
        assertEquals("not open", false, c.state.value.open)
    }

    @Test
    fun `a submit asks the selected playlist once and lands as Loaded`(): Unit = runBlocking {
        val calls = mutableListOf<Triple<String, String, String>>()
        val c = controller(calls) { _, _, q -> listOf(item(q)) }
        c.submit("acc1", XtreamSection.SERIES, " matrix ")
        val done = c.settled()
        assertEquals("one scoped request", listOf(Triple("acc1", XtreamAccount.TYPE_SERIES, "matrix")), calls)
        assertEquals("loaded", LoadStatus.Loaded, done.load)
        assertEquals("hits", listOf("matrix"), done.items.map { it.name })
    }

    @Test
    fun `a slow answer for an old submit cannot overwrite the newer one`(): Unit = runBlocking {
        val calls = mutableListOf<Triple<String, String, String>>()
        val firstStarted = CompletableDeferred<Unit>()
        val c = controller(calls) { _, _, q ->
            if (q == "old") { firstStarted.complete(Unit); delay(400); listOf(item("STALE")) } else listOf(item("fresh"))
        }
        c.submit("acc1", XtreamSection.MOVIES, "old")
        firstStarted.await()
        c.submit("acc1", XtreamSection.MOVIES, "new")
        c.settled()
        delay(500)
        assertEquals("newer answer kept", listOf("fresh"), c.state.value.items.map { it.name })
    }

    @Test
    fun `no hits is Empty and a failed search is Failed - never Empty`(): Unit = runBlocking {
        val calls = mutableListOf<Triple<String, String, String>>()
        val empty = controller(calls) { _, _, _ -> emptyList() }
        empty.submit("acc1", XtreamSection.MOVIES, "zzzz")
        assertEquals("empty", LoadStatus.Empty, empty.settled().load)

        val failing = controller(calls) { _, _, _ -> error("portal down") }
        failing.submit("acc1", XtreamSection.MOVIES, "matrix")
        val failed = failing.settled()
        assertTrue("failed, not empty: ${failed.load}", failed.load is LoadStatus.Failed)
        assertTrue("no items on failure", failed.items.isEmpty())
    }

    @Test
    fun `retry asks again and recovers, and close keeps the text for next time`(): Unit = runBlocking {
        val calls = mutableListOf<Triple<String, String, String>>()
        var attempt = 0
        val c = controller(calls) { _, _, q -> if (attempt++ == 0) error("blip") else listOf(item(q)) }
        c.submit("acc1", XtreamSection.MOVIES, "matrix")
        assertTrue("first attempt failed", c.settled().load is LoadStatus.Failed)
        c.retry()
        val done = withTimeout(5_000) { c.state.first { it.load == LoadStatus.Loaded } }
        assertEquals("recovered", listOf("matrix"), done.items.map { it.name })
        assertEquals("asked twice", 2, calls.size)

        c.close()
        assertEquals("closed", false, c.state.value.open)
        assertEquals("text kept for the keyboard", "matrix", c.state.value.query)
        assertTrue("results dropped", c.state.value.items.isEmpty())
    }
}
