package com.nuvio.tv.core.iptv

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Step 2 contract section 5: contact links follow the web's `normalizeSupport` rules; parsing is defensive. */
class ProviderSetupModelsTest {

    private fun support(json: String) = ProviderSupport.fromJson(Json.parseToJsonElement(json))

    @Test
    fun `contacts become the contract's links and only the ones the provider set`() {
        val s = support("""{"whatsapp":"+44 7700 900123","telegram":"@acme_tv","email":"help@acme.tv","website":"https://acme.tv/help"}""")
        assertEquals(
            listOf(
                ProviderContact(ProviderContact.Kind.TELEGRAM, "@acme_tv", "https://t.me/acme_tv"),
                ProviderContact(ProviderContact.Kind.WHATSAPP, "+447700900123", "https://wa.me/447700900123"),
                ProviderContact(ProviderContact.Kind.EMAIL, "help@acme.tv", "mailto:help@acme.tv"),
                ProviderContact(ProviderContact.Kind.WEBSITE, "acme.tv", "https://acme.tv/help"),
            ),
            s.contacts(),
        )
        assertEquals(1, support("""{"telegram":"acme_tv"}""").contacts().size)
        assertTrue(support("{}").isEmpty)
        assertTrue(ProviderSupport.fromJson(null).isEmpty)
    }

    @Test
    fun `values that do not normalize are dropped not shown`() {
        assertNull(ProviderSupport.normalizeWhatsapp("12345"))                   // too short
        assertNull(ProviderSupport.normalizeTelegram("abc"))                      // under 5
        assertEquals("acme_tv", ProviderSupport.normalizeTelegram("https://t.me/acme_tv"))
        assertNull("no pre-filled cc/subject in a mailto", ProviderSupport.normalizeEmail("a@b.co?cc=x@y.z"))
        assertNull(ProviderSupport.normalizeWebsite("javascript:alert(1)"))
        assertNull(ProviderSupport.normalizeWebsite("ftp://acme.tv"))
        assertNull("no credentials in a link", ProviderSupport.normalizeWebsite("https://user:pw@acme.tv"))
        assertNull(ProviderSupport.normalizeWebsite("https://localhost"))
        assertNull(ProviderSupport.normalizeWebsite("https://acme.tv/a b"))
        assertNull("https only", ProviderSupport.normalizeWebsite("http://acme.tv"))
        assertEquals("https://acme.tv", ProviderSupport.normalizeWebsite("https://acme.tv"))
    }

    @Test
    fun `a preview parses names only and ignores unknown keys`() {
        val p = SetupPreview.fromJson(Json.parseToJsonElement("""
            {"preview":{"provider_name":"Acme TV","support":{"telegram":"acme_tv"},"package_name":"Gold",
              "playlists":[{"name":"Acme Live","source_type":"xtream","server_url":"http://secret"},{"name":"Acme M3U","source_type":"m3u_url"}],
              "addons":["Cinemeta"],"status":"open","expires_at":"2026-12-01T00:00:00Z","surprise":123}}"""))!!
        assertEquals("Acme TV", p.providerName)
        assertEquals("Gold", p.packageName)
        assertEquals(listOf(PreviewPlaylist("Acme Live", "xtream"), PreviewPlaylist("Acme M3U", "m3u_url")), p.playlists)
        assertEquals(listOf("Cinemeta"), p.addons)
        assertEquals("acme_tv", p.support.telegram)
        assertEquals("2026-12-01T00:00:00Z", p.expiresAt)
    }

    @Test
    fun `a preview without a provider name is no preview`() {
        assertNull(SetupPreview.fromJson(Json.parseToJsonElement("""{"preview":{"playlists":[]}}""")))
        assertNull(SetupPreview.fromJson(Json.parseToJsonElement("""{"nope":1}""")))
        assertNull(SetupPreview.fromJson(Json.parseToJsonElement("[]")))
    }

    @Test
    fun `managed playlists parse with an absent service_updated_at as unknown`() {
        val list = ManagedPlaylistInfo.listFromJson(Json.parseToJsonElement("""
            [{"playlist_key":"k1","provider_name":"Acme TV","service_name":"Main","support":{"email":"help@acme.tv"}},
             {"playlist_key":"k2","provider_name":"Acme TV","service_name":null,"service_updated_at":"2026-10-01T10:00:00Z","support":{}},
             {"provider_name":"no key"}, 7]"""))
        assertEquals(listOf("k1", "k2"), list.map { it.playlistKey })
        assertNull(list[0].serviceUpdatedAt)
        assertEquals("2026-10-01T10:00:00Z", list[1].serviceUpdatedAt)
        assertNull(list[1].serviceName)
        assertFalse(list[0].support.isEmpty)
    }

    @Test
    fun `provider text loses bidi and format characters and is capped`() {
        assertEquals("Acme TV", ProviderText.clean("\u202EAcme\u200B TV\u2066"))
        assertEquals("a b", ProviderText.clean("a\u0000\n\t b"))
        assertEquals("", ProviderText.clean("\u202E\u200F\uFEFF"))
        assertEquals(80, ProviderText.clean("x".repeat(200)).length)
        assertEquals("a emoji \uD83D\uDE00 stays", ProviderText.clean("a emoji \uD83D\uDE00 stays"))
    }

    @Test
    fun `a preview is sanitised and capped`() {
        val many = (1..50).joinToString(",") { """{"name":"P$it","source_type":"xtream"}""" }
        val adds = (1..50).joinToString(",") { "\"Addon$it\"" }
        val p = SetupPreview.fromJson(Json.parseToJsonElement("""{"preview":{"provider_name":"\u202EStar\u200BShare","package_name":"G\u202Eold",
            "playlists":[$many,{"source_type":"m3u_url"}],"addons":[$adds,"https://evil.example/manifest.json"]}}"""))!!
        assertEquals("StarShare", p.providerName)
        assertEquals("Gold", p.packageName)
        assertEquals(20, p.playlists.size)
        assertEquals(20, p.addons.size)
        assertTrue(p.addons.none { "://" in it })
        val nameless = SetupPreview.fromJson(Json.parseToJsonElement("""{"preview":{"provider_name":"A","playlists":[{"source_type":"xtream"}]}}"""))!!
        assertEquals("Playlist", nameless.playlists.single().name)
    }

    @Test
    fun `a provider name that is only invisible characters is no preview`() {
        assertNull(SetupPreview.fromJson(Json.parseToJsonElement("""{"preview":{"provider_name":"\u202E\u200B"}}""")))
    }

    @Test
    fun `managed provider names are sanitised too`() {
        val l = ManagedPlaylistInfo.listFromJson(Json.parseToJsonElement("""[{"playlist_key":"k","provider_name":"\u202EAcme","service_name":"M\u200Bain","support":{}}]"""))
        assertEquals("Acme", l.single().providerName)
        assertEquals("Main", l.single().serviceName)
    }
}
