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
    fun iptvIdentityKeysMaskTheUsernameOrMac() {
        assertEquals("xtream content id", "xtream:http://line.example.com:8080|***:live:12345", LogRedaction.url("xtream:http://line.example.com:8080|alice:live:12345"))
        assertEquals("content id whose base URL has a path", "http://line.example.com/c|***:series:5", LogRedaction.url("http://line.example.com/c|alice:series:5"))
        assertEquals("Stalker MAC identity", "stalker:http://portal.example.com|***:live:4", LogRedaction.url("stalker:http://portal.example.com|00:1A:79:12:34:56:live:4"))
        assertEquals("playlist key", "http://line.example.com|***", LogRedaction.url("http://line.example.com|alice"))
        assertNoSecret(LogRedaction.text("preserved key=xtream:http://line.example.com|alice:movie:987 (live channel, local-only)"))
    }

    @Test
    fun plainUrlsStayUnchanged() {
        listOf(
            "https://image.tmdb.org/t/p/w500/kqjL17yufvn9OVLyXYpvtyrFfak.jpg",
            "https://v3-cinemeta.strem.io/meta/movie/tt0111161.json",
            "https://example.com/some-movie-title-2024/stream?quality=1080p",
            "http://127.0.0.1:8090/stream/file.mkv?link=abc&index=1&play",
            "https://torrentio.strem.fun/providers=yts,eztv|sort=qualitysize/manifest.json",
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

    // Wave 3 / J4: Jellyfin / Emby / Plex credentials travel under their own names. A media-server request or
    // response that reaches a log line (a failed call, a debug dump of headers) must never carry the session
    // token, the Quick Connect secret or the MediaBrowser DeviceId. The KMP twin carries the same vectors.
    private val mediaSecrets = listOf("MBTOK-9f8e7d", "EMBYTOK-1a2b3c", "PLEXTOK-4d5e6f", "QCSECRET-77aa88", "pw-hunter2", "dev-install-0042")

    private fun assertNoMediaSecret(value: String) {
        mediaSecrets.forEach { assertFalse("media-server secret '$it' leaked in: $value", value.contains(it)) }
    }

    @Test
    fun mediaBrowserAuthorizationHeaderIsMaskedWholesale() {
        val header = "Authorization: MediaBrowser Client=\"Tuvora\", Device=\"Pixel 8\", DeviceId=\"dev-install-0042\", " +
            "Version=\"1.10.0\", Token=\"MBTOK-9f8e7d\""
        val out = LogRedaction.text("request failed: $header (HTTP 500)")
        assertNoMediaSecret(out)
        assertTrue("the rest of the line survives: $out", out.contains("HTTP 500"))
        // a bare quoted Token="..." pair (a header echoed without its scheme) is masked too
        assertNoMediaSecret(LogRedaction.text("sent Client=\"Tuvora\", Token=\"MBTOK-9f8e7d\" to server"))
        // ...and the tokenless Quick Connect form still hides the device id
        assertNoMediaSecret(LogRedaction.text("Authorization: MediaBrowser Client=\"Tuvora\", Device=\"TV\", DeviceId=\"dev-install-0042\", Version=\"1\""))
    }

    @Test
    fun embyAndPlexTokenHeadersAreMasked() {
        assertNoMediaSecret(LogRedaction.text("X-Emby-Token: EMBYTOK-1a2b3c"))
        assertNoMediaSecret(LogRedaction.text("headers={x-emby-token=EMBYTOK-1a2b3c, accept=application/json}"))
        assertNoMediaSecret(LogRedaction.text("X-Emby-Authorization: MediaBrowser Token=\"EMBYTOK-1a2b3c\", Client=\"Tuvora\""))
        assertNoMediaSecret(LogRedaction.text("X-Plex-Token: PLEXTOK-4d5e6f"))
        assertNoMediaSecret(LogRedaction.text("X-MediaBrowser-Token: MBTOK-9f8e7d"))
    }

    @Test
    fun mediaServerUrlsMaskApiKeyAndTokenQueryValues() {
        assertEquals(
            "Jellyfin ApiKey",
            "http://nas.local:8096/Videos/abc123/stream?Static=true&ApiKey=***&MediaSourceId=abc123",
            LogRedaction.url("http://nas.local:8096/Videos/abc123/stream?Static=true&ApiKey=MBTOK-9f8e7d&MediaSourceId=abc123"),
        )
        assertEquals(
            "Emby api_key",
            "https://emby.example.com/Videos/9/stream?api_key=***&Static=true",
            LogRedaction.url("https://emby.example.com/Videos/9/stream?api_key=EMBYTOK-1a2b3c&Static=true"),
        )
        assertEquals(
            "Plex token",
            "http://plex.local:32400/library/parts/5/1/file.mkv?X-Plex-Token=***",
            LogRedaction.url("http://plex.local:32400/library/parts/5/1/file.mkv?X-Plex-Token=PLEXTOK-4d5e6f"),
        )
        assertNoMediaSecret(LogRedaction.text("player error for http://nas.local:8096/Videos/1/master.m3u8?ApiKey=MBTOK-9f8e7d: 403"))
    }

    @Test
    fun mediaServerAuthBodiesAreMasked() {
        val response = "{\"User\":{\"Name\":\"kid\",\"Id\":\"0f1e\"},\"AccessToken\":\"MBTOK-9f8e7d\",\"ServerId\":\"6f3c\"}"
        val out = LogRedaction.text("auth response: $response")
        assertNoMediaSecret(out)
        assertTrue("non-secret fields stay legible: $out", out.contains("ServerId"))
        assertNoMediaSecret(LogRedaction.text("{\"Username\":\"kid\",\"Pw\":\"pw-hunter2\"}"))
        assertNoMediaSecret(LogRedaction.text("quick connect {\"Secret\":\"QCSECRET-77aa88\",\"Code\":\"123456\"}"))
        assertNoMediaSecret(LogRedaction.text("Secret=QCSECRET-77aa88"))
    }

    @Test
    fun mediaServerRedactionKeepsOrdinaryProse() {
        val line = "Jellyfin server 12.1 answered in 85 ms; user picked the Token Ring documentary"
        assertEquals("prose", line, LogRedaction.text(line))
        val idLine = "playing ms:jellyfin:6f3c1a9e:0f1e2d3c:movie:abc via ms-deferred"
        assertEquals("ids", idLine, LogRedaction.text(idLine))
    }
}
