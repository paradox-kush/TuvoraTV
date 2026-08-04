package com.nuvio.tv.core.iptv.match

import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The index parser reads provider-supplied JSON directly, so it has to survive the same
 * encoding disagreements the DTO layer's FlexInt adapter was written for: the same field
 * arrives as a number on one panel and a quoted string on another.
 */
class XtreamCatalogIndexParserTest {

    private fun src(json: String) = Buffer().writeUtf8(json)

    @Test
    fun `vod parses numeric and quoted ids alike`() {
        val items = XtreamCatalogIndexParser.parseVod(
            src(
                """
                [
                  {"stream_id": 12, "name": "Arrival (2016)", "tmdb": 329865, "container_extension": "mkv", "stream_icon": "http://x/a.jpg"},
                  {"stream_id": "13", "name": "Dune", "tmdb": "438631", "container_extension": "mp4", "stream_icon": ""}
                ]
                """.trimIndent()
            )
        )

        assertEquals(2, items.size)
        assertEquals(IndexedItem(12, "Arrival (2016)", 2016, 329865, "mkv", "http://x/a.jpg"), items[0])
        assertEquals(13, items[1].sid)
        assertEquals(438631, items[1].tmdb)
        // blank icon is not a poster
        assertNull(items[1].poster)
    }

    @Test
    fun `vod drops rows without a stream id and ignores unknown fields`() {
        val items = XtreamCatalogIndexParser.parseVod(
            src(
                """
                [
                  {"name": "No id here", "tmdb": 1},
                  {"stream_id": null, "name": "Null id"},
                  {"stream_id": 7, "name": "Keeper", "num": 3, "added": "1699999999",
                   "rating": "7.4", "category_id": "12", "extra": {"nested": [1,2,3]}}
                ]
                """.trimIndent()
            )
        )

        assertEquals(1, items.size)
        assertEquals(7, items[0].sid)
        assertEquals("Keeper", items[0].name)
    }

    @Test
    fun `vod treats a zero tmdb as absent`() {
        val items = XtreamCatalogIndexParser.parseVod(
            src("""[{"stream_id": 1, "name": "X", "tmdb": 0}]""")
        )
        assertNull(items[0].tmdb)
    }

    @Test
    fun `series prefers releaseDate over a year parsed from the title`() {
        val items = XtreamCatalogIndexParser.parseSeries(
            src(
                """
                [
                  {"series_id": 5, "name": "Silo (2023)", "releaseDate": "2019-05-01", "cover": "http://x/c.jpg", "tmdb": 125988},
                  {"series_id": 6, "name": "Severance (2022)", "release_date": "2021-02-18"},
                  {"series_id": 7, "name": "Fallout (2024)"}
                ]
                """.trimIndent()
            )
        )

        assertEquals(3, items.size)
        assertEquals(2019, items[0].year)
        assertEquals(125988, items[0].tmdb)
        // the underscore spelling is honoured when the camelCase one is absent
        assertEquals(2021, items[1].year)
        // and with neither, the title still supplies the year
        assertEquals(2024, items[2].year)
        // series rows carry no container extension
        assertNull(items[0].ext)
    }

    @Test
    fun `empty catalog parses to an empty list`() {
        assertEquals(emptyList<IndexedItem>(), XtreamCatalogIndexParser.parseVod(src("[]")))
    }

    @Test
    fun `an error object where the catalog should be is a failure, not an empty catalog`() {
        // Panels answer some failures with {"user_info": {...}}. Parsing that as "no movies"
        // would wipe a good index; it has to throw so the caller backs off instead.
        assertThrows(IllegalStateException::class.java) {
            XtreamCatalogIndexParser.parseVod(src("""{"user_info": {"auth": 0}}"""))
        }
    }
}
