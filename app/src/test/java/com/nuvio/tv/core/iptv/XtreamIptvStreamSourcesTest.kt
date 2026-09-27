package com.nuvio.tv.core.iptv

import org.junit.Assert.assertEquals
import org.junit.Test

class XtreamIptvStreamSourcesTest {
    private fun account(id: String, types: Set<String>, enabled: Boolean = true) = XtreamAccount(
        id = id, name = id, baseUrl = "http://$id", username = "u", password = "p",
        enabled = enabled, contentTypes = types,
    )

    @Test
    fun `enabled accounts contribute the movie and series types they serve`() {
        val types = XtreamIptvStreamSources.servedContentTypesOf(listOf(
            account("a", setOf(XtreamAccount.TYPE_LIVE, XtreamAccount.TYPE_MOVIES)),
            account("b", setOf(XtreamAccount.TYPE_SERIES)),
        ))
        assertEquals("movies + series across accounts", setOf("movie", "series"), types)
    }

    @Test
    fun `disabled and live-only accounts serve nothing`() {
        val types = XtreamIptvStreamSources.servedContentTypesOf(listOf(
            account("a", setOf(XtreamAccount.TYPE_MOVIES, XtreamAccount.TYPE_SERIES), enabled = false),
            account("b", setOf(XtreamAccount.TYPE_LIVE)),
        ))
        assertEquals("no playable types", emptySet<String>(), types)
    }
}
