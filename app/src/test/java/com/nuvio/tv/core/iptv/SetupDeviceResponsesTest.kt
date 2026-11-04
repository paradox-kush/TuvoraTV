package com.nuvio.tv.core.iptv

import kotlinx.serialization.json.Json
import java.security.SecureRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** B205: the device redeem route's answer, the claim RPC's answer, and the token/hash. */
class SetupDeviceResponsesTest {
    private val okBody = """
        {"status":"redeemed","provider":"Acme TV","skipped_addons":false,"playlists":[
          {"playlist_key":"http://h.example:8080|bob","name":"Acme Live","source_type":"xtream","action":"added",
           "base_url":"http://h.example:8080","url":null,"portal_url":null,"username":"bob","password":"pw",
           "mac_address":null,"stalker_username":null,"stalker_password":null,"backup_urls":["http://b.example"],
           "epg_url":"http://e.example/x.xml","user_agent":"UA/1","content_types":["live","movies"]},
          {"playlist_key":"m3u|http://l.example/p.m3u","name":"M3U","source_type":"m3u_url","action":"skipped","reason":"missing_login"}
        ]}
    """.trimIndent()

    @Test
    fun `a 200 device redeem carries every playlist field`() {
        val r = SetupResponses.parseDeviceRedeem(200, okBody) as DeviceRedeemOutcome.Done
        assertEquals("redeemed", r.redeem.status)
        assertEquals("Acme TV", r.redeem.provider)
        assertEquals(2, r.redeem.playlists.size)
        val p = r.redeem.playlists[0]
        assertEquals("http://h.example:8080|bob", p.playlistKey)
        assertEquals("bob", p.username)
        assertEquals("pw", p.password)
        assertEquals(listOf("http://b.example"), p.backupUrls)
        assertEquals("http://e.example/x.xml", p.epgUrl)
        assertEquals("UA/1", p.userAgent)
        assertEquals(listOf("live", "movies"), p.contentTypes)
        assertEquals("added", p.action)
        assertEquals("skipped", r.redeem.playlists[1].action)
        assertEquals("missing_login", r.redeem.playlists[1].reason)
    }

    @Test
    fun `already_redeemed is a success with the same playlists`() {
        val r = SetupResponses.parseDeviceRedeem(200, okBody.replace("\"redeemed\"", "\"already_redeemed\"")) as DeviceRedeemOutcome.Done
        assertEquals("already_redeemed", r.redeem.status)
        assertEquals(2, r.redeem.playlists.size)
    }

    @Test
    fun `a 200 that is not a redeem is unusable`() {
        assertEquals(DeviceRedeemOutcome.Failed(SetupCodeOutcome.Unusable), SetupResponses.parseDeviceRedeem(200, "[]"))
        assertEquals(DeviceRedeemOutcome.Failed(SetupCodeOutcome.Unusable), SetupResponses.parseDeviceRedeem(200, null))
    }

    @Test
    fun `route errors map to the same outcomes as the account redeem`() {
        fun f(status: Int, code: String?, retry: String? = null) =
            (SetupResponses.parseDeviceRedeem(status, code?.let { """{"error":"x","code":"$it"}""" }, retry) as DeviceRedeemOutcome.Failed).outcome
        assertEquals(SetupCodeOutcome.Unusable, f(404, "not_found"))
        assertEquals(SetupCodeOutcome.Unusable, f(409, "already_used"))
        assertEquals(SetupCodeOutcome.Unusable, f(410, "revoked"))
        assertEquals(SetupCodeOutcome.Unusable, f(400, "invalid_code"))
        assertEquals(SetupCodeOutcome.Expired(null), f(410, "expired"))
        assertEquals(SetupCodeOutcome.RateLimited(30), f(429, "rate_limited", "30"))
        assertEquals("upstream failure is retryable", SetupCodeOutcome.Network, f(424, null))
        assertEquals(SetupCodeOutcome.Network, f(503, null))
        assertEquals(SetupCodeOutcome.Network, f(502, "upstream_error"))
    }

    @Test
    fun `claim ok is claimed for redeemed and already_redeemed`() {
        fun parse(s: String) = SetupResponses.parseClaim(Json.parseToJsonElement(s))
        assertEquals(ClaimOutcome.Claimed, parse("""{"ok":true,"status":"redeemed","added":1,"playlists":[]}"""))
        assertEquals(ClaimOutcome.Claimed, parse("""{"ok":true,"status":"already_redeemed"}"""))
    }

    @Test
    fun `claim failures carry the server code`() {
        fun err(code: String) = (SetupResponses.parseClaim(Json.parseToJsonElement("""{"ok":false,"error":"$code"}""")) as ClaimOutcome.Failed).error
        assertEquals(ClaimError.NOT_FOUND, err("not_found"))
        assertEquals(ClaimError.ALREADY_USED, err("already_used"))
        assertEquals(ClaimError.REVOKED, err("revoked"))
        assertEquals(ClaimError.SUSPENDED, err("suspended"))
        assertEquals(ClaimError.UNAVAILABLE, err("unavailable"))
        assertEquals(ClaimError.RATE_LIMITED, err("rate_limited"))
        assertEquals(ClaimError.PROFILE_NOT_FOUND, err("profile_not_found"))
        assertEquals("an unknown code is not a verdict: keep the claim", ClaimError.UNKNOWN, err("whatever"))
        assertEquals(ClaimError.UNKNOWN, (SetupResponses.parseClaim(null) as ClaimOutcome.Failed).error)
    }

    @Test
    fun `a raised claim error uses the first line as the code and transport failures are network`() {
        assertEquals(ClaimOutcome.Failed(ClaimError.REVOKED), SetupResponses.claimFailure(400, "revoked\nDETAIL"))
        assertEquals(ClaimOutcome.Failed(ClaimError.PROFILE_NOT_FOUND), SetupResponses.claimFailure(400, "profile_not_found"))
        assertEquals(ClaimOutcome.Failed(ClaimError.NETWORK), SetupResponses.claimFailure(503, "bad gateway"))
        assertEquals(ClaimOutcome.Failed(ClaimError.NETWORK), SetupResponses.claimFailure(null, null))
        assertEquals("a refused session is not a verdict on the setup", ClaimOutcome.Failed(ClaimError.NETWORK), SetupResponses.claimFailure(401, "JWT expired"))
        assertEquals(ClaimOutcome.Failed(ClaimError.NETWORK), SetupResponses.claimThrowable(java.io.IOException("x")))
    }

    @Test
    fun `the token is 64 lowercase hex from 32 random bytes and the hash is its sha256`() {
        val t = DeviceClaimToken.newToken(SecureRandom())
        assertTrue(t, Regex("^[0-9a-f]{64}$").matches(t))
        assertNotEquals(t, DeviceClaimToken.newToken(SecureRandom()))
        // sha256("abc")
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", DeviceClaimToken.hash("abc"))
        assertTrue(Regex("^[0-9a-f]{64}$").matches(DeviceClaimToken.hash(t)))
    }

    @Test
    fun `the device redeem url sits beside the preview route`() {
        assertEquals("https://tuvora.co/api/s/redeem", ProviderSetupConfig.deviceRedeemUrl("https://tuvora.co/").toString())
    }
}
