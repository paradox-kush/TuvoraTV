package com.nuvio.tv.core.recordings

import com.nuvio.tv.core.recordings.api.RecordingProblem
import com.nuvio.tv.core.recordings.api.SaveOutcome
import com.nuvio.tv.core.recordings.api.SaveRequest
import com.nuvio.tv.core.recordings.internal.AndroidRecordingSaver
import com.nuvio.tv.core.recordings.internal.ResumeMeta
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer

class AndroidRecordingSaverTest {
    private lateinit var server: MockWebServer
    private lateinit var dir: File
    private var online = true

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        dir = Files.createTempDirectory("rec-saver").toFile()
        online = true
    }

    @After
    fun tearDown() {
        server.shutdown()
        dir.deleteRecursively()
    }

    private fun req(vararg paths: String, resume: Long = 0, headers: Map<String, String> = emptyMap(), check: (suspend (Long) -> RecordingProblem?)? = null) =
        SaveRequest(
            candidates = paths.map { server.url(it).toString() }, headers = headers,
            targetPath = File(dir, "show.ts").path, resumeFromBytes = resume, estimatedBytes = 0, spaceCheck = check,
        )

    /** A `.part` left by an earlier try of [path]: its bytes plus the sidecar naming the link and validators it came from. */
    private fun partial(text: String, path: String, etag: String? = null, lastModified: String? = null) {
        val part = File(dir, "show.ts.part")
        part.writeText(text)
        ResumeMeta.write(part, server.url(path).toString(), etag, lastModified)
    }

    private fun saver() = AndroidRecordingSaver(isOnline = { online }, baseClient = OkHttpClient())
    private fun body(s: String) = MockResponse().setResponseCode(200).setBody(s)

    @Test
    fun `a plain download is renamed to the target`() = runBlocking {
        server.enqueue(body("0123456789"))
        val got = saver().save(req("/a.ts")) { }
        assertEquals(SaveOutcome.Done(10), got)
        assertEquals("0123456789", File(dir, "show.ts").readText())
        assertTrue(!File(dir, "show.ts.part").exists())
    }

    @Test
    fun `the channel headers are sent and none are invented`() = runBlocking {
        server.enqueue(body("x"))
        saver().save(req("/a.ts", headers = mapOf("User-Agent" to "ProviderUA/1.0"))) { }
        assertEquals("ProviderUA/1.0", server.takeRequest().getHeader("User-Agent"))
    }

    @Test
    fun `a partial file resumes with a range request when the server answers 206`() = runBlocking {
        partial("01234", "/a.ts", etag = "\"v1\"")
        server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 5-9/10").setBody("56789"))
        val got = saver().save(req("/a.ts", resume = 5)) { }
        assertEquals(SaveOutcome.Done(10), got)
        val sent = server.takeRequest()
        assertEquals("bytes=5-", sent.getHeader("Range"))
        assertEquals("the resume is conditional on the same bytes", "\"v1\"", sent.getHeader("If-Range"))
        assertEquals("0123456789", File(dir, "show.ts").readText())
    }

    @Test
    fun `a server that ignores the range restarts from zero`() = runBlocking {
        partial("01234", "/a.ts", etag = "\"v1\"")
        server.enqueue(body("0123456789"))
        val got = saver().save(req("/a.ts", resume = 5)) { }
        assertEquals(SaveOutcome.Done(10), got)
        assertEquals("0123456789", File(dir, "show.ts").readText())
    }

    @Test
    fun `a 416 on resume throws the partial away and starts again`() = runBlocking {
        partial("0123456789ABC", "/a.ts", etag = "\"v1\"")
        server.enqueue(MockResponse().setResponseCode(416))
        server.enqueue(body("0123456789"))
        assertEquals(SaveOutcome.Done(10), saver().save(req("/a.ts", resume = 13)) { })
    }

    @Test
    fun `candidates are tried in order and a 4xx moves on`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(body("ok"))
        val got = saver().save(req("/a.ts", "/b.ts")) { }
        assertEquals(SaveOutcome.Done(2), got)
        assertEquals("/a.ts", server.takeRequest().path)
        assertEquals("/b.ts", server.takeRequest().path)
    }

    @Test
    fun `every candidate refused is PROVIDER_REFUSED and not offline`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403))
        server.enqueue(MockResponse().setResponseCode(403))
        val got = saver().save(req("/a.ts", "/b.ts")) { } as SaveOutcome.Failed
        assertEquals(RecordingProblem.PROVIDER_REFUSED, got.problem)
        assertEquals(false, got.offline)
    }

    @Test
    fun `no network is reported offline and stops trying other links`() = runBlocking {
        online = false
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        server.enqueue(body("never"))
        val got = saver().save(req("/a.ts", "/b.ts")) { } as SaveOutcome.Failed
        assertEquals(RecordingProblem.NETWORK, got.problem)
        assertEquals(true, got.offline)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a body shorter than its length is a failed save that keeps the partial`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Length", "10").setBody("0123").setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        val got = saver().save(req("/a.ts")) { } as SaveOutcome.Failed
        assertEquals(false, got.offline)
        assertTrue(File(dir, "show.ts.part").exists())
        assertTrue(!File(dir, "show.ts").exists())
    }

    @Test
    fun `the space check is asked every 16 MB and can stop the save`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(ByteArray(17 * 1024 * 1024))))
        var asked = 0
        val got = saver().save(req("/a.ts", check = { asked++; RecordingProblem.NO_SPACE })) { } as SaveOutcome.Failed
        assertEquals(RecordingProblem.NO_SPACE, got.problem)
        assertEquals(1, asked)
    }

    @Test
    fun `cancelling aborts a read stalled on a body that never finishes`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("Content-Length", "100000")
                .setBody(Buffer().write(ByteArray(100_000))).throttleBody(1, 1, TimeUnit.HOURS),
        )
        val job = async(Dispatchers.Default) { saver().save(req("/a.ts")) { } }
        delay(700)
        val t0 = System.nanoTime()
        withTimeout(3_000) { job.cancel(); job.join() }
        val tookMs = (System.nanoTime() - t0) / 1_000_000
        assertTrue("cancel must interrupt the blocked read, took $tookMs ms", tookMs < 1_000)
    }

    @Test
    fun `a partial from a different link is not resumed`() = runBlocking {
        partial("01234", "/other.ts", etag = "\"v1\"")
        server.enqueue(body("0123456789"))
        assertEquals(SaveOutcome.Done(10), saver().save(req("/a.ts", resume = 5)) { })
        val sent = server.takeRequest()
        assertEquals("never ask for a range of bytes that came from another URL", null, sent.getHeader("Range"))
        assertEquals("0123456789", File(dir, "show.ts").readText())
    }

    @Test
    fun `a partial with no recorded source is not resumed`() = runBlocking {
        File(dir, "show.ts.part").writeText("01234")
        server.enqueue(body("0123456789"))
        assertEquals(SaveOutcome.Done(10), saver().save(req("/a.ts", resume = 5)) { })
        assertEquals(null, server.takeRequest().getHeader("Range"))
    }

    @Test
    fun `a 206 whose Content-Range starts elsewhere is never appended and restarts from zero`() = runBlocking {
        partial("01234", "/a.ts", etag = "\"v1\"")
        server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 3-9/10").setBody("3456789"))
        server.enqueue(body("0123456789"))
        assertEquals(SaveOutcome.Done(10), saver().save(req("/a.ts", resume = 5)) { })
        assertEquals("0123456789", File(dir, "show.ts").readText())
        server.takeRequest()
        assertEquals("the second request starts from zero", null, server.takeRequest().getHeader("Range"))
    }

    @Test
    fun `a 200 to a conditional range replaces the partial because the file changed`() = runBlocking {
        partial("OLDOL", "/a.ts", etag = "\"v1\"")
        server.enqueue(MockResponse().setResponseCode(200).setHeader("ETag", "\"v2\"").setBody("0123456789"))
        assertEquals(SaveOutcome.Done(10), saver().save(req("/a.ts", resume = 5)) { })
        assertEquals("0123456789", File(dir, "show.ts").readText())
    }

    @Test
    fun `an interrupted save records its link and validators so the next try can resume`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("Content-Length", "10").setHeader("ETag", "\"v9\"")
                .setBody("0123456789").setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
        )
        val first = saver().save(req("/a.ts")) { } as SaveOutcome.Failed
        assertTrue("a partial was kept", first.writtenBytes in 1..9)
        val meta = ResumeMeta.read(File(dir, "show.ts.part"))
        assertEquals(server.url("/a.ts").toString(), meta?.url)
        assertEquals("\"v9\"", meta?.etag)
    }
}
