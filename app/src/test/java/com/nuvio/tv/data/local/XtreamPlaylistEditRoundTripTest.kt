package com.nuvio.tv.data.local

import com.google.gson.Gson
import com.nuvio.tv.core.iptv.XtreamAccount
import com.nuvio.tv.core.iptv.m3uAccountFromUrl
import com.nuvio.tv.core.iptv.parseXtreamAccount
import com.nuvio.tv.core.iptv.withPlaylistOptions
import com.nuvio.tv.core.iptv.xtreamAccountFromFields
import com.nuvio.tv.core.sync.playlistPushJson
import com.nuvio.tv.core.sync.toXtreamAccountOrNull
import com.nuvio.tv.data.remote.supabase.SupabaseIptvPlaylist
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * B04 regression: the reporter sets a per-playlist User-Agent + Auto-refresh 12h, saves, and on
 * reopening the Edit dialog both fields are reset (UA blank, refresh back to 24h).
 *
 * These tests reproduce the settings-form save -> persist -> reopen-prefill cycle for each source
 * type WITHOUT Hilt, exercising the real option-application helpers + the real Gson persistence
 * normalizer. Each edit path composes the account the same way [XtreamSettingsViewModel] does:
 *   builtAccount.withPlaylistOptions(epg, dns, refresh).copy(userAgent = formUa)
 * then swaps in the old enabled/content/category selections before store.replace (gson.toJson),
 * and the reopened dialog prefills from decodeXtreamAccountsJson(...).
 */
class XtreamPlaylistEditRoundTripTest {

    private val gson = Gson()
    private val json = Json { ignoreUnknownKeys = true }   // mirrors the supabase-kt pull serializer

    /** Mirror of XtreamSettingsViewModel.withOptions (private): shared options + the UA copy. */
    private fun XtreamAccount.withOptions(
        epgUrl: String?,
        dnsProvider: String,
        autoRefreshHours: Int,
        userAgent: String?
    ): XtreamAccount =
        withPlaylistOptions(epgUrl, dnsProvider, autoRefreshHours)
            .copy(userAgent = userAgent?.trim()?.takeIf { it.isNotEmpty() })

    /** Persist one account the way the store does and read it back through the decode normalizer. */
    private fun roundTrip(account: XtreamAccount): XtreamAccount =
        decodeXtreamAccountsJson(gson, gson.toJson(listOf(account))).single()

    @Test
    fun `xtream manual edit preserves user-agent and auto-refresh across save-reopen`() {
        // old account as originally stored (defaults).
        val old = xtreamAccountFromFields("http://host:8080", "u1", "p1", "Panel")!!
        // The form's collected options: UA set, refresh 12h.
        val saved = xtreamAccountFromFields("http://host:8080", "u1", "p1", "Panel")!!
            .withOptions(epgUrl = null, dnsProvider = XtreamAccount.DNS_SYSTEM, autoRefreshHours = 12, userAgent = "MyPlayer/1.0")
            .copy(enabled = old.enabled, contentTypes = old.contentTypes, categorySelections = old.categorySelections)

        val reopened = roundTrip(saved)

        // JUnit: assertEquals(message, expected, actual)
        assertEquals("Xtream manual: user-agent lost on reopen", "MyPlayer/1.0", reopened.userAgent)
        assertEquals("Xtream manual: auto-refresh lost on reopen", 12, reopened.autoRefreshHours)
    }

    @Test
    fun `m3u url edit preserves user-agent (in username) and auto-refresh across save-reopen`() {
        val old = m3uAccountFromUrl("http://host/list.m3u", userAgent = null, name = "List")!!
        // M3U keeps its UA in username; the shared-form UA field is blank for M3U (null).
        val saved = m3uAccountFromUrl("http://host/list.m3u", userAgent = "MyPlayer/1.0", name = "List")!!
            .withOptions(epgUrl = null, dnsProvider = XtreamAccount.DNS_SYSTEM, autoRefreshHours = 12, userAgent = null)
            .copy(enabled = old.enabled, contentTypes = old.contentTypes, categorySelections = old.categorySelections)

        val reopened = roundTrip(saved)

        assertEquals("M3U URL: user-agent (username) lost on reopen", "MyPlayer/1.0", reopened.username)
        assertEquals("M3U URL: auto-refresh lost on reopen", 12, reopened.autoRefreshHours)
    }

