package com.nuvio.tv.core.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * TV twin of the mobile/desktop policy test. NOTE the assertion order: JUnit is
 * `assertEquals(message, expected, actual)` — the reverse of commonTest's kotlin.test.
 *
 * Regression cover for a real support loop: a Cloudflare-fronted Xtream panel answered the *stream*
 * request with HTTP 456 while the same account played in another IPTV app. Before this, an Xtream
 * playlist had no way to pin an honest client User-Agent — the resolver only honored one for M3U —
 * so the stream always went out under the spoofed-Chrome default and stayed blocked.
 */
class StreamUserAgentPolicyTest {

    private fun account(
        sourceType: String,
        username: String = "",
        userAgent: String? = null,
    ) = XtreamAccount(
        id = "id",
        name = "name",
        baseUrl = "http://host",
        username = username,
        password = "pass",
        sourceType = sourceType,
        userAgent = userAgent,
    )

    // --- Xtream: the newly-honored path (the bug this closes) -----------------

    @Test
    fun `an Xtream playlist sends its pinned User-Agent`() {
        val ua = "VLC/3.0.20 LibVLC/3.0.20"
        assertEquals(
            "the per-playlist UA reaches the stream fetch",
            ua,
            StreamUserAgentPolicy.resolve(account(XtreamAccount.SOURCE_XTREAM, username = "realuser", userAgent = ua)),
        )
    }

    @Test
    fun `an Xtream playlist with no override falls back to the engine default`() {
        assertNull(
            "null means 'use DEFAULT_STREAM_USER_AGENT'",
            StreamUserAgentPolicy.resolve(account(XtreamAccount.SOURCE_XTREAM, username = "realuser", userAgent = null)),
        )
    }

    @Test
    fun `a blank Xtream override is treated as no override`() {
        assertNull(
            "whitespace-only UA must not be sent",
            StreamUserAgentPolicy.resolve(account(XtreamAccount.SOURCE_XTREAM, username = "realuser", userAgent = "   ")),
        )
    }

    @Test
    fun `an Xtream username is never leaked as the User-Agent`() {
        // username here is a real credential, not a UA stash — it must never become the UA.
        assertNull(
            "Xtream reads its UA from userAgent, never from the credential username",
            StreamUserAgentPolicy.resolve(account(XtreamAccount.SOURCE_XTREAM, username = "55ab3b", userAgent = null)),
        )
    }

    @Test
    fun `a pinned Xtream override is trimmed`() {
        assertEquals(
            "surrounding whitespace is stripped so the header is clean",
            "TiviMate/4.7.0",
            StreamUserAgentPolicy.resolve(account(XtreamAccount.SOURCE_XTREAM, userAgent = "  TiviMate/4.7.0  ")),
        )
    }

    // --- M3U: the pre-existing path keeps working -----------------------------

    @Test
    fun `an M3U URL playlist keeps sending the UA stashed in its username slot`() {
        assertEquals(
            "M3U URL playlists carry the UA in username",
            "MyPlayer/1.0",
            StreamUserAgentPolicy.resolve(account(XtreamAccount.SOURCE_URL, username = "MyPlayer/1.0")),
        )
    }

    @Test
    fun `an M3U file playlist with no UA sends none`() {
        assertNull(
            "empty username slot = no override",
            StreamUserAgentPolicy.resolve(account(XtreamAccount.SOURCE_FILE, username = "")),
        )
    }
}
