package com.nuvio.tv.core.epg

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * Correctness + concurrency for the two-phase channels-index ingest, through the real repository +
 * framework SQLite (Robolectric). Twin of NuvioMobile's ChannelsIndexConcurrencyTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class ChannelsIndexConcurrencyTest {
    private val db = EpgMirrorDb(RuntimeEnvironment.getApplication())
    private val json = Json { ignoreUnknownKeys = true }

    private fun feedOf(body: String): suspend (onChunk: (String) -> Unit) -> Unit = { it(body) }

    private suspend fun rows(): List<Triple<String, String, String>> {
        val out = ArrayList<Triple<String, String, String>>()
        db.forEachIndexRow { out.add(Triple(it.slug, it.epgId, it.name)) }
        return out
    }

    private suspend fun commit(selection: Set<String>, body: String) {
        assertTrue(ingestChannelsIndexStream(db, json, selection, feedOf(body)))
    }

    @Test
    fun `late slug and countries after channels still filter correctly`() = runBlocking {
        val doc = """{"sources":[""" +
            """{"channels":[{"id":"bbc","names":["BBC"]}],"slug":"gb","countries":"United Kingdom"},""" +
            """{"channels":[{"id":"tve","names":["TVE"]}],"slug":"es","countries":"Spain"}]}"""
        commit(setOf("United Kingdom"), doc)
        assertEquals(listOf(Triple("gb", "bbc", "BBC")), rows())
    }

    @Test
    fun `duplicate metadata keys use the last value`() = runBlocking {
        val doc = """{"sources":[""" +
            """{"slug":"zz","countries":"Spain","slug":"gb","countries":"United Kingdom",""" +
            """"channels":[{"id":"bbc","names":["BBC"]}]}]}"""
        commit(setOf("United Kingdom"), doc)
        assertEquals(listOf(Triple("gb", "bbc", "BBC")), rows())
    }

    @Test
    fun `skipped region channels are not promoted`() = runBlocking {
        val doc = """{"sources":[""" +
            """{"slug":"gb","countries":"United Kingdom","channels":[{"id":"bbc","names":["BBC"]}]},""" +
            """{"slug":"es","countries":"Spain","channels":[{"id":"a","names":["A"]},{"id":"b","names":["B"]}]}]}"""
        commit(setOf("United Kingdom"), doc)
        assertEquals(listOf(Triple("gb", "bbc", "BBC")), rows())
    }

    @Test
    fun `a stalled transport does not block a guide read of the previous generation`() = runBlocking {
        commit(emptySet(), """{"sources":[{"slug":"a","channels":[{"id":"c1","names":["One"]}]}]}""")
        val gen1 = rows()

        val stalled = CompletableDeferred<Unit>()
        val reached = CompletableDeferred<Unit>()
        val job = launch {
            ingestChannelsIndexStream(db, json, emptySet()) { onChunk ->
                onChunk("""{"sources":[{"slug":"b","channels":[{"id":"c9",""")
                reached.complete(Unit)
                stalled.await()
                onChunk(""""names":["Nine"]}]}]}""")
            }
        }
        reached.await()
        assertEquals(gen1, rows())
        stalled.complete(Unit)
        job.join()
        assertEquals(listOf(Triple("b", "c9", "Nine")), rows())
    }

    @Test
    fun `cancellation while awaiting the network preserves the previous generation`() = runBlocking {
        commit(emptySet(), """{"sources":[{"slug":"a","channels":[{"id":"c1","names":["One"]}]}]}""")
        val gen1 = rows()

        val never = CompletableDeferred<Unit>()
        val reached = CompletableDeferred<Unit>()
        val job = launch {
            ingestChannelsIndexStream(db, json, emptySet()) { onChunk ->
                onChunk("""{"sources":[{"slug":"b","channels":[{"id":"c9","names":["Nine"]}""")
                reached.complete(Unit)
                never.await()
            }
        }
        reached.await()
        withTimeout(5_000) { job.cancelAndJoin() }
        assertEquals(gen1, rows())
        commit(emptySet(), """{"sources":[{"slug":"c","channels":[{"id":"c3","names":["Three"]}]}]}""")
        assertEquals(listOf(Triple("c", "c3", "Three")), rows())
    }

    @Test
    fun `overlapping ingests do not corrupt the committed generation`() = runBlocking {
        val docA = """{"sources":[{"slug":"a","channels":[{"id":"a1","names":["A1"]},{"id":"a2","names":["A2"]}]}]}"""
        val docB = """{"sources":[{"slug":"b","channels":[{"id":"b1","names":["B1"]}]}]}"""
        val jobA = launch { ingestChannelsIndexStream(db, json, emptySet(), feedOf(docA)) }
        val jobB = launch { ingestChannelsIndexStream(db, json, emptySet(), feedOf(docB)) }
        jobA.join(); jobB.join()
        val slugs = rows().map { it.first }.toSet()
        assertTrue("one whole generation won: $slugs", slugs == setOf("a") || slugs == setOf("b"))
        assertFalse("the two generations did not merge", "a" in slugs && "b" in slugs)
    }
}
