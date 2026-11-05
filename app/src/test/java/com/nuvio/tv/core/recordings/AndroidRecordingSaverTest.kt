package com.nuvio.tv.core.recordings

import com.nuvio.tv.core.recordings.api.RecordingProblem
import com.nuvio.tv.core.recordings.api.SaveOutcome
import com.nuvio.tv.core.recordings.api.SaveRequest
import com.nuvio.tv.core.recordings.internal.AndroidRecordingSaver
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlinx.coroutines.runBlocking
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
        File(dir, "show.ts.part").writeText("01234")
        server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 5-9/10").setBody("56789"))
        val got = saver().save(req("/a.ts", resume = 5)) { }
        assertEquals(SaveOutcome.Done(10), got)
        assertEquals("bytes=5-", server.takeRequest().getHeader("Range"))
        assertEquals("0123456789", File(dir, "show.ts").readText())
    }

    @Test
    fun `a server that ignores the range restarts from zero`() = runBlocking {
        File(dir, "show.ts.part").writeText("01234")
        server.enqueue(body("0123456789"))
        val got = saver().save(req("/a.ts", resume = 5)) { }
        assertEquals(SaveOutcome.Done(10), got)
        assertEquals("0123456789", File(dir, "show.ts").readText())
    }

    @Test
    fun `a 416 on resume throws the partial away and starts again`() = runBlocking {
        File(dir, "show.ts.part").writeText("0123456789ABC")
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
}
