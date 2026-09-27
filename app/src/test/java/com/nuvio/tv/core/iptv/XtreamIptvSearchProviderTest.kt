package com.nuvio.tv.core.iptv

import org.junit.Assert.assertEquals
import org.junit.Test

class XtreamIptvSearchProviderTest {

    private fun hit(id: String, live: Boolean = false) =
        XtreamSearchIndex.Hit(id, "n$id", null, isLive = live, streamUrl = null, detailType = "movie")

    @Test
    fun `rows keep channels movies series order and drop empty rows`() {
        val rows = XtreamIptvSearchProvider.rowsOf(
            XtreamSearchIndex.Results(
                channels = listOf(hit("c", live = true)),
                movies = emptyList(),
                series = listOf(hit("s")),
            )
        )
        assertEquals("row ids", listOf("xtream_channels", "xtream_series"), rows.map { it.catalogId })
        assertEquals("row types", listOf("tv", "series"), rows.map { it.rawType })
        assertEquals("live flag carried", true, rows.first().hits.single().isLive)
    }
}
