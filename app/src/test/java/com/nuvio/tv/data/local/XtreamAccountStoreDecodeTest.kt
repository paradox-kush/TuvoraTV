package com.nuvio.tv.data.local

import com.google.gson.Gson
import com.nuvio.tv.core.iptv.CategorySelections
import com.nuvio.tv.core.iptv.XtreamAccount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The DataStore key "xtream_accounts" holds JSON written by pre-playlist-manager builds
 * (no sourceType/epgUrl/dnsProvider/autoRefreshHours/contentTypes/categorySelections).
 * Gson instantiates via Unsafe, bypassing Kotlin defaults — these tests pin the normalizer
 * that re-applies them.
 */
class XtreamAccountStoreDecodeTest {

    private val gson = Gson()

    @Test
    fun `old persisted JSON without new fields decodes with correct defaults`() {
        val legacyJson = """
            [{"id":"http://host:8080|u1","name":"Panel","baseUrl":"http://host:8080",
              "username":"u1","password":"p1","enabled":true}]
        """.trimIndent()

        val accounts = decodeXtreamAccountsJson(gson, legacyJson)

        assertEquals(1, accounts.size)
        val acc = accounts[0]
        // old fields intact
        assertEquals("http://host:8080|u1", acc.id)
        assertEquals("Panel", acc.name)
        assertEquals("http://host:8080", acc.baseUrl)
        assertEquals("u1", acc.username)
        assertEquals("p1", acc.password)
        assertTrue(acc.enabled)
        // new fields take their defaults despite Gson's Unsafe instantiation
        assertEquals(XtreamAccount.SOURCE_XTREAM, acc.sourceType)
        assertNull(acc.epgUrl)
        assertEquals(XtreamAccount.DNS_SYSTEM, acc.dnsProvider)
        // missing field → the 24h product default, not Gson's primitive 0
        assertEquals(24, acc.autoRefreshHours)
        assertEquals(XtreamAccount.DEFAULT_CONTENT_TYPES, acc.contentTypes)
        assertEquals(CategorySelections(), acc.categorySelections)
        // and the default selection means "everything allowed"
        assertTrue(acc.typeEnabled(XtreamAccount.TYPE_LIVE))
        assertTrue(acc.allowsCategory(XtreamAccount.TYPE_MOVIES, "42"))
    }

    @Test
    fun `new fields survive a serialize-deserialize roundtrip`() {
        val original = XtreamAccount(
            id = "http://host|u",
            name = "Panel",
            baseUrl = "http://host",
            username = "u",
            password = "p",
            enabled = false,
            epgUrl = "http://epg.example/xmltv.php",
            dnsProvider = "cloudflare",
            autoRefreshHours = 24,
            contentTypes = setOf(XtreamAccount.TYPE_LIVE, XtreamAccount.TYPE_MOVIES),
            categorySelections = CategorySelections(live = listOf("1", "2"), movies = emptyList(), series = null)
        )

        val decoded = decodeXtreamAccountsJson(gson, gson.toJson(listOf(original))).single()

        assertEquals(original, decoded)
        // null (series) stays null through the roundtrip: "all categories incl. future"
        assertNull(decoded.categorySelections.series)
        assertEquals(emptyList<String>(), decoded.categorySelections.movies)
    }

    @Test
    fun `explicit autoRefreshHours 0 (Off) is preserved, not overwritten by the default`() {
        val json = """
            [{"id":"http://host|u","name":"P","baseUrl":"http://host","username":"u","password":"p",
              "enabled":true,"autoRefreshHours":0}]
        """.trimIndent()

        assertEquals(0, decodeXtreamAccountsJson(gson, json).single().autoRefreshHours)
    }

    @Test
    fun `blank or malformed JSON decodes to empty list`() {
        assertEquals(emptyList<XtreamAccount>(), decodeXtreamAccountsJson(gson, null))
        assertEquals(emptyList<XtreamAccount>(), decodeXtreamAccountsJson(gson, ""))
        assertEquals(emptyList<XtreamAccount>(), decodeXtreamAccountsJson(gson, "{not json"))
    }
}
