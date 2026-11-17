package com.nuvio.tv.playback.media3

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.nuvio.tv.playback.core.TransientLoadRetryPolicy
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * A single-use (SESSION_ONLY) live link must not be re-requested by Media3 after it is rejected.
 * Onn device pass 2026-11-04 (mock Stalker portal, /kill): with DefaultLoadErrorHandlingPolicy(0)
 * Media3's HLS playlist loader still re-requested the dead index.m3u8 five times on a 0/1/2/3 s
 * backoff, and the session only took over ~9 s after the first 401. The minimum retry count does
 * not stop retries; only getRetryDelayMsFor returning C.TIME_UNSET does (media3 1.11
 * DefaultLoadErrorHandlingPolicy / DefaultHlsPlaylistTracker).
 */
class SingleUseLoadErrorHandlingPolicyTest {
    private fun rejected(code: Int, errorCount: Int): LoadErrorHandlingPolicy.LoadErrorInfo {
        val dataSpec = DataSpec(mockk<Uri>(relaxed = true))
        val error = HttpDataSource.InvalidResponseCodeException(code, "rejected", null, emptyMap(), dataSpec, ByteArray(0))
        return LoadErrorHandlingPolicy.LoadErrorInfo(
            LoadEventInfo(0L, dataSpec, 0L),
            MediaLoadData(C.DATA_TYPE_MANIFEST),
            error,
            errorCount,
        )
    }

    @Test
    fun `a single-use link is never re-requested after a rejection`() {
        val policy = media3LoadErrorPolicyFor(TransientLoadRetryPolicy.SESSION_ONLY)
        assertNotNull("SESSION_ONLY installs a policy", policy)
        for (count in 1..5) {
            for (code in listOf(401, 403, 410, 404, 503)) {
                assertEquals(
                    "HTTP $code, error #$count: no Media3 retry; the session re-mints",
                    C.TIME_UNSET,
                    policy!!.getRetryDelayMsFor(rejected(code, count)),
                )
            }
        }
    }

    /**
     * Device pass (mock M3U, 458): Media3 re-requested a "max connections" answer 3 more times (4 requests
     * in ~15 s) before the session's terminal failure — each one a fresh hit on the account's full slots.
     * A connection limit (429/458/509) or a firewall refusal (456) is answered at once; retrying it in the
     * loader only hammers the provider.
     */
    @Test
    fun `a re-openable link never re-requests a connection limit or firewall refusal`() {
        val policy = media3LoadErrorPolicyFor(TransientLoadRetryPolicy.ENGINE_DEFAULT)
        assertNotNull("every request gets the refusal-aware policy", policy)
        for (count in 1..5) {
            for (code in listOf(429, 456, 458, 509)) {
                assertEquals("HTTP $code, error #$count", C.TIME_UNSET, policy!!.getRetryDelayMsFor(rejected(code, count)))
            }
        }
    }

    @Test
    fun `a re-openable link keeps Media3's default recovery for server and network errors`() {
        val policy = media3LoadErrorPolicyFor(TransientLoadRetryPolicy.ENGINE_DEFAULT)!!
        val default = androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy()
        for (code in listOf(500, 502, 503, 504)) {
            assertEquals("HTTP $code keeps Media3's backoff", default.getRetryDelayMsFor(rejected(code, 2)), policy.getRetryDelayMsFor(rejected(code, 2)))
        }
        assertEquals("min retry count unchanged", default.getMinimumLoadableRetryCount(C.DATA_TYPE_MEDIA), policy.getMinimumLoadableRetryCount(C.DATA_TYPE_MEDIA))
    }
}
