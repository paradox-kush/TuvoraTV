package com.nuvio.tv.core.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B116 golden vectors. The KMP twin (`com.nuvio.app.core.diag.LogRedactionTest`, commonTest) carries
 * the SAME vectors — change both together. Secrets used below: user `alice`, password `s3cr3t`,
 * MAC `00:1A:79:12:34:56`, tokens `TOK…`, debrid key `RDKEY…`.
 */
class LogRedactionTest {
    private val secrets = listOf("alice", "s3cr3t", "00:1A:79:12:34:56", "TOKabc123", "RDKEY0123456789", "jwtsig")

    private fun assertNoSecret(value: String) {
        secrets.forEach { assertFalse("secret '$it' leaked in: $value", value.contains(it)) }
    }

    @Test
    fun xtreamLiveMovieSeriesPathCredentialsAreMasked() {
        assertEquals("Xtream live", "http://line.example.com:8080/live/***/***/12345.ts", LogRedaction.url("http://line.example.com:8080/live/alice/s3cr3t/12345.ts"))
        assertEquals("Xtream movie", "http://line.example.com/movie/***/***/987.mkv", LogRedaction.url("http://line.example.com/movie/alice/s3cr3t/987.mkv"))
        assertEquals("Xtream series", "https://line.example.com/series/***/***/555.mp4", LogRedaction.url("https://line.example.com/series/alice/s3cr3t/555.mp4"))
    }

    @Test
    fun xtreamTimeshiftPathAndQueryFormsAreMasked() {
        assertEquals("Xtream timeshift path", "http://line.example.com:8080/timeshift/***/***/120/2026-10-03:20-00/12345.ts", LogRedaction.url("http://line.example.com:8080/timeshift/alice/s3cr3t/120/2026-10-03:20-00/12345.ts"))
        assertEquals("Xtream timeshift.php query", "http://line.example.com/streaming/timeshift.php?username=***&password=***&stream=12345&start=2026-10-03:20-00&duration=120", LogRedaction.url(
                "http://line.example.com/streaming/timeshift.php?username=alice&password=s3cr3t&stream=12345&start=2026-10-03:20-00&duration=120",
            ))
    }

    @Test
    fun xtreamShortLiveFormIsMasked() {
        assertEquals("Xtream short live", "http://line.example.com:8080/***/***/12345", LogRedaction.url("http://line.example.com:8080/alice/s3cr3t/12345"))
    }

    @Test
    fun m3uGetPhpQueryCredentialsAreMasked() {
        assertEquals("M3U get.php", "http://line.example.com/get.php?username=***&password=***&type=m3u_plus&output=ts", LogRedaction.url("http://line.example.com/get.php?username=alice&password=s3cr3t&type=m3u_plus&output=ts"))
    }

    @Test
    fun stalkerMacTokenAndPlayTokenAreMasked() {
        assertEquals("Stalker portal call", "http://portal.example.com/stalker_portal/server/load.php?type=stb&action=create_link&mac=***&token=***", LogRedaction.url(
                "http://portal.example.com/stalker_portal/server/load.php?type=stb&action=create_link&mac=00:1A:79:12:34:56&token=TOKabc123",
            ))
        assertEquals("Stalker play link", "http://portal.example.com/play/live.php?mac=***&stream=4242&extension=ts&play_token=***", LogRedaction.url(
                "http://portal.example.com/play/live.php?mac=00:1A:79:12:34:56&stream=4242&extension=ts&play_token=TOKabc123",
            ))
        assertEquals("Stalker cmd string", "ffmpeg http://portal.example.com/play/live.php?mac=***&stream=4242&play_token=***", LogRedaction.url("ffmpeg http://portal.example.com/play/live.php?mac=00:1A:79:12:34:56&stream=4242&play_token=TOKabc123"))
        val nestedCmd = LogRedaction.url(
            "http://portal.example.com/server/load.php?action=create_link&cmd=ffmpeg%20http%3A%2F%2Fportal.example.com%2Fplay%2Flive.php%3Fmac%3D00%3A1A%3A79%3A12%3A34%3A56%26play_token%3DTOKabc123",
        )
        assertEquals("encoded cmd", "http://portal.example.com/server/load.php?action=create_link&cmd=***", nestedCmd)
    }

