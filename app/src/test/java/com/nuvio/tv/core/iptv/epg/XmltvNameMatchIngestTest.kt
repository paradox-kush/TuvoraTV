package com.nuvio.tv.core.iptv.epg

import com.nuvio.tv.core.iptv.PlaylistServerFailover
import com.nuvio.tv.core.iptv.XtreamAccount
import com.nuvio.tv.core.iptv.content.ContentChannel
import com.nuvio.tv.core.iptv.content.IptvContentDb
import com.nuvio.tv.core.iptv.match.XtreamMatchIndex
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * B10 + F14 end to end on TV (real SQLite + a real HTTP guide): blank-id channels get the playlist
 * guide by NAME, several EPG sources fill each other's gaps in order, and the parse keeps nothing a
 * channel didn't match. Twin of NuvioMobile's XmltvNameMatchIngestTest (JUnit order by hand).
 *
 * Red before lane G: the TV ingest filtered on tvg-ids only (a lineup with none skipped the download
 * entirely), and a `url-tvg` / typed list of several URLs was fetched as ONE (invalid) URL.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class XmltvNameMatchIngestTest {

    private val app = RuntimeEnvironment.getApplication()
    private val db = IptvContentDb(app)
    private val xmltv = XmltvClient(db, OkHttpClient(), com.nuvio.tv.core.iptv.dns.PlaylistDns(), XtreamMatchIndex(app), PlaylistServerFailover.detached())
    private lateinit var server: MockWebServer
    private val hits = ConcurrentHashMap<String, AtomicInteger>()
    private val guides = ConcurrentHashMap<String, String>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                hits.getOrPut(path) { AtomicInteger() }.incrementAndGet()
                return guides[path]?.let { MockResponse().setBody(it) } ?: MockResponse().setResponseCode(404)
            }
        }
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private val fmt = SimpleDateFormat("yyyyMMddHHmmss Z").apply { timeZone = TimeZone.getTimeZone("UTC") }

    private fun guide(vararg channels: Pair<String, String>): String {
        val now = System.currentTimeMillis()
        val start = fmt.format(Date(now - 30 * 60_000L))
        val stop = fmt.format(Date(now + 30 * 60_000L))
        return buildString {
            append("<?xml version=\"1.0\"?>\n<tv>\n")
            for ((id, name) in channels) append("<channel id=\"$id\"><display-name>$name</display-name></channel>\n")
            for ((id, name) in channels) append("<programme start=\"$start\" stop=\"$stop\" channel=\"$id\"><title>$name now</title></programme>\n")
            append("</tv>\n")
        }
    }

    private fun account(id: String, epgUrls: String) = XtreamAccount(
        id = id, name = "b10", baseUrl = server.url("/playlist.m3u").toString(), username = "", password = "",
        sourceType = XtreamAccount.SOURCE_URL, epgUrl = epgUrls,
    )

    private fun row(sid: Int, name: String, tvg: String?) = ContentChannel(sid, name, null, tvg, "1", "http://x/$sid.ts")

    @Test
    fun `a channel with no tvg-id gets its playlist guide by name`() = runBlocking {
        guides["/guide.xml"] = guide("bbc1.uk" to "BBC One", "cnn.us" to "CNN", "itv1.uk" to "ITV")
        val acc = account("m3u|b10-name", server.url("/guide.xml").toString())
        db.replaceLiveLineup(acc.id, listOf(row(1, "UK: BBC One FHD", ""), row(2, "CNN", "cnn.us"), row(3, "UK: ITV +1", null)), listOf("1" to "UK"))

        xmltv.refreshIfStale(acc, force = true)
        val now = System.currentTimeMillis()

        assertEquals("blank tvg-id, matched by name", "BBC One now", xmltv.storedNowNext(acc, 1, now).firstOrNull()?.title)
        assertEquals("tvg-id still wins", "CNN now", xmltv.storedNowNext(acc, 2, now).firstOrNull()?.title)
        assertEquals("+1 never shows its base channel's times", emptyList<Any>(), xmltv.storedNowNext(acc, 3, now))
        val census = xmltv.census(acc)!!
        assertEquals("census by id", 1, census.byId)
        assertEquals("census by name", 1, census.byName)
    }

    @Test
    fun `several EPG sources fill gaps in priority order and stop once nothing is left`() = runBlocking {
        guides["/a.xml"] = guide("bbc1.uk" to "BBC One")
        guides["/b.xml"] = guide("bbc1.uk" to "BBC One (feed B)", "cnn.us" to "CNN")
        guides["/c.xml"] = guide("sky1.uk" to "Sky One")
        val acc = account("m3u|b10-multi", listOf("/a.xml", "/b.xml", "/c.xml").joinToString(", ") { server.url(it).toString() })
        db.replaceLiveLineup(acc.id, listOf(row(1, "BBC One", null), row(2, "CNN HD", null)), listOf("1" to "UK"))

        xmltv.refreshIfStale(acc, force = true)
        val now = System.currentTimeMillis()

        assertEquals("first source wins", "BBC One now", xmltv.storedNowNext(acc, 1, now).firstOrNull()?.title)
        assertEquals("second source fills the gap", "CNN now", xmltv.storedNowNext(acc, 2, now).firstOrNull()?.title)
        assertNull("a third source is not downloaded once every channel matched", hits["/c.xml"])
        assertEquals("the picker lists both sources' BBC One, priority first", listOf(0, 1), xmltv.guideChannels(acc, "bbc").map { it.sourceIndex })
    }

    @Test
    fun `a guide that matches nothing stores nothing`() = runBlocking {
        guides["/guide.xml"] = guide("zzz.xx" to "Nothing Alike")
        val acc = account("m3u|b10-none", server.url("/guide.xml").toString())
        db.replaceLiveLineup(acc.id, listOf(row(1, "BBC One", null)), listOf("1" to "UK"))

        xmltv.refreshIfStale(acc, force = true)

        assertTrue("an unmatched guide channel's rows are not kept", db.epgNowNext(acc.id, "zzz.xx", System.currentTimeMillis()).isEmpty())
    }
    @Test
    fun `plain HTTP gzip and raw gzip all populate matched programmes`() = runBlocking {
        for (mode in listOf("plain", "http-gzip", "raw-gzip")) {
            val xml = guide("bbc1.uk" to "BBC One")
            val response = MockResponse().setHeader("Content-Type", "application/octet-stream")
            if (mode == "plain") response.setBody(xml) else {
                val bytes = java.io.ByteArrayOutputStream().also { out ->
                    java.util.zip.GZIPOutputStream(out).use { it.write(xml.toByteArray()) }
                }.toByteArray()
                response.setBody(okio.Buffer().write(bytes))
                if (mode == "http-gzip") response.setHeader("Content-Encoding", "gzip")
            }
            // No .gz extension: detection must depend on bytes rather than the URL.
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = response
            }
            val acc = account("m3u|gzip-$mode", server.url("/guide").toString())
            db.replaceLiveLineup(acc.id, listOf(row(1, "BBC One", "bbc1.uk")), listOf("1" to "UK"))
            xmltv.refreshIfStale(acc, force = true)
            assertEquals(mode, "BBC One now", xmltv.storedNowNext(acc, 1, System.currentTimeMillis()).firstOrNull()?.title)
        }
    }


    @Test
    fun interleavedChannelsAndProgrammesAreMatchedAfterCompleteCensus() = runBlocking {
        val now = System.currentTimeMillis()
        val start = fmt.format(Date(now - 30 * 60_000L))
        val stop = fmt.format(Date(now + 30 * 60_000L))
        fun channel(id: String, name: String) = "<channel id=\"$id\"><display-name>$name</display-name></channel>"
        fun programme(id: String, title: String) = "<programme start=\"$start\" stop=\"$stop\" channel=\"$id\"><title>$title</title></programme>"
        guides["/interleaved.xml"] = "<tv>" +
            channel("early", "CNN") + programme("early", "Wrong name match") +
            programme("late", "Correct late id") + channel("late", "CNN") +
            channel("bbc", "BBC One") + programme("bbc", "Late BBC") +
            channel("unused", "Unrelated") + programme("unused", "Drop me") + "</tv>"
        val acc = account("interleaved-regression", server.url("/interleaved.xml").toString())
        db.replaceLiveLineup(acc.id, listOf(row(1, "CNN", "late"), row(2, "BBC One", null)), listOf("1" to "UK"))
        xmltv.refreshIfStale(acc, force = true)
        assertEquals("Correct late id", xmltv.storedNowNext(acc, 1, now).firstOrNull()?.title)
        assertEquals("Late BBC", xmltv.storedNowNext(acc, 2, now).firstOrNull()?.title)
        assertEquals("one network fetch", 1, hits["/interleaved.xml"]?.get())
    }
}
