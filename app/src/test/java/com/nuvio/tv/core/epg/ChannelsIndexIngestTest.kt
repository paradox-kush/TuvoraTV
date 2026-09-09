package com.nuvio.tv.core.epg

import kotlinx.coroutines.test.runTest
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
 * Real-SQLite integration for bounded-memory channels-index ingestion, through the actual
 * [ingestChannelsIndexStream] + [EpgMirrorDb] (Robolectric framework SQLite). Proves the streamed
 * parse + shadow-swap commit AND that a rejected update retains the previous generation — what the
 * pure ChannelsIndexStreamParser test cannot. Twin of the KMP ChannelsIndexIngestTest.
 *
 * @Config as IptvContentDbTest: real framework SQLiteOpenHelper, plain Application, sdk 35 (36 needs
 * Java 21, repo is 17), Conscrypt OFF (no TLS here).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class ChannelsIndexIngestTest {
    private val db = EpgMirrorDb(RuntimeEnvironment.getApplication())
    private val json = Json { ignoreUnknownKeys = true }

    private fun feedOf(body: String): suspend (onChunk: (String) -> Unit) -> Unit =
        { onChunk -> onChunk(body) }

    private suspend fun rows(): List<Triple<String, String, String>> {
        val out = ArrayList<Triple<String, String, String>>()
        db.forEachIndexRow { out.add(Triple(it.slug, it.epgId, it.name)) }
        return out
    }

    private val gen1 = """{"sources":[{"slug":"a","channels":[{"id":"c1","names":["One"]}]}]}"""

    @Test
    fun `a valid index commits and stores the kept rows`() = runTest {
        assertTrue(ingestChannelsIndexStream(db, json, emptySet(), feedOf(gen1)))
        assertFalse(db.indexIsEmpty())
        assertEquals(listOf(Triple("a", "c1", "One")), rows())
    }

    @Test
    fun `a malformed source is rejected and the prior generation is retained`() = runTest {
        assertTrue(ingestChannelsIndexStream(db, json, emptySet(), feedOf(gen1)))
        val before = rows()
        val bad = """{"sources":[{"slug":"a","channels":[{"id":"c1","names":["One"]}]},{"slug":}]}"""
        assertFalse(ingestChannelsIndexStream(db, json, emptySet(), feedOf(bad)))
        assertEquals(before, rows())
    }

    @Test
    fun `a truncated response is rejected and the prior generation is retained`() = runTest {
        assertTrue(ingestChannelsIndexStream(db, json, emptySet(), feedOf(gen1)))
        val before = rows()
        val truncated = """{"sources":[{"slug":"b","channels":[{"id":"c9","names":["Nine"]}]}""" // no closing ]
        assertFalse(ingestChannelsIndexStream(db, json, emptySet(), feedOf(truncated)))
        assertEquals(before, rows())
    }

    @Test
    fun `only selected regions are stored`() = runTest {
        val doc = """{"sources":[""" +
            """{"slug":"gb","countries":"United Kingdom","channels":[{"id":"bbc","names":["BBC"]}]},""" +
            """{"slug":"es","countries":"Spain","channels":[{"id":"tve","names":["TVE"]}]}]}"""
        assertTrue(ingestChannelsIndexStream(db, json, setOf("United Kingdom"), feedOf(doc)))
        assertEquals(listOf(Triple("gb", "bbc", "BBC")), rows())
    }

    @Test
    fun `chunk-split delivery ingests the same rows`() = runTest {
        val feed: suspend (onChunk: (String) -> Unit) -> Unit = { onChunk ->
            gen1.chunked(7).forEach { onChunk(it) }
        }
        assertTrue(ingestChannelsIndexStream(db, json, emptySet(), feed))
        assertEquals(listOf(Triple("a", "c1", "One")), rows())
    }
}
