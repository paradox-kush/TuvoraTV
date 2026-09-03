package com.nuvio.tv.core.iptv.content

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * RED-FIRST (TV twin of NuvioMobile's M3uIngestCrashSafetyTest; Overlay Build Spec v1.3.3 §5).
 *
 * Today [IptvContentDb.ingest] calls [IptvContentDb.clear] FIRST (`ingest():294 → clear():302`),
 * then fills chunk by chunk in separate transactions, then writes the meta row last. A crash
 * between the clear and the finish leaves the previous catalog GONE and a partial one in its place,
 * so a catalog-health gate cannot "keep previous state" — nothing previous survives the clear.
 *
 * Expected after P1 (generation swap): the previous complete catalog keeps serving until a new
 * generation completes and is flipped atomically. Red on the current code; green without rewriting.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class M3uIngestCrashSafetyTest {

    private val app = RuntimeEnvironment.getApplication()
    private val db = IptvContentDb(app)

    private fun channel(sid: Int, name: String) = ContentChannel(
        sid = sid, name = name, logo = null, tvgId = null, categoryId = "news",
        url = "http://m3u.example.com/$sid.ts",
    )

    @Test
    fun `a crash mid-ingest keeps the previous catalog serving`() = runTest {
        val pid = "crash-safety:m3u"

        // Ingest #1 completes: three channels, meta row written.
        db.ingest(pid) { w ->
            w.addChannel(channel(1, "BBC ONE")); w.addChannel(channel(2, "BBC TWO")); w.addChannel(channel(3, "ITV"))
        }
        assertEquals("baseline catalog has 3 channels", 3, db.channelsFor(pid, null).size)
        assertNotNull("baseline build is stamped", db.builtAt(pid))

        // Ingest #2 starts, writes one channel, then the process dies before the writer finishes.
        val crashed = runCatching {
            db.ingest(pid) { w ->
                w.addChannel(channel(1, "BBC ONE"))
                throw IllegalStateException("simulated crash mid-ingest")
            }
        }
        assertEquals("the simulated crash must propagate, not be swallowed", true, crashed.isFailure)

        // The previous, complete catalog must still be what readers see.
        val served = db.channelsFor(pid, null)
        assertEquals(
            "a half-built refresh must not replace the last complete catalog; readers saw ${served.size} channel(s)",
            3, served.size,
        )
        assertNotNull("the last complete build's stamp must survive an aborted refresh", db.builtAt(pid))
    }
}
