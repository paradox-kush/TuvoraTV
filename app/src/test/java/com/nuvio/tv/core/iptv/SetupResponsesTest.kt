package com.nuvio.tv.core.iptv

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Step 2 contract sections 2 to 4: the preview route's answers, redeem bodies and failures. */
class SetupResponsesTest {

    @Test
    fun `a 200 preview becomes Ready`() {
        val o = SetupResponses.parsePreview(200, """{"preview":{"provider_name":"Acme TV","playlists":[{"name":"L","source_type":"xtream"}]}}""")
        assertTrue(o is SetupCodeOutcome.Ready)
        assertEquals("Acme TV", (o as SetupCodeOutcome.Ready).preview.providerName)
    }

    @Test
    fun `a 200 that is not a preview is unusable not a crash`() {
        assertEquals(SetupCodeOutcome.Unusable, SetupResponses.parsePreview(200, "<html>"))
        assertEquals(SetupCodeOutcome.Unusable, SetupResponses.parsePreview(200, null))
        assertEquals(SetupCodeOutcome.Unusable, SetupResponses.parsePreview(200, """{"preview":{}}"""))
    }

    @Test
    fun `error bodies map through the outcome table`() {
        assertEquals(SetupCodeOutcome.Expired(null), SetupResponses.parsePreview(410, """{"error":"expired","code":"expired"}"""))
        assertEquals(SetupCodeOutcome.Unusable, SetupResponses.parsePreview(409, """{"error":"used","code":"used"}"""))
        assertEquals(SetupCodeOutcome.RateLimited(17), SetupResponses.parsePreview(429, """{"error":"rate_limited","code":"rate_limited"}""", "17"))
        assertEquals("a garbage Retry-After is unknown", SetupCodeOutcome.RateLimited(null), SetupResponses.parsePreview(429, "{}", "soon"))
        assertEquals("feature off: 404 with no code", SetupCodeOutcome.Unusable, SetupResponses.parsePreview(404, """{"error":"Not found"}"""))
        assertEquals("a proxy error page is a gateway failure", SetupCodeOutcome.Network, SetupResponses.parsePreview(502, "not json"))
    }

    @Test
    fun `redeem success keeps counts and keys`() {
        val r = SetupResponses.parseRedeem(Json.parseToJsonElement("""
            {"ok":true,"status":"redeemed","profile_index":2,"added":1,"updated":0,"unchanged":0,
             "playlists":[{"playlist_key":"http://h|u","name":"Acme Live","action":"added"}],"added_addons":1,"skipped_addons":0}"""))
        assertEquals(
            RedeemOutcome.Done(RedeemResult("redeemed", 2, 1, 0, 0, listOf("http://h|u"), listOf("Acme Live"))), r,
        )
    }

    @Test
    fun `an idempotent re-redeem by the same account is success`() {
        val r = SetupResponses.parseRedeem(Json.parseToJsonElement("""{"ok":true,"status":"already_redeemed","profile_index":1,"added":0,"updated":0,"unchanged":0,"playlists":[]}"""))
        assertTrue(r is RedeemOutcome.Done)
        assertEquals("already_redeemed", (r as RedeemOutcome.Done).result.status)
    }

    @Test
    fun `redeem failures use the shared mapping`() {
        fun fail(err: String) = (SetupResponses.parseRedeem(Json.parseToJsonElement("""{"ok":false,"error":"$err"}""")) as RedeemOutcome.Failed).outcome
        assertEquals(SetupCodeOutcome.ProfileNotFound, fail("profile_not_found"))
        assertEquals(SetupCodeOutcome.Unusable, fail("already_used"))
        assertEquals(SetupCodeOutcome.Expired(null), fail("expired"))
        assertEquals(SetupCodeOutcome.Unusable, SetupResponses.parseRedeem(null).let { (it as RedeemOutcome.Failed).outcome })
    }

