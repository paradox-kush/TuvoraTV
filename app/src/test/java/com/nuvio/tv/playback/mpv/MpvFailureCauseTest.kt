package com.nuvio.tv.playback.mpv

import com.nuvio.tv.playback.core.FailureCode
import com.nuvio.tv.playback.core.FailurePhase
import com.nuvio.tv.playback.core.HttpStatusFailures
import com.nuvio.tv.playback.core.HttpStatusProvenance
import com.nuvio.tv.playback.core.Retryability
import com.nuvio.tv.player.mpv.MpvLogLevelPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * B189: libmpv's end-file `file_error` is always `mpv_error_string()` ("loading failed",
 * "unrecognized file format", …) and never carries an HTTP status. The real cause arrives
 * earlier as an FFmpeg log line ("https: HTTP error 403 Forbidden"); without it every mpv
 * failure was reported as DEMUX_FAILED and handed off to Media3 to repeat the same request.
 */
class MpvFailureCauseTest {
    private fun evidence(vararg lines: String): MpvLoadEvidence =
        lines.fold(MpvLoadEvidence.NONE) { acc, line -> MpvFailureCause.observe(acc, "ffmpeg", MPV_LOG_WARN, line) }

    @Test
    fun `real mpv loading failed with a 403 log line is an authorization rejection, not a demux failure`() {
        val failure = normalizeMpvError("loading failed", evidence("https: HTTP error 403 Forbidden"))

        assertEquals(FailureCode.AUTHORIZATION_REJECTED, failure.code)
        assertEquals(Retryability.FATAL, failure.retryability)
        assertEquals(403, failure.httpStatus)
        assertEquals(HttpStatusProvenance.CONFIRMED, failure.statusProvenance)
    }

    @Test
    fun `mpv statuses map exactly like Media3 so the engines agree on retry and handoff`() {
        listOf(401, 403, 404, 410, 429, 400, 451, 456, 500, 503).forEach { status ->
            val mpv = normalizeMpvError("loading failed", evidence("http: HTTP error $status Something"))
            val expected = HttpStatusFailures.forStatus(status, FailurePhase.PLAYBACK)
            assertEquals("code for $status", expected.code, mpv.code)
            assertEquals("retryability for $status", expected.retryability, mpv.retryability)
            assertEquals("domain for $status", expected.domain, mpv.domain)
        }
    }

    @Test
    fun `permanent statuses are never handed off to the other engine`() {
        listOf(401, 403, 404, 429).forEach { status ->
            val failure = normalizeMpvError("loading failed", evidence("https: HTTP error $status X"))
            assertEquals("$status", Retryability.FATAL, failure.retryability)
        }
    }

    @Test
    fun `the last HTTP status of the load wins`() {
        val failure = normalizeMpvError(
            "loading failed",
            evidence("https: HTTP error 503 Service Unavailable", "http: Will reconnect at 0 in 1 second(s).", "https: HTTP error 404 Not Found"),
        )
        assertEquals(FailureCode.SOURCE_NOT_FOUND, failure.code)
    }

    @Test
    fun `network and TLS log lines classify a generic mpv failure`() {
        assertEquals(
            FailureCode.NETWORK_UNREACHABLE,
            normalizeMpvError("loading failed", evidence("tcp: Failed to resolve hostname provider.example: Name or service not known")).code,
        )
        assertEquals(
            FailureCode.NETWORK_UNREACHABLE,
            normalizeMpvError("loading failed", evidence("tcp: Connection to tcp://provider.example:80 failed: Connection refused")).code,
        )
        assertEquals(
            FailureCode.TLS_HANDSHAKE_FAILED,
            normalizeMpvError("loading failed", evidence("tls: error:0A000086:SSL routines::certificate verify failed")).code,
        )
        assertEquals(
            FailureCode.NETWORK_TIMEOUT,
            normalizeMpvError("loading failed", evidence("tcp: Connection to tcp://provider.example:80 failed: Connection timed out")).code,
        )
    }

    @Test
    fun `without evidence the real mpv strings keep the demux classification`() {
        listOf("loading failed", "unrecognized file format", "no audio or video data played").forEach { raw ->
            assertEquals(raw, FailureCode.DEMUX_FAILED, normalizeMpvError(raw).code)
        }
    }

    @Test
    fun `lines below warning or unrelated text are not evidence`() {
        val info = MpvFailureCause.observe(MpvLoadEvidence.NONE, "ffmpeg", MPV_LOG_INFO, "https: HTTP error 403 Forbidden")
        assertNull(info.httpStatus)
        assertEquals(MpvLoadEvidence.NONE, evidence("Stream ends prematurely at 1024, should be 2048", "frame 401 decoded"))
    }

    @Test
    fun `the clean player hears FFmpeg protocol errors but never URL-bearing modules`() {
        val spec = MpvFailureCause.MSG_LEVEL
        assertEquals("warn", MpvLogLevelPolicy.levelFor("ffmpeg", spec))
        // cplayer/stream print the URL ("Playing: <url>", "Failed to open <url>"); the HLS demuxer
        // logs segment URLs. Provider credentials ride in those URLs (B116).
        listOf("cplayer", "stream", "ffmpeg/demuxer", "demux", "ytdl_hook").forEach { module ->
            assertEquals(module, "no", MpvLogLevelPolicy.levelFor(module, spec))
        }
    }

    @Test
    fun `the clean player hears decoder and audio output warnings for decode health`() {
        val spec = MpvFailureCause.MSG_LEVEL
        listOf("ad", "vd", "ao").forEach { module -> assertEquals(module, "warn", MpvLogLevelPolicy.levelFor(module, spec)) }
        // The URL-bearing modules stay off even with the decode modules on (B116).
        listOf("cplayer", "stream", "ffmpeg/demuxer", "demux").forEach { module ->
            assertEquals(module, "no", MpvLogLevelPolicy.levelFor(module, spec))
        }
    }

    private companion object {
        // mpv client.h: MPV_LOG_LEVEL_WARN = 30, MPV_LOG_LEVEL_INFO = 40.
        const val MPV_LOG_WARN = 30
        const val MPV_LOG_INFO = 40
    }
}