    @Test
    fun addonDebridKeysAreMasked() {
        assertEquals("torrentio-style config segment", "https://torrentio.strem.fun/providers=yts,eztv|realdebrid=***|sort=qualitysize/stream/movie/tt0111161.json", LogRedaction.url(
                "https://torrentio.strem.fun/providers=yts,eztv|realdebrid=RDKEY0123456789|sort=qualitysize/stream/movie/tt0111161.json",
            ))
        assertEquals("opaque base64 config segment", "https://addon.example.com/***/manifest.json", LogRedaction.url("https://addon.example.com/eyJkZWJyaWRLZXkiOiJSREtFWTAxMjM0NTY3ODkifQ9/manifest.json"))
        assertEquals("api key query", "https://addon.example.com/stream/movie/tt0111161.json?apikey=***", LogRedaction.url("https://addon.example.com/stream/movie/tt0111161.json?apikey=RDKEY0123456789"))
    }

    @Test
    fun userinfoAndFragmentTokensAreMasked() {
        assertEquals("userinfo", "https://***@dav.example.com/media/file.mkv", LogRedaction.url("https://alice:s3cr3t@dav.example.com/media/file.mkv"))
        assertEquals("fragment token", "https://app.example.com/callback#access_token=***&state=xyz", LogRedaction.url("https://app.example.com/callback#access_token=TOKabc123&state=xyz"))
    }

    @Test
    fun plainUrlsStayUnchanged() {
        listOf(
            "https://image.tmdb.org/t/p/w500/kqjL17yufvn9OVLyXYpvtyrFfak.jpg",
            "https://v3-cinemeta.strem.io/meta/movie/tt0111161.json",
            "https://example.com/some-movie-title-2024/stream?quality=1080p",
            "http://127.0.0.1:8090/stream/file.mkv?link=abc&index=1&play",
        ).forEach { plain -> assertEquals("plain URL must stay unchanged", plain, LogRedaction.url(plain)) }
        assertEquals("null", "", LogRedaction.url(null))
        assertEquals("not a URL", "not a url", LogRedaction.url("not a url"))
    }

    @Test
    fun redactionIsIdempotent() {
        val once = LogRedaction.url("http://line.example.com:8080/timeshift/alice/s3cr3t/120/2026-10-03:20-00/12345.ts")
        assertEquals("url twice", once, LogRedaction.url(once))
        val text = LogRedaction.text("Playing: http://line.example.com/get.php?username=alice&password=s3cr3t")
        assertEquals("text twice", text, LogRedaction.text(text))
    }

    @Test
    fun freeTextUrlsHeadersTokensAndMacsAreMasked() {
        assertEquals("mpv line with trailing period", "[cplayer] Playing: http://line.example.com:8080/timeshift/***/***/120/2026-10-03:20-00/1.ts.", LogRedaction.text("[cplayer] Playing: http://line.example.com:8080/timeshift/alice/s3cr3t/120/2026-10-03:20-00/1.ts."))
        val headers = LogRedaction.text(
            "headers={Authorization=Bearer TOKabc123.jwtsig, Cookie=mac=00:1A:79:12:34:56; stb_lang=en, User-Agent=Tuvora}",
        )
        assertNoSecret(headers)
        assertTrue("non-secret header kept: $headers", headers.contains("User-Agent=Tuvora"))
        assertNoSecret(LogRedaction.text("Authorization: Bearer TOKabc123"))
        assertNoSecret(LogRedaction.text("session eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.jwtsig ok"))
        assertNoSecret(LogRedaction.text("portal identity mac 00:1A:79:12:34:56 rejected"))
        assertNoSecret(LogRedaction.text("login username=alice password=s3cr3t"))
        assertEquals("ordinary diagnostic pairs stay legible", "focus key=row-3 code=401 attempt=2", LogRedaction.text("focus key=row-3 code=401 attempt=2"))
    }

    @Test
    fun everyCredentialVectorLeaksNothing() {
        listOf(
            "http://line.example.com:8080/live/alice/s3cr3t/12345.ts",
            "http://line.example.com:8080/timeshift/alice/s3cr3t/120/2026-10-03:20-00/12345.ts",
            "http://line.example.com/get.php?username=alice&password=s3cr3t&type=m3u_plus",
            "http://line.example.com/xmltv.php?username=alice&password=s3cr3t",
            "http://line.example.com/player_api.php?username=alice&password=s3cr3t&action=get_live_streams",
            "http://portal.example.com/play/live.php?mac=00:1A:79:12:34:56&stream=1&play_token=TOKabc123",
            "https://torrentio.strem.fun/realdebrid=RDKEY0123456789/manifest.json",
            "https://alice:s3cr3t@dav.example.com/x",
        ).forEach { vector ->
            assertNoSecret(LogRedaction.url(vector))
            assertNoSecret(LogRedaction.text("failed to open $vector: HTTP 401"))
        }
    }
}
