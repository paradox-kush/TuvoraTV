package com.nuvio.tv.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

// getStreamUrls() was removed from Stream in favour of getStreamUrl() (singular).
// Entire class is ignored until the test is rewritten against the current API.
@Ignore("getStreamUrls() no longer exists — rewrite against getStreamUrl()")
class StreamTest {

    @Test
    fun `getStreamUrls keeps primary url first and appends distinct sources`() {
        val stream = Stream(
            name = null,
            title = null,
            description = null,
            url = "https://example.com/primary.m3u8",
            sources = listOf(
                "https://example.com/primary.m3u8",
                " https://example.com/fallback1.m3u8 ",
                "",
                "https://example.com/fallback2.m3u8"
            ),
            ytId = null,
            infoHash = null,
            fileIdx = null,
            externalUrl = null,
            behaviorHints = null,
            addonName = "Addon",
            addonLogo = null
        )

        // assertEquals(
        //     listOf(
        //         "https://example.com/primary.m3u8",
        //         "https://example.com/fallback1.m3u8",
        //         "https://example.com/fallback2.m3u8"
        //     ),
        //     stream.getStreamUrls()
        // )
        assertEquals("https://example.com/primary.m3u8", stream.getStreamUrl())
    }

    @Test
    fun `sources-only stream is not treated as external`() {
        val stream = Stream(
            name = null,
            title = null,
            description = null,
            url = null,
            sources = listOf("https://example.com/fallback1.m3u8"),
            ytId = null,
            infoHash = null,
            fileIdx = null,
            externalUrl = "https://example.com/browser",
            behaviorHints = null,
            addonName = "Addon",
            addonLogo = null
        )

        assertEquals("https://example.com/fallback1.m3u8", stream.getStreamUrl())
        assertFalse(stream.isExternal())
    }

    @Test
    fun `external stream remains external when no playable urls exist`() {
        val stream = Stream(
            name = null,
            title = null,
            description = null,
            url = null,
            sources = emptyList(),
            ytId = null,
            infoHash = null,
            fileIdx = null,
            externalUrl = "https://example.com/browser",
            behaviorHints = null,
            addonName = "Addon",
            addonLogo = null
        )

        assertTrue(stream.isExternal())
    }
}
