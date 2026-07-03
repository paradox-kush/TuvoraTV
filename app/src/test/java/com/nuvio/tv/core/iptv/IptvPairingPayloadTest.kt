package com.nuvio.tv.core.iptv

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/**
 * P5 IPTV pairing: the pairing-payload -> XtreamAccount mapping (the terminal action of a received
 * pairing) plus the code-format and secret-hash shape the TV uses to create/claim a session. Pure —
 * no device or network. Payload keys mirror the `sync_push_iptv_playlists` row shape the web form
 * submits (see the backend migration 20260703000000_iptv_pairing.sql + XtreamAccountSyncService).
 */
class IptvPairingPayloadTest {

    // --- payload -> XtreamAccount --------------------------------------------

    @Test
    fun `xtream payload maps to a browsable xtream account with the id and options`() {
        val payload = buildJsonObject {
            put("source_type", "xtream")
            put("name", "My Panel")
            put("base_url", "http://host:8080")
            put("username", "u1")
            put("password", "p1")
            put("epg_url", "http://epg.example/xmltv.php")
            put("dns_provider", "cloudflare")
            put("auto_refresh_hours", 48)
            put("content_types", buildJsonArray { add("live"); add("movies") })
        }

        val account = pairingPayloadToXtreamAccount(payload)
        assertNotNull(account)
        account!!
        assertEquals(XtreamAccount.SOURCE_XTREAM, account.sourceType)
        assertEquals("http://host:8080|u1", account.id)   // stable id = base|user
        assertEquals("My Panel", account.name)
        assertEquals("http://host:8080", account.baseUrl)
        assertEquals("u1", account.username)
        assertEquals("p1", account.password)
        assertEquals("http://epg.example/xmltv.php", account.epgUrl)
        assertEquals(XtreamAccount.DNS_CLOUDFLARE, account.dnsProvider)
        assertEquals(48, account.autoRefreshHours)
        assertEquals(setOf("live", "movies"), account.contentTypes)
    }

    @Test
    fun `xtream payload missing credentials is unusable`() {
        val noPass = buildJsonObject {
            put("source_type", "xtream")
            put("base_url", "http://host:8080")
            put("username", "u1")
        }
        assertNull(pairingPayloadToXtreamAccount(noPass))

        val noBase = buildJsonObject {
            put("source_type", "xtream")
            put("username", "u1")
            put("password", "p1")
        }
        assertNull(pairingPayloadToXtreamAccount(noBase))
    }

    @Test
    fun `url payload maps to an m3u-url account keeping the full playlist url`() {
        val payload = buildJsonObject {
            put("source_type", "url")
            put("name", "My M3U")
            put("url", "http://host:9000/get.php?username=x&password=y&type=m3u_plus")
        }

        val account = pairingPayloadToXtreamAccount(payload)
        assertNotNull(account)
        account!!
        assertEquals(XtreamAccount.SOURCE_URL, account.sourceType)
        assertEquals("My M3U", account.name)
        // Full URL is preserved verbatim in baseUrl (M3UClient fetches it as-is).
        assertEquals("http://host:9000/get.php?username=x&password=y&type=m3u_plus", account.baseUrl)
        assertTrue("m3u id derived from scheme+host+port+path", account.id.startsWith("m3u:"))
    }

    @Test
    fun `url payload also accepts the playlist url under base_url`() {
        val payload = buildJsonObject {
            put("source_type", "url")
            put("base_url", "http://host/playlist.m3u")
        }
        val account = pairingPayloadToXtreamAccount(payload)
        assertNotNull(account)
        assertEquals(XtreamAccount.SOURCE_URL, account!!.sourceType)
        assertEquals("http://host/playlist.m3u", account.baseUrl)
    }

    @Test
    fun `stalker payload is saved even though it cannot be browsed yet`() {
        val payload = buildJsonObject {
            put("source_type", "stalker")
            put("name", "Portal")
            put("portal_url", "http://portal.example/c/")
            put("mac_address", "00:1A:79:AA:BB:CC")
        }

        val account = pairingPayloadToXtreamAccount(payload)
        // Spec §4: unbrowsable source types must still persist (they sync + work once implemented).
        assertNotNull(account)
        account!!
        assertEquals(XtreamAccount.SOURCE_STALKER, account.sourceType)
        assertEquals("Portal", account.name)
        assertEquals("http://portal.example/c/", account.baseUrl)     // portal_url stashed in baseUrl
        assertEquals("00:1A:79:AA:BB:CC", account.username)           // mac_address stashed in username
    }

    @Test
    fun `disabled flag and category selections are applied`() {
        val payload = buildJsonObject {
            put("source_type", "xtream")
            put("base_url", "http://host:8080")
            put("username", "u1")
            put("password", "p1")
            put("enabled", false)
            put("category_selections", buildJsonObject {
                put("live", buildJsonArray { add("1"); add("2") })
                put("movies", buildJsonArray { })
            })
        }
        val account = pairingPayloadToXtreamAccount(payload)!!
        assertEquals(false, account.enabled)
        assertEquals(listOf("1", "2"), account.categorySelections.live)
        assertEquals(emptyList<String>(), account.categorySelections.movies)
        assertNull(account.categorySelections.series)
    }

    @Test
    fun `null missing or source-type-less payloads yield null (no crash)`() {
        assertNull(pairingPayloadToXtreamAccount(null))
        assertNull(pairingPayloadToXtreamAccount(JsonNull))
        assertNull(pairingPayloadToXtreamAccount(JsonPrimitive("not-an-object")))
        assertNull(pairingPayloadToXtreamAccount(buildJsonObject { put("base_url", "http://x") }))
        assertNull(pairingPayloadToXtreamAccount(buildJsonObject { put("source_type", "") }))
    }

    // --- code format ---------------------------------------------------------

    @Test
    fun `generated pairing code matches the backend format`() {
        val random = SecureRandom()
        repeat(200) {
            val code = generatePairingCode(random)
            assertEquals(6, code.length)
            assertTrue("code '$code' must be ^[A-Z0-9]{6}$", PAIRING_CODE_REGEX.matches(code))
        }
    }

    @Test
    fun `pairing web url appends the code as a query param`() {
        assertEquals(
            "https://paradox-kush.github.io/iptv-pairing/?code=ABC123",
            pairingWebUrl("https://paradox-kush.github.io/iptv-pairing/", "ABC123")
        )
        // Tolerates an existing query string.
        assertEquals(
            "https://example.com/pair?ref=1&code=XYZ789",
            pairingWebUrl("https://example.com/pair?ref=1", "XYZ789")
        )
    }

    // --- secret hashing shape ------------------------------------------------

    @Test
    fun `secret hash is deterministic lowercase 64-char hex`() {
        val hash = sha256Hex("device-secret-abc")
        assertEquals(64, hash.length)
        assertTrue("sha256 hex must be lowercase hex", hash.matches(Regex("^[0-9a-f]{64}$")))
        // Deterministic for the same input; different for a different input.
        assertEquals(hash, sha256Hex("device-secret-abc"))
        assertTrue(hash != sha256Hex("device-secret-abd"))
    }

    @Test
    fun `known sha256 vector`() {
        // sha256("abc") — pins the algorithm/encoding so a refactor can't silently change it.
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256Hex("abc")
        )
    }

    @Test
    fun `generated secret is non-empty and hashes to the expected shape`() {
        val secret = generatePairingSecret()
        assertTrue(secret.isNotBlank())
        assertTrue(sha256Hex(secret).matches(Regex("^[0-9a-f]{64}$")))
    }
}