    @Test
    fun `xtream paste-link edit preserves user-agent and auto-refresh across save-reopen`() {
        val old = parseXtreamAccount("http://host:8080/get.php?username=u1&password=p1", "Panel")!!
        val saved = parseXtreamAccount("http://host:8080/get.php?username=u1&password=p1", "Panel")!!
            .withOptions(epgUrl = null, dnsProvider = XtreamAccount.DNS_SYSTEM, autoRefreshHours = 12, userAgent = "MyPlayer/1.0")
            .copy(enabled = old.enabled, contentTypes = old.contentTypes, categorySelections = old.categorySelections)

        val reopened = roundTrip(saved)

        assertEquals("Xtream paste-link: user-agent lost on reopen", "MyPlayer/1.0", reopened.userAgent)
        assertEquals("Xtream paste-link: auto-refresh lost on reopen", 12, reopened.autoRefreshHours)
    }

    /**
     * The one place a correctly-saved local value can silently revert: a remote sync pull REPLACES
     * the account list with objects rebuilt from the wire. Both fields must survive push -> pull.
     */
    @Test
    fun `xtream sync push-pull round trip preserves user-agent and auto-refresh`() {
        val local = xtreamAccountFromFields("http://host:8080", "u1", "p1", "Panel")!!
            .withOptions(epgUrl = null, dnsProvider = XtreamAccount.DNS_SYSTEM, autoRefreshHours = 12, userAgent = "MyPlayer/1.0")

        // push -> wire JSON -> (supabase serializer) -> DTO -> pull mapping
        val wire = playlistPushJson(local, sortOrder = 0)
        val dto = json.decodeFromJsonElement(SupabaseIptvPlaylist.serializer(), wire)
        val pulled = dto.toXtreamAccountOrNull()!!

        assertEquals("Xtream sync: user-agent lost across push/pull", "MyPlayer/1.0", pulled.userAgent)
        assertEquals("Xtream sync: auto-refresh lost across push/pull", 12, pulled.autoRefreshHours)
    }

    @Test
    fun `m3u sync push-pull round trip preserves user-agent (username) and auto-refresh`() {
        val local = m3uAccountFromUrl("http://host/list.m3u", userAgent = "MyPlayer/1.0", name = "List")!!
            .withOptions(epgUrl = null, dnsProvider = XtreamAccount.DNS_SYSTEM, autoRefreshHours = 12, userAgent = null)

        val wire = playlistPushJson(local, sortOrder = 0)
        val dto = json.decodeFromJsonElement(SupabaseIptvPlaylist.serializer(), wire)
        val pulled = dto.toXtreamAccountOrNull()!!

        assertEquals("M3U sync: user-agent (username) lost across push/pull", "MyPlayer/1.0", pulled.username)
        assertEquals("M3U sync: auto-refresh lost across push/pull", 12, pulled.autoRefreshHours)
    }

    @Test
    fun `stalker edit preserves user-agent and auto-refresh across save-reopen`() {
        val old = XtreamAccount(
            id = "stalker|http://portal:80|00:1A:79:AA:BB:CC",
            name = "Portal", baseUrl = "http://portal:80", username = "", password = "",
            sourceType = XtreamAccount.SOURCE_STALKER,
            portalUrl = "http://portal:80", macAddress = "00:1A:79:AA:BB:CC"
        )
        val saved = old
            .withOptions(epgUrl = null, dnsProvider = XtreamAccount.DNS_SYSTEM, autoRefreshHours = 12, userAgent = "MyPlayer/1.0")
            .copy(enabled = old.enabled, contentTypes = old.contentTypes, categorySelections = old.categorySelections)

        val reopened = roundTrip(saved)

        assertEquals("Stalker: user-agent lost on reopen", "MyPlayer/1.0", reopened.userAgent)
        assertEquals("Stalker: auto-refresh lost on reopen", 12, reopened.autoRefreshHours)
    }
}
