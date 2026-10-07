package com.nuvio.tv.core.streams

import com.nuvio.tv.domain.model.Stream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wave 3 / P0: a matched source listed with a deferred url is minted when PICKED. The player's source
 * and episode switch used to hand the engine the "stalker-deferred:..." placeholder.
 */
class DeferredStreamPolicyTest {

    private fun stream(url: String?) = Stream(
        name = "Edition", title = null, description = null, url = url, ytId = null, infoHash = null,
        fileIdx = null, externalUrl = null, behaviorHints = null, addonName = "Home portal", addonLogo = null,
    )

    private val isDeferred: (String?) -> Boolean = { it != null && it.startsWith("stalker-deferred:") }

    @Test
    fun `a deferred stream is minted and the copy carries the real url`() = runBlocking {
        val minted = mutableListOf<String>()
        val outcome = DeferredStreamPolicy.resolve(stream("stalker-deferred:acc|movie|1|Heat"), isDeferred) { url ->
            minted += url
            "https://cdn.example/minted.mp4"
        }
        assertTrue("minted: $outcome", outcome is DeferredStreamPolicy.Outcome.Minted)
        val playable = (outcome as DeferredStreamPolicy.Outcome.Minted).stream
        assertEquals("the engine gets the minted url", "https://cdn.example/minted.mp4", playable.url)
        assertEquals("the edition keeps its provider label", "Home portal", playable.addonName)
        assertEquals("exactly one mint, for the picked url", listOf("stalker-deferred:acc|movie|1|Heat"), minted)
    }

    @Test
    fun `a plain stream is never minted`() = runBlocking {
        var mints = 0
        val outcome = DeferredStreamPolicy.resolve(stream("https://cdn.example/a.mp4"), isDeferred) { mints++; "unused" }
        assertEquals(DeferredStreamPolicy.Outcome.Plain, outcome)
        assertEquals("no mint for a plain url", 0, mints)
    }

    @Test
    fun `a stream with no url is plain`() = runBlocking {
        assertEquals(DeferredStreamPolicy.Outcome.Plain, DeferredStreamPolicy.resolve(stream(null), isDeferred) { "unused" })
    }

    @Test
    fun `a failed or blank mint is unavailable - never the placeholder`() = runBlocking {
        assertEquals(
            DeferredStreamPolicy.Outcome.Unavailable,
            DeferredStreamPolicy.resolve(stream("stalker-deferred:a"), isDeferred) { null },
        )
        assertEquals(
            DeferredStreamPolicy.Outcome.Unavailable,
            DeferredStreamPolicy.resolve(stream("stalker-deferred:a"), isDeferred) { "  " },
        )
        assertEquals(
            "a mint that hands the placeholder back is a failed mint",
            DeferredStreamPolicy.Outcome.Unavailable,
            DeferredStreamPolicy.resolve(stream("stalker-deferred:a"), isDeferred) { "stalker-deferred:a" },
        )
    }
}
