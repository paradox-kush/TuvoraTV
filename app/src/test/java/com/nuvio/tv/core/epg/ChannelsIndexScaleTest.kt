package com.nuvio.tv.core.epg

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * Proves the two allocation risks are gone through the real repository + framework SQLite (Robolectric):
 * the shadow receives channels in batches that never exceed INDEX_BATCH, and that peak batch size is
 * constant regardless of channel count (no proportional growth in retained application collections).
 *
 * The JVM-heap delta is measured on the KMP host runners (ChannelsIndexScaleTest there); Robolectric's
 * SQLite storage model makes a JVM-heap figure unreliable here, so TV proves the bound structurally.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class ChannelsIndexScaleTest {
    private val db = EpgMirrorDb(RuntimeEnvironment.getApplication())
    private val json = Json { ignoreUnknownKeys = true }
    private val batches = ArrayList<Int>()

    private fun observe() { db.indexInsertObserver = { batches.add(it) } }

    private fun syntheticFeed(sources: Int, channelsPer: Int, countries: String): suspend (onChunk: (String) -> Unit) -> Unit =
        { onChunk ->
            onChunk("""{"generatedAt":"t","sources":[""")
            for (s in 0 until sources) {
                if (s > 0) onChunk(",")
                onChunk("""{"slug":"s$s","label":"S$s","countries":"$countries","channels":[""")
                for (ch in 0 until channelsPer) {
                    if (ch > 0) onChunk(",")
                    onChunk("""{"id":"s${s}c$ch",""")
                    onChunk(""""names":["Name $s $ch"]}""")
                }
                onChunk("]}")
            }
            onChunk("]}")
        }

    private suspend fun rowCount(): Int {
        var n = 0
        db.forEachIndexRow { n++ }
        return n
    }

    @Test
    fun `one very large source streams in bounded batches`() = runTest {
        observe()
        val channels = 120_000
        assertTrue(ingestChannelsIndexStream(db, json, emptySet(), syntheticFeed(1, channels, "United Kingdom")))
        assertEquals(channels, rowCount())
        assertTrue("max batch ${batches.max()}", batches.max() <= 4_000)
        assertTrue("streamed in many flushes (${batches.size})", batches.size >= 30)
    }

    @Test
    fun `max retained batch does not grow with channel count`() = runTest {
        observe()
        ingestChannelsIndexStream(db, json, emptySet(), syntheticFeed(1, 10_000, "Spain"))
        val small = batches.max()
        batches.clear()
        ingestChannelsIndexStream(db, json, emptySet(), syntheticFeed(1, 100_000, "Spain"))
        val large = batches.max()
        assertEquals("peak retained batch constant across a 10x larger catalog", small, large)
        assertTrue(large <= 4_000)
    }

    @Test
    fun `near-fully-selected multi-source catalog stays bounded`() = runTest {
        observe()
        assertTrue(ingestChannelsIndexStream(db, json, setOf("India"), syntheticFeed(40, 3_000, "India")))
        assertEquals(120_000, rowCount())
        assertTrue("max batch ${batches.max()}", batches.max() <= 4_000)
    }
}
