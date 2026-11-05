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
import org.junit.Assert.assertNull
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

    @Test
    fun `a re-openable link keeps Media3's default recovery`() {
        assertNull(media3LoadErrorPolicyFor(TransientLoadRetryPolicy.ENGINE_DEFAULT))
    }
}
