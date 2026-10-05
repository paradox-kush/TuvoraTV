package com.nuvio.tv.core.iptv.identity

import com.nuvio.tv.core.iptv.identity.M3uIdentity.Login
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * B64 — the login-free M3U identity's golden vectors. The tables below are byte-identical in NuvioMobile/
 * NuvioDesktop (`M3uIdentityTest`, kotlin.test), NuvioTV (this file, JUnit), nuvio-web (`m3uIdentity.test.ts`) and the SQL
 * port (nuvio-backend `supabase/tests/m3u_identity_golden.sql`); the expected values come from the
 * reference implementation research/b64-m3u-identity-ref.py.
 */
class M3uIdentityTest {

    private data class LF(val url: String, val login: Login?, val expected: String)
    private data class PK(val url: String, val expected: String?)
    private data class SID(val url: String, val login: Login?, val expected: Int)

    // ---- GOLDEN VECTORS: loginFree(url, login) ----
    private val loginFree = listOf(
        LF("http://h.com:8080/live/alice/s3cret/123.ts", Login("alice", "s3cret"), "http://h.com:8080/live/123.ts"),
        LF("http://h.com:8080/live/alice/s3cret/123.ts", null, "http://h.com:8080/live/123.ts"),
        LF("http://h.com:8080/live/bob/other/123.ts", Login("alice", "s3cret"), "http://h.com:8080/live/123.ts"),
        LF("http://h.com/movie/2020/action/film.mp4", null, "http://h.com/movie/2020/action/film.mp4"),
        LF("http://h.com:8080/alice/s3cret/123", Login("alice", "s3cret"), "http://h.com:8080/123"),
        LF("http://h.com:8080/alice/s3cret/123", null, "http://h.com:8080/alice/s3cret/123"),
        LF("http://h.com/series/alice/s3cret/9876.mkv", Login("alice", "s3cret"), "http://h.com/series/9876.mkv"),
        LF("http://h.com/timeshift/alice/s3cret/120/2024-01-01:10-00/55.ts", Login("alice", "s3cret"), "http://h.com/timeshift/120/2024-01-01:10-00/55.ts"),
        LF("http://h.com/get.php?username=alice&password=s3cret&type=m3u_plus&output=ts", Login("alice", "s3cret"), "http://h.com/get.php?type=m3u_plus&output=ts"),
        LF("http://alice:s3cret@h.com/list.m3u", Login("alice", "s3cret"), "http://h.com/list.m3u"),
        LF("http://h.com/list.m3u?user=a&pass=b", Login("a", "b"), "http://h.com/list.m3u"),
        LF("HTTPS://Example.com/List.m3u", null, "HTTPS://Example.com/List.m3u"),
        LF("http://h.com/a.m3u?x=1#frag", null, "http://h.com/a.m3u?x=1#frag"),
        LF("http://h.com/a.m3u?", null, "http://h.com/a.m3u?"),
        LF("http://h.com/play?token=abc&Username=x", Login("x", null), "http://h.com/play?token=abc"),
        LF("rtmp://h.com/live/alice/s3cret/1", Login("alice", "s3cret"), "rtmp://h.com/live/1"),
        LF("  http://h.com/LIVE/alice/s3cret/7.m3u8  ", Login("alice", "s3cret"), "http://h.com/LIVE/7.m3u8"),
        LF("http://h.com/live/alice/s3cret/12a.ts", null, "http://h.com/live/alice/s3cret/12a.ts"),
        LF("http://h.com/x.m3u?password=p&&a=1", Login(null, "p"), "http://h.com/x.m3u?&a=1"),
        LF("udp://@239.0.0.1:1234", null, "udp://@239.0.0.1:1234"),
        LF("", null, ""),
    )

