package com.nuvio.tv.core.sync

import com.nuvio.tv.core.iptv.CategorySelections
import com.nuvio.tv.core.iptv.XtreamAccount
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the `sync_push_iptv_playlists` per-row JSON contract against the backend migration
 * (nuvio-backend/supabase/migrations/20260702000000_iptv_playlists.sql). Key names and
 * omission rules must match exactly — the RPC reads them with e->>'key'.
 */
class XtreamPlaylistSyncPayloadTest {

    private fun account(
        name: String = "Panel",
        epgUrl: String? = null,
        contentTypes: Set<String> = XtreamAccount.DEFAULT_CONTENT_TYPES,
        selections: CategorySelections = CategorySelections()
    ) = XtreamAccount(
        id = "http://host:8080|u1",
        name = name,
        baseUrl = "http://host:8080",
        username = "u1",
        password = "p1",
        enabled = true,
        epgUrl = epgUrl,
        dnsProvider = "system",
        autoRefreshHours = 0,
        contentTypes = contentTypes,
        categorySelections = selections
    )

    @Test
    fun `defaults-only account pushes exactly the base contract keys`() {
        val json = playlistPushJson(account(name = ""), sortOrder = 3)

        // exact key set: name omitted (blank), epg_url omitted (null),
        // category_selections omitted (all three null)
        assertEquals(
            setOf(
                "source_type", "enabled", "sort_order", "base_url", "username", "password",
                "dns_provider", "auto_refresh_hours", "content_types"
            ),
            json.keys
        )
        assertEquals("xtream", json.str("source_type"))
        assertEquals(true, json["enabled"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(3, json["sort_order"]!!.jsonPrimitive.content.toInt())
        assertEquals("http://host:8080", json.str("base_url"))
        assertEquals("u1", json.str("username"))
        assertEquals("p1", json.str("password"))
        assertEquals("system", json.str("dns_provider"))
        assertEquals(0, json["auto_refresh_hours"]!!.jsonPrimitive.content.toInt())
        assertEquals(
            listOf("live", "movies", "series"),
            json["content_types"]!!.jsonArray.map { it.jsonPrimitive.content }
        )
    }

    @Test
    fun `fully-populated account pushes every contract key with the right shapes`() {
        val json = playlistPushJson(
            account(
                name = "My Panel",
                epgUrl = "http://epg.example/xmltv.php",
                contentTypes = setOf("live", "movies"),
                selections = CategorySelections(live = listOf("1", "2"), movies = emptyList(), series = null)
            ),
            sortOrder = 0
        )

        assertEquals(
            setOf(
                "source_type", "name", "enabled", "sort_order", "base_url", "username", "password",
                "epg_url", "dns_provider", "auto_refresh_hours", "content_types", "category_selections"
            ),
            json.keys
        )
        assertEquals("My Panel", json.str("name"))
        assertEquals("http://epg.example/xmltv.php", json.str("epg_url"))
        assertEquals(listOf("live", "movies"), json["content_types"]!!.jsonArray.map { it.jsonPrimitive.content })

        // category_selections: live = array, movies = empty array, series OMITTED (null = all)
        val sel = json["category_selections"]!!.jsonObject
        assertEquals(setOf("live", "movies"), sel.keys)
        assertEquals(listOf("1", "2"), sel["live"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(0, sel["movies"]!!.jsonArray.size)
    }

    @Test
    fun `decodeCategorySelections is lenient and preserves null-vs-empty`() {
        // well-formed: live list, movies empty, series absent
        val decoded = decodeCategorySelections(
            buildJsonObject {
                put("live", buildJsonArray { add(JsonPrimitive("7")) })
                put("movies", JsonArray(emptyList()))
            }
        )
        assertEquals(listOf("7"), decoded.live)
        assertEquals(emptyList<String>(), decoded.movies)
        assertNull(decoded.series)

        // sql NULL / garbage -> all-null selections (= all categories), never a throw
        assertEquals(CategorySelections(), decodeCategorySelections(null))
        assertEquals(CategorySelections(), decodeCategorySelections(JsonPrimitive("junk")))
        assertEquals(
            CategorySelections(),
            decodeCategorySelections(buildJsonObject { put("live", JsonPrimitive("not-an-array")) })
        )
    }

    private fun JsonObject.str(key: String): String = this[key]!!.jsonPrimitive.content
}