    @Test
    fun `transport failures are Network and raised server codes are mapped`() {
        assertEquals(SetupCodeOutcome.Network, SetupResponses.forThrowable(UnknownHostException("x")))
        assertEquals(SetupCodeOutcome.Network, SetupResponses.forThrowable(SocketTimeoutException()))
        assertEquals(SetupCodeOutcome.Network, SetupResponses.forThrowable(IOException("boom")))
        assertEquals(SetupCodeOutcome.Network, SetupResponses.forThrowable(RuntimeException("Unable to resolve host \"x\"")))
        assertEquals(SetupCodeOutcome.NeedsSignIn, SetupResponses.forThrowable(RuntimeException("anonymous_not_allowed")))
        assertEquals(SetupCodeOutcome.NeedsSignIn, SetupResponses.forThrowable(RuntimeException("P0001: not_authenticated")))
        assertEquals(SetupCodeOutcome.Unusable, SetupResponses.forThrowable(RuntimeException("something else entirely")))
    }

    @Test
    fun `the preview url carries the formatted code and nothing else`() {
        val url = ProviderSetupConfig.previewUrl("abcdefghjkmn", base = "https://tuvora.co")
        assertEquals("https://tuvora.co/api/s/preview?code=TUV-ABCD-EFGH-JKMN", url.toString())
        assertEquals("http://10.0.2.2:3000/api/s/preview?code=TUV-ABCD-EFGH-JKMN",
            ProviderSetupConfig.previewUrl("ABCDEFGHJKMN", base = "http://10.0.2.2:3000/").toString())
    }

    private fun redeem(json: String) = (SetupResponses.parseRedeem(Json.parseToJsonElement(json)) as RedeemOutcome.Done).result

    @Test
    fun `a redeem that skipped every service added nothing and says why`() {
        val noLogin = redeem("""{"ok":true,"status":"redeemed","profile_index":1,"added":0,"updated":0,"unchanged":0,
            "playlists":[{"playlist_key":null,"name":"Live","action":"skipped","reason":"missing_login"}]}""")
        assertEquals(listOf("missing_login"), noLogin.skippedReasons)
        assertEquals(RedeemResultPolicy.Kind.NOTHING_NO_LOGIN, RedeemResultPolicy.classify(noLogin))
        val badUrl = redeem("""{"ok":true,"status":"redeemed","added":0,"updated":0,"unchanged":0,
            "playlists":[{"playlist_key":null,"name":"Live","action":"skipped","reason":"invalid_url"}]}""")
        assertEquals(RedeemResultPolicy.Kind.NOTHING_BAD_ADDRESS, RedeemResultPolicy.classify(badUrl))
    }

    @Test
    fun `added updated and already-set-up redeems are classified`() {
        assertEquals(RedeemResultPolicy.Kind.ADDED, RedeemResultPolicy.classify(redeem("""{"ok":true,"status":"redeemed","added":1,"updated":0,"unchanged":0,"playlists":[{"playlist_key":"k","name":"L","action":"added"}]}""")))
        assertEquals(RedeemResultPolicy.Kind.ADDED, RedeemResultPolicy.classify(redeem("""{"ok":true,"status":"redeemed","added":0,"updated":1,"unchanged":0,"playlists":[{"playlist_key":"k","name":"L","action":"updated"}]}""")))
        assertEquals(RedeemResultPolicy.Kind.ALREADY_SET_UP, RedeemResultPolicy.classify(redeem("""{"ok":true,"status":"already_redeemed","added":0,"updated":0,"unchanged":0,"playlists":[]}""")))
        assertEquals(RedeemResultPolicy.Kind.ALREADY_SET_UP, RedeemResultPolicy.classify(redeem("""{"ok":true,"status":"redeemed","added":0,"updated":0,"unchanged":1,"playlists":[{"playlist_key":"k","name":"L","action":"unchanged"}]}""")))
        assertEquals(RedeemResultPolicy.Kind.NOTHING, RedeemResultPolicy.classify(redeem("""{"ok":true,"status":"redeemed","added":0,"updated":0,"unchanged":0,"playlists":[]}""")))
    }
}