    // ---- GOLDEN VECTORS: playlistKey(url) ----
    private val playlistKeys = listOf(
        PK("http://h.com/get.php?username=alice&password=s3cret&type=m3u_plus&output=ts", "m3u|http://h.com/get.php?type=m3u_plus&output=ts|u872213e7"),
        PK("http://h.com/get.php?username=alice&password=CHANGED&type=m3u_plus&output=ts", "m3u|http://h.com/get.php?type=m3u_plus&output=ts|u872213e7"),
        PK("http://h.com/get.php?username=bob&password=s3cret&type=m3u_plus&output=ts", "m3u|http://h.com/get.php?type=m3u_plus&output=ts|u86c6a0d4"),
        PK("example.com/list.m3u", "m3u|http://example.com/list.m3u"),
        PK("HTTPS://Example.com/List.m3u", "m3u|HTTPS://Example.com/List.m3u"),
        PK("http://alice:s3cret@h.com/list.m3u", "m3u|http://h.com/list.m3u|u872213e7"),
        PK("http://h.com/l.m3u?password=x", "m3u|http://h.com/l.m3u"),
        PK("http://h.com/list.m3u?user=a&pass=b", "m3u|http://h.com/list.m3u|ue40c292c"),
        PK("   ", null),
        PK("http://h.com/get.php?username=%C3%A9lise&password=x", "m3u|http://h.com/get.php|u4e274660"),
        PK("http://h.com/a.m3u?", "m3u|http://h.com/a.m3u?"),
    )

    // ---- GOLDEN VECTORS: itemSid(url, login) (FNV-1a over UTF-16 units, 31-bit) ----
    private val sids = listOf(
        SID("http://h.com:8080/live/alice/s3cret/123.ts", Login("alice", "s3cret"), 1241157623),
        SID("http://h.com/movie/2020/action/film.mp4", null, 1556087993),
        SID("http://h.com/x/é/ü.ts", null, 1780950273),
        SID("http://h.com/😀.ts", null, 1929916269),
    )

    @Test
    fun loginFreeVectors() {
        for (v in loginFree) assertEquals("loginFree(${v.url}, ${v.login})", v.expected, M3uIdentity.loginFree(v.url, v.login))
    }

    @Test
    fun playlistKeyVectors() {
        for (v in playlistKeys) assertEquals("playlistKey(${v.url})", v.expected, M3uIdentity.playlistKey(v.url))
    }

    @Test
    fun itemSidVectors() {
        for (v in sids) assertEquals("itemSid(${v.url})", v.expected.toLong(), M3uIdentity.itemSid(v.url, v.login).toLong())
    }

    @Test
    fun loginOfReadsQueryThenUserInfo() {
        assertEquals(Login("alice", "s3cret"), M3uIdentity.loginOf("http://h.com/get.php?username=alice&password=s3cret"))
        assertEquals(Login("alice", "s3cret"), M3uIdentity.loginOf("http://alice:s3cret@h.com/l.m3u"))
        assertEquals(null, M3uIdentity.loginOf("http://h.com/l.m3u"))
        assertEquals(Login(null, "p"), M3uIdentity.loginOf("http://h.com/l.m3u?Password=p"))
    }

    @Test
    fun passwordChangeKeepsEveryId() {
        val before = "http://h.com/get.php?username=alice&password=old&type=m3u_plus"
        val after = "http://h.com/get.php?username=alice&password=new&type=m3u_plus"
        assertEquals(M3uIdentity.playlistKey(before), M3uIdentity.playlistKey(after))
        assertEquals(
            M3uIdentity.itemSid("http://h.com/live/alice/old/42.ts", M3uIdentity.loginOf(before)),
            M3uIdentity.itemSid("http://h.com/live/alice/new/42.ts", M3uIdentity.loginOf(after)),
        )
    }

    @Test
    fun twoAccountsOnOnePanelStayDistinct() {
        assertNotEquals(
            M3uIdentity.playlistKey("http://h.com/get.php?username=alice&password=x"),
            M3uIdentity.playlistKey("http://h.com/get.php?username=bob&password=x"),
        )
    }

    @Test
    fun aUrlWithoutLoginKeepsTheStep0Key() {
        // Every pre-B64 key of a login-free URL is unchanged, so only login-bearing playlists re-key.
        for (u in listOf("example.com/list.m3u", "HTTPS://Example.com/List.m3u", "http://h.com/a.m3u?x=1&y=2", "http://h.com:80/a.m3u")) {
            assertEquals(u, "m3u|" + com.nuvio.tv.core.iptv.PlaylistKey.withHttpScheme(u.trim()), M3uIdentity.playlistKey(u))
        }
    }

    @Test
    fun noLoginInAnyKeyOrId() {
        val url = "http://alice:s3cret@h.com/get.php?username=alice&password=s3cret"
        val key = M3uIdentity.playlistKey(url)!!
        assertFalse(key, "s3cret" in key || "alice" in key)
    }
}
