package com.nuvio.tv.core.iptv

import com.nuvio.tv.data.remote.api.XtreamApi
import com.nuvio.tv.data.remote.dto.FlexIntAdapter
import com.nuvio.tv.data.remote.dto.XtreamLiveStreamDto
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.util.Base64

class XtreamClientTest {

    private val acc = XtreamAccount(
        id = "http://host:8080",
        name = "Panel",
        baseUrl = "http://host:8080",
        username = "u s+r",          // space + reserved char -> must be encoded
        password = "p@ss"
    )

    @Test
    fun `liveChannels builds player_api url and ts stream url`() = runTest {
        val api = mockk<XtreamApi>()
        val urlSlot = slot<String>()
        coEvery { api.getLiveStreams(capture(urlSlot)) } returns Response.success(
            listOf(XtreamLiveStreamDto(num = 1, name = "BBC", streamId = 42, streamIcon = "", epgChannelId = "bbc", categoryId = "3", tvArchive = 1))
        )

        // acc uses system DNS, so apiFor(acc) returns this mock api (no DoH Retrofit is built) — the
        // extra client/moshi/dns are inert here.
        val channels = XtreamClient(
            api,
            okhttp3.OkHttpClient(),
            Moshi.Builder().add(FlexIntAdapter).add(KotlinJsonAdapterFactory()).build(),
            com.nuvio.tv.core.iptv.dns.PlaylistDns(),
        ).liveChannels(acc).getOrThrow()

        // request URL: encoded creds, player_api.php, correct action
        val reqUrl = urlSlot.captured
        assertTrue(reqUrl, reqUrl.startsWith("http://host:8080/player_api.php?"))
        assertTrue(reqUrl, reqUrl.contains("action=get_live_streams"))
        assertTrue(reqUrl, reqUrl.contains("username=u%20s%2Br"))   // space->%20, +->%2B
        assertTrue(reqUrl, reqUrl.contains("password=p%40ss"))

        // mapped channel + stream url. Path segments keep RFC-3986-legal '+'/'@' literal
        // (space still -> %20; '/' etc. would still be encoded). Query encoding above differs.
        assertEquals(1, channels.size)
        assertEquals("http://host:8080/live/u%20s+r/p@ss/42.ts", channels[0].streamUrl)
        assertTrue(channels[0].hasArchive)
    }

    @Test
    fun `FlexInt tolerates int, quoted string, and bool across panels`() {
        val moshi = Moshi.Builder().add(FlexIntAdapter).add(KotlinJsonAdapterFactory()).build()
        val adapter = moshi.adapter(XtreamLiveStreamDto::class.java)

        // panel A: numbers as ints, tv_archive as int
        val a = adapter.fromJson("""{"num":1,"stream_id":42,"name":"A","category_id":"3","tv_archive":1}""")!!
        assertEquals(42, a.streamId)
        assertEquals(1, a.tvArchive)

        // panel B: same fields as quoted strings, tv_archive as bool
        val b = adapter.fromJson("""{"num":"1","stream_id":"42","name":"B","category_id":"3","tv_archive":true}""")!!
        assertEquals(42, b.streamId)
        assertEquals(1, b.tvArchive)

        // garbage/empty id -> null, doesn't throw
        val c = adapter.fromJson("""{"stream_id":"","name":"C"}""")!!
        assertEquals(null, c.streamId)
    }

    @Test
    fun `parseXtreamAccount extracts host, port, creds from m3u and player_api urls`() {
        // get.php M3U with :8080 -> port preserved
        val a = parseXtreamAccount("http://provider.example.com:8080/get.php?username=user1&password=pass1&type=m3u_plus&output=mpegts")!!
        assertEquals("http://provider.example.com:8080", a.baseUrl)
        assertEquals("user1", a.username)
        assertEquals("pass1", a.password)

        // default http port -> omitted from baseUrl
        val b = parseXtreamAccount("http://panel.example.net/get.php?username=u1&password=p1&type=m3u_plus&output=ts")!!
        assertEquals("http://panel.example.net", b.baseUrl)
        assertEquals("panel.example.net", b.name)

        // player_api form works too; custom name honored
        val c = parseXtreamAccount("http://host.example.org/player_api.php?username=demo&password=secret", name = "Home")!!
        assertEquals("http://host.example.org", c.baseUrl)
        assertEquals("Home", c.name)

        // missing creds / non-url -> null
        assertEquals(null, parseXtreamAccount("http://panel.example.net/get.php?type=m3u_plus"))
        assertEquals(null, parseXtreamAccount("not a url"))
    }

    @Test
    fun `xtreamAccountFromFields normalizes server, defaults http, requires creds`() {
        // bare host:port -> http scheme added, port kept
        val a = xtreamAccountFromFields("host.example.org:8080", "demo", "secret", null)!!
        assertEquals("http://host.example.org:8080", a.baseUrl)
        assertEquals("demo", a.username)
        assertEquals("secret", a.password)
        assertEquals("host.example.org", a.name)

        // full url with path -> path stripped, default port omitted, custom name kept
        val b = xtreamAccountFromFields("http://panel.example.net/c/", "u", "p", "Home")!!
        assertEquals("http://panel.example.net", b.baseUrl)
        assertEquals("Home", b.name)

        // missing creds -> null
        assertEquals(null, xtreamAccountFromFields("http://panel.example.net", "", "p", null))
        assertEquals(null, xtreamAccountFromFields("", "u", "p", null))
    }

    @Test
    fun `decodeXtreamBase64 decodes, passes through garbage, empties null`() {
        val enc = Base64.getEncoder().encodeToString("News at Ten".toByteArray())
        assertEquals("News at Ten", decodeXtreamBase64(enc))
        assertEquals("", decodeXtreamBase64(null))
        assertEquals("", decodeXtreamBase64("   "))
    }
}
