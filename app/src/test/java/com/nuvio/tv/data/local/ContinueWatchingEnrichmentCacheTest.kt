package com.nuvio.tv.data.local

import com.nuvio.tv.core.profile.ProfileManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.io.File

/**
 * Bounds + predictable failure handling for the disposable continue-watching enrichment cache, over
 * the real file store (Robolectric). Both snapshot methods are covered: a valid round-trip, the
 * producer write cap, the reader byte cap (oversized → dropped + empty), the reader record cap, and
 * corrupt input (→ dropped + empty). A valid snapshot is never deleted.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class ContinueWatchingEnrichmentCacheTest {
    private val app = RuntimeEnvironment.getApplication()
    private val pm = mockk<ProfileManager> { every { activeProfileId } returns MutableStateFlow(0) }
    private val cache = ContinueWatchingEnrichmentCache(app, pm)

    private fun nextUpFile() = File(app.filesDir, "cw_enrichment/nextup_0.json")
    private fun inProgressFile() = File(app.filesDir, "cw_enrichment/inprogress_0.json")

    private fun nextUp(id: String) = CachedNextUpItem(
        contentId = id, contentType = "series", name = "N$id", poster = null, backdrop = null, logo = null,
        videoId = "v$id", season = 1, episode = 1, episodeTitle = null, thumbnail = null,
        lastWatched = 1L, sortTimestamp = 1L,
    )

    private fun inProgress(id: String) = CachedInProgressItem(
        contentId = id, contentType = "movie", name = "N$id", poster = null, backdrop = null, logo = null,
        videoId = "v$id", season = null, episode = null, episodeTitle = null,
        position = 1L, duration = 2L, lastWatched = 1L, progressPercent = 0.5f,
    )

    @Test
    fun `next-up round-trips a valid snapshot and never drops it`() = runBlocking {
        cache.saveNextUpSnapshot(listOf(nextUp("a"), nextUp("b")), force = true)
        assertEquals(listOf("a", "b"), cache.getNextUpSnapshot().map { it.contentId })
        assertTrue("a valid snapshot must survive a read", nextUpFile().exists())
    }

    @Test
    fun `in-progress round-trips a valid snapshot`() = runBlocking {
        cache.saveInProgressSnapshot(listOf(inProgress("x")), force = true)
        assertEquals(listOf("x"), cache.getInProgressSnapshot().map { it.contentId })
    }

    @Test
    fun `the producer caps what it writes at 500 records`() = runBlocking {
        cache.saveNextUpSnapshot((1..600).map { nextUp("i$it") }, force = true)
        assertEquals(500, cache.getNextUpSnapshot().size)
    }

    @Test
    fun `an oversized file is dropped before the read and returns empty`() = runBlocking {
        nextUpFile().parentFile!!.mkdirs()
        nextUpFile().writeText("[" + " ".repeat(5 * 1024 * 1024) + "]") // > 4 MB byte cap
        assertTrue(cache.getNextUpSnapshot().isEmpty())
        assertFalse("an over-byte-cap disposable file must be dropped", nextUpFile().exists())
    }

    @Test
    fun `a file with more than 500 records is read bounded to 500 and kept`() = runBlocking {
        // Write 600 raw records directly (bypassing the producer cap) to exercise the reader cap.
        val raw = (1..600).joinToString(",", "[", "]") {
            """{"contentId":"r$it","contentType":"movie","name":"n","videoId":"v","season":1,"episode":1,"lastWatched":1,"sortTimestamp":1}"""
        }
        nextUpFile().parentFile!!.mkdirs()
        nextUpFile().writeText(raw)
        assertEquals(500, cache.getNextUpSnapshot().size)
        assertTrue("a valid (merely large) snapshot must not be deleted", nextUpFile().exists())
    }

    @Test
    fun `the record cap halts decoding mid-stream instead of parsing the whole file then truncating`() = runBlocking {
        // 500 valid records, then a MALFORMED object. If the JsonReader stops after MAX_RECORDS (the
        // `if (out.size >= MAX_RECORDS) break` in readBoundedSnapshot), the malformed tail is never
        // parsed -> no exception -> 500 kept. If instead the whole file were decoded and only then
        // truncated, the malformed object would throw -> the file would be dropped -> empty. So a KEPT
        // 500 is proof the decode halts mid-stream. (The tail starts with '{' so hasNext() can peek it
        // without throwing; it only fails if actually parsed as a value.)
        val valid = (1..500).joinToString(",") {
            """{"contentId":"r$it","contentType":"movie","name":"n","videoId":"v","season":1,"episode":1,"lastWatched":1,"sortTimestamp":1}"""
        }
        nextUpFile().parentFile!!.mkdirs()
        nextUpFile().writeText("""[$valid,{"broken":}]""")
        assertEquals(500, cache.getNextUpSnapshot().size)
        assertTrue("early-stop must keep the valid, record-capped file", nextUpFile().exists())
    }

    @Test
    fun `a corrupt file returns empty and is dropped`() = runBlocking {
        inProgressFile().parentFile!!.mkdirs()
        inProgressFile().writeText("this is not json")
        assertTrue(cache.getInProgressSnapshot().isEmpty())
        assertFalse("an unparseable disposable file must be dropped", inProgressFile().exists())
    }

    @Test
    fun `a replacement write is read back, not the prior snapshot`() = runBlocking {
        cache.saveNextUpSnapshot(listOf(nextUp("old")), force = true)
        cache.saveNextUpSnapshot(listOf(nextUp("new1"), nextUp("new2")), force = true)
        assertEquals(listOf("new1", "new2"), cache.getNextUpSnapshot().map { it.contentId })
    }
}
