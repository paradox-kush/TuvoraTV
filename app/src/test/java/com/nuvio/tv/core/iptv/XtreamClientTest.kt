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

    /**
     * Per-programme has_archive (get_simple_data_table rows) — the panel marking, recording by
     * recording, what it kept. FlexInt already coerces the shapes; pinned anyway because a wrong
     * decode here silently turns "the panel spoke" into "the panel was silent".
     */
    @Test
    fun `per-programme has_archive parses from int, string and absent`() {
        val moshi = Moshi.Builder().add(FlexIntAdapter).add(KotlinJsonAdapterFactory()).build()
        val adapter = moshi.adapter(com.nuvio.tv.data.remote.dto.XtreamEpgEntryDto::class.java)

        val marked = adapter.fromJson("""{"id":"1","has_archive":1}""")!!
        val markedString = adapter.fromJson("""{"id":"1","has_archive":"1"}""")!!
        val unmarked = adapter.fromJson("""{"id":"1","has_archive":0}""")!!
        val absent = adapter.fromJson("""{"id":"1"}""")!!
        val junk = adapter.fromJson("""{"id":"1","has_archive":"soon"}""")!!

        assertEquals(1, marked.hasArchive)
        assertEquals(1, markedString.hasArchive)
        assertEquals(0, unmarked.hasArchive)
        assertEquals(null, absent.hasArchive)
        assertEquals(null, junk.hasArchive)

        // ...and threads into the domain model as spoke-true / spoke-false / silent.
        assertEquals(true, marked.toProgram().hasArchive)
        assertEquals(true, markedString.toProgram().hasArchive)
        assertEquals(false, unmarked.toProgram().hasArchive)
        assertEquals(null, absent.toProgram().hasArchive)
        assertEquals(null, junk.toProgram().hasArchive)
    }

    @Test
    fun `decodeXtreamBase64 decodes, passes through garbage, empties null`() {
        val enc = Base64.getEncoder().encodeToString("News at Ten".toByteArray())
        assertEquals("News at Ten", decodeXtreamBase64(enc))
        assertEquals("", decodeXtreamBase64(null))
        assertEquals("", decodeXtreamBase64("   "))
    }

    // --- the short-EPG lane's epoch-skew correction (the wa12 lie) ----------------------------

    /** 2026-08-15 00:00:00 UTC; the guide's "now" is 21:40 UTC that evening. */
    private val probeDay = 1_786_752_000L
    private val wa12Now = (probeDay + 21 * 3600 + 40 * 60) * 1000L

    /** A wa12-shaped listing: the epoch equals its own start string read as UTC (+2h wall clock). */
    private fun liarEntry(h: Int, m: Int): com.nuvio.tv.data.remote.dto.XtreamEpgEntryDto {
        val startSec = probeDay + h * 3600L + m * 60L
        return com.nuvio.tv.data.remote.dto.XtreamEpgEntryDto(
            id = "1",
            title = null,
            description = null,
            startTimestamp = startSec.toString(),
            stopTimestamp = (startSec + 3600).toString(),
            nowPlaying = 0,
            start = "2026-08-15 %02d:%02d:00".format(h, m),
        )
    }

    /** An onnipsite-shaped listing: local string (+1h), true-UTC epoch. */
    private fun honestEntry(utcH: Int, utcM: Int): com.nuvio.tv.data.remote.dto.XtreamEpgEntryDto {
        val startSec = probeDay + utcH * 3600L + utcM * 60L
        return com.nuvio.tv.data.remote.dto.XtreamEpgEntryDto(
            id = "1",
            title = null,
            description = null,
            startTimestamp = startSec.toString(),
            stopTimestamp = (startSec + 3600).toString(),
            nowPlaying = 0,
            start = "2026-08-15 %02d:%02d:00".format(utcH + 1, utcM),
        )
    }

    @Test
    fun `wa12-shaped listings are corrected so one brackets now`() = runTest {
        var asked = 0
        val programs = correctShortEpgListings(
            listOf(liarEntry(22, 20), liarEntry(23, 20)),
            manualOffsetMs = null,
            measuredClockOffsetMs = { asked++; 7_200_000L },
        )
        assertEquals("clock pair fetched exactly once", 1, asked)
        assertEquals("corrected to 20:20 UTC", (probeDay + 20 * 3600 + 20 * 60) * 1000L, programs.first().startMs)
        assertTrue("a corrected row brackets now", programs.any { wa12Now in it.startMs until it.endMs })
    }

    /**
     * The onnipsite proof: honest panels never pay for the lie — not with a shifted guide and not
     * with a panel round trip. The clock pair is only fetched once a response has voted LIAR.
     */
    @Test
    fun `honest listings are byte-identical and never fetch the clock pair`() = runTest {
        var asked = 0
        val listings = listOf(honestEntry(20, 40), honestEntry(21, 40))
        val programs = correctShortEpgListings(listings, null) { asked++; 3_600_000L }
        assertEquals("no server_info request for an honest panel", 0, asked)
        assertEquals("identical to the uncorrected mapping", listings.map { it.toProgram() }, programs)
    }

    @Test
    fun `a manual offset overrides auto and skips the clock fetch`() = runTest {
        var asked = 0
        val programs = correctShortEpgListings(
            listOf(liarEntry(22, 20), liarEntry(23, 20)),
            manualOffsetMs = 1_800_000L,
            measuredClockOffsetMs = { asked++; 7_200_000L },
        )
        assertEquals("manual never measures", 0, asked)
        assertEquals(
            "shifted by the manual +30m, not the auto -2h",
            (probeDay + 22 * 3600 + 20 * 60) * 1000L + 1_800_000L,
            programs.first().startMs,
        )
    }

    /** A liar whose server_info is junk has nothing to subtract — leave the rows alone. */
    @Test
    fun `a liar with no measurable clock pair is untouched`() = runTest {
        val listings = listOf(liarEntry(22, 20), liarEntry(23, 20))
        val programs = correctShortEpgListings(listings, null) { null }
        assertEquals(listings.map { it.toProgram() }, programs)
    }

    /** One parseable pair proves nothing (short EPG often answers 4 rows; junk eats some). */
    @Test
    fun `insufficient votes leave the response untouched`() = runTest {
        var asked = 0
        val listings = listOf(liarEntry(22, 20))
        val programs = correctShortEpgListings(listings, null) { asked++; 7_200_000L }
        assertEquals("unknown never measures", 0, asked)
        assertEquals(listings.map { it.toProgram() }, programs)
    }

    /**
     * `server_info` was dropped from this DTO once because panels send `port` as a bare int and
     * the strict String decode threw — losing the whole body. The re-added clock pair must
     * survive that exact payload, and the pair itself arrives bare OR quoted across panels.
     */
    @Test
    fun `server_info clock pair decodes despite the bare-int port that broke the old dto`() {
        val moshi = Moshi.Builder()
            .add(FlexIntAdapter)
            .add(com.nuvio.tv.data.remote.dto.FlexLongAdapter)
            .add(KotlinJsonAdapterFactory())
            .build()
        val adapter = moshi.adapter(com.nuvio.tv.data.remote.dto.XtreamAccountDto::class.java)

        val bare = adapter.fromJson(
            """{"user_info":{"auth":1},"server_info":{"url":"host","port":8080,""" +
                """"timestamp_now":1786830000,"time_now":"2026-08-15 23:40:00"}}"""
        )!!
        assertEquals(1786830000L, bare.serverInfo?.timestampNow)
        assertEquals(
            "the wa12 pair measures +2h",
            7_200_000L,
            ServerClockOffset.offsetMs(bare.serverInfo?.timeNow, bare.serverInfo?.timestampNow ?: 0L),
        )

        val quoted = adapter.fromJson(
            """{"server_info":{"timestamp_now":"1786830000","time_now":"2026-08-15 23:40:00"}}"""
        )!!
        assertEquals(1786830000L, quoted.serverInfo?.timestampNow)

        val absent = adapter.fromJson("""{"user_info":{"auth":1}}""")!!
        assertEquals(null, absent.serverInfo)
    }

    /** Absent timestamps parse to 0; a "corrected" 0 would be negative garbage downstream. */
    @Test
    fun `rows without epochs are never shifted`() = runTest {
        val empty = com.nuvio.tv.data.remote.dto.XtreamEpgEntryDto(
            id = "1", title = null, description = null,
            startTimestamp = null, stopTimestamp = null, nowPlaying = 0,
        )
        val programs = correctShortEpgListings(
            listOf(liarEntry(22, 20), liarEntry(23, 20), empty),
            manualOffsetMs = null,
            measuredClockOffsetMs = { 7_200_000L },
        )
        assertEquals("the empty row keeps its zero start", 0L, programs.last().startMs)
        assertEquals("and its zero end", 0L, programs.last().endMs)
    }
}
