package com.nuvio.tv.core.sync

import com.nuvio.tv.core.iptv.CategorySelections
import com.nuvio.tv.core.iptv.XtreamAccount
import com.nuvio.tv.data.remote.supabase.SupabaseIptvPlaylist
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
import org.junit.Assert.assertTrue
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
    fun `regular push scopes the replace to the known source types and omits p_only_if_empty`() {
        val params = playlistPushParams(listOf(account()), profileId = 2, onlyIfEmpty = false)

        // exact key set: no p_only_if_empty on a regular push
        assertEquals(setOf("p_playlists", "p_profile_id", "p_source_types"), params.keys)
        assertEquals(2, params["p_profile_id"]!!.jsonPrimitive.content.toInt())
        // EVERY push is source-type-scoped so this client can't delete a FUTURE type's rows.
        // Wire names — the internal "url"/"file" spellings never leave the device.
        assertEquals(
            listOf("xtream", "m3u_url", "m3u_file", "stalker"),
            params["p_source_types"]!!.jsonArray.map { it.jsonPrimitive.content }
        )
        assertEquals(1, params["p_playlists"]!!.jsonArray.size)
    }

    @Test
    fun `legacy-migration push additionally sends p_only_if_empty true`() {
        val params = playlistPushParams(listOf(account()), profileId = 1, onlyIfEmpty = true)

        assertEquals(setOf("p_playlists", "p_profile_id", "p_source_types", "p_only_if_empty"), params.keys)
        assertTrue(params["p_only_if_empty"]!!.jsonPrimitive.content.toBoolean())
        // still source-type-scoped
        assertEquals(
            listOf("xtream", "m3u_url", "m3u_file", "stalker"),
            params["p_source_types"]!!.jsonArray.map { it.jsonPrimitive.content }
        )
    }

    @Test
    fun `m3u and stalker accounts push their wire type and per-type extras`() {
        val m3u = XtreamAccount(
            id = "m3u:http://h/list.m3u", name = "M3U", baseUrl = "http://h/list.m3u",
            username = "VLC/3.0",   // this client stashes the UA in username
            password = "", sourceType = XtreamAccount.SOURCE_URL
        )
        val m3uJson = playlistPushJson(m3u, sortOrder = 0)
        assertEquals("m3u_url", m3uJson.str("source_type"))         // wire name, not "url"
        assertEquals("http://h/list.m3u", m3uJson.str("url"))
        assertEquals("VLC/3.0", m3uJson.str("user_agent"))

        val file = XtreamAccount(
            id = "file:abc", name = "tv", baseUrl = "", username = "", password = "",
            sourceType = XtreamAccount.SOURCE_FILE, fileName = "tv.m3u"
        )
        val fileJson = playlistPushJson(file, sortOrder = 1)
        assertEquals("m3u_file", fileJson.str("source_type"))       // wire name, not "file"
        assertEquals("tv.m3u", fileJson.str("file_name"))

        val stalker = XtreamAccount(
            id = "stalker|http://p:8080|00:1A:79:AA:BB:CC", name = "Portal", baseUrl = "http://p:8080",
            username = "", password = "", sourceType = XtreamAccount.SOURCE_STALKER,
            portalUrl = "http://p:8080", macAddress = "00:1A:79:AA:BB:CC",
            serialNumber = "SN1", sendDeviceId = false
        )
        val stalkerJson = playlistPushJson(stalker, sortOrder = 2)
        assertEquals("stalker", stalkerJson.str("source_type"))
        assertEquals("http://p:8080", stalkerJson.str("portal_url"))
        assertEquals("00:1A:79:AA:BB:CC", stalkerJson.str("mac_address"))
        assertEquals("SN1", stalkerJson.str("serial_number"))
        assertEquals(false, stalkerJson["send_device_id"]!!.jsonPrimitive.content.toBoolean())
        // blank optionals stay omitted
        assertTrue("stalker_username" !in stalkerJson)
        assertTrue("device_id" !in stalkerJson)
    }

    @Test
    fun `pull maps every source type to the settings-form account shape`() {
        // m3u_url (wire) -> internal SOURCE_URL via the shared builder (UA back into username).
        val m3u = SupabaseIptvPlaylist(sourceType = "m3u_url", url = "http://h/list.m3u", userAgent = "VLC/3.0")
            .toXtreamAccountOrNull()!!
        assertEquals(XtreamAccount.SOURCE_URL, m3u.sourceType)
        assertEquals("http://h/list.m3u", m3u.baseUrl)
        assertEquals("VLC/3.0", m3u.username)
        assertTrue(m3u.id.startsWith("m3u:"))

        // m3u_file -> re-import ghost with a deterministic id.
        val file = SupabaseIptvPlaylist(sourceType = "m3u_file", fileName = "tv.m3u", name = "TV")
            .toXtreamAccountOrNull()!!
        assertEquals(XtreamAccount.SOURCE_FILE, file.sourceType)
        assertEquals("file:synced-tv.m3u", file.id)
        assertEquals("tv.m3u", file.fileName)

        // stalker -> the P4 field shape + form-builder id (portalUrl/macAddress drive the session).
        val stalker = SupabaseIptvPlaylist(
            sourceType = "stalker", portalUrl = "http://p:8080", macAddress = "00:1A:79:AA:BB:CC",
            serialNumber = "SN1", sendDeviceId = false
        ).toXtreamAccountOrNull()!!
        assertEquals("stalker|http://p:8080|00:1A:79:AA:BB:CC", stalker.id)
        assertEquals("http://p:8080", stalker.portalUrl)
        assertEquals("00:1A:79:AA:BB:CC", stalker.macAddress)
        assertEquals("SN1", stalker.serialNumber)
        assertEquals(false, stalker.sendDeviceId)
    }

    @Test
    fun `reconcile keeps the local file playlist id so its local copy survives a pull`() {
        val local = XtreamAccount(
            id = "file:1234-uuid", name = "tv", baseUrl = "", username = "", password = "",
            sourceType = XtreamAccount.SOURCE_FILE, fileName = "tv.m3u"
        )
        val pulled = SupabaseIptvPlaylist(sourceType = "m3u_file", fileName = "tv.m3u", dnsProvider = "quad9")
            .toXtreamAccountOrNull()!!
        val reconciled = reconcileLocalIds(listOf(pulled), listOf(local)).single()
        assertEquals("file:1234-uuid", reconciled.id)          // local copy + content keys survive
        assertEquals("quad9", reconciled.dnsProvider)          // remote option edits still apply
        // no local match -> deterministic synced id kept
        assertEquals("file:synced-tv.m3u", reconcileLocalIds(listOf(pulled), emptyList()).single().id)
    }

    @Test
    fun `pull with only unknown or malformed rows maps to no usable accounts (local state kept)`() {
        val futureType = SupabaseIptvPlaylist(sourceType = "plex", name = "Future", enabled = true)
        val malformedM3u = SupabaseIptvPlaylist(sourceType = "m3u_url", name = "M3U")          // no url
        val malformedStalker = SupabaseIptvPlaylist(sourceType = "stalker", name = "Portal")   // no portal/mac
        val xtream = SupabaseIptvPlaylist(
            sourceType = "xtream", baseUrl = "http://host:8080", username = "u1", password = "p1"
        )

        // Unusable-only rows must decode to an EMPTY usable list -> pullAndApply treats it like an
        // empty remote (no applyRemote of []), so local accounts survive.
        assertEquals(
            emptyList<XtreamAccount>(),
            listOf(futureType, malformedM3u, malformedStalker).mapNotNull { it.toXtreamAccountOrNull() }
        )

        // Mixed rows keep the usable ones.
        val usable = listOf(futureType, xtream, malformedStalker).mapNotNull { it.toXtreamAccountOrNull() }
        assertEquals(listOf("http://host:8080|u1"), usable.map { it.id })

        // An xtream row missing credentials is unusable too.
        assertNull(SupabaseIptvPlaylist(sourceType = "xtream", baseUrl = "http://host").toXtreamAccountOrNull())
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
