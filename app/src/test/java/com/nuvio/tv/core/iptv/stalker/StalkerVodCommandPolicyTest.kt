package com.nuvio.tv.core.iptv.stalker

import com.nuvio.tv.core.iptv.stalker.StalkerVodCommandPolicy.Decision
import com.nuvio.tv.core.iptv.stalker.StalkerVodCommandPolicy.Row
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StalkerVodCommandPolicyTest {

    @Test fun `is_file row builds the file cmd`() =
        assertEquals("file", Decision.Cmd("/media/file_100.mpg"),
            StalkerVodCommandPolicy.decide(Row(100, "http://cdn.test/f.mkv", isFile = true)))

    @Test fun `rtsp_url row builds the movie cmd`() =
        assertEquals("rtsp", Decision.Cmd("/media/10.mpg"),
            StalkerVodCommandPolicy.decide(Row(10, "http://cdn.test/a.mkv", rtspUrl = "http://cdn.test/a.mkv", hasFiles = 0)))

    @Test fun `has_files resolves files first and never tries the movie cmd`() =
        assertEquals("has_files", Decision.ResolveFiles(null),
            StalkerVodCommandPolicy.decide(Row(10, "http://cdn.test/a.mkv", hasFiles = 1)))

    @Test fun `has_files with a movie-form media cmd still resolves files first`() =
        assertEquals("movie-form", Decision.ResolveFiles(null),
            StalkerVodCommandPolicy.decide(Row(101, "/media/101.mpg", hasFiles = 1)))

    @Test fun `has_files with an rtsp_url still resolves files first`() =
        assertEquals("rtsp+files", Decision.ResolveFiles(null),
            StalkerVodCommandPolicy.decide(Row(10, "http://cdn.test/a.mkv", rtspUrl = "http://cdn.test/a.mkv", hasFiles = 2)))

    @Test fun `has_files with a file-form cmd stays direct`() =
        assertEquals("file-form", Decision.Cmd("/media/file_5.mpg"),
            StalkerVodCommandPolicy.decide(Row(7, "/media/file_5.mpg", hasFiles = 1)))

    @Test fun `media cmd passes unchanged when the row has no files`() {
        assertEquals("movie", Decision.Cmd("/media/101.mpg"), StalkerVodCommandPolicy.decide(Row(101, "/media/101.mpg")))
        assertEquals("movie has_files 0", Decision.Cmd("/media/101.mpg"), StalkerVodCommandPolicy.decide(Row(101, "/media/101.mpg", hasFiles = 0)))
        assertEquals("file", Decision.Cmd("/media/file_5.mpg"), StalkerVodCommandPolicy.decide(Row(7, "/media/file_5.mpg")))
    }

    @Test fun `opaque cmd passes unchanged`() =
        assertEquals("xc", Decision.Cmd("eyJ0eXBlIjoibW92aWUifQ=="),
            StalkerVodCommandPolicy.decide(Row(3, "eyJ0eXBlIjoibW92aWUifQ==")))

    @Test fun `XC emulator raw cmd is sent unchanged`() {
        assertEquals("has_files 0", Decision.Cmd("ffmpeg http://localhost/ch/3", "/media/10.mpg"),
            StalkerVodCommandPolicy.decide(Row(10, "ffmpeg http://localhost/ch/3", hasFiles = 0)))
        assertEquals("cold", Decision.Cmd("ffmpeg http://cdn.test/a.mkv", "/media/10.mpg"),
            StalkerVodCommandPolicy.decide(Row(10, "ffmpeg http://cdn.test/a.mkv")))
    }

    @Test fun `a raw url is rewritten only on proof of a stock custom row`() {
        listOf(
            Row(1, "http://a/b.mkv", rtspUrl = "http://a/b.mkv"), Row(1, "rtsp://a/b", hasFiles = 2), Row(1, "http://a/b", isFile = true),
        ).forEach { r ->
            val d = StalkerVodCommandPolicy.decide(r)
            assertEquals("raw url sent for $r", false, d is Decision.Cmd && StalkerVodCommandPolicy.isRawUrl(d.cmd))
        }
    }

    @Test fun `file row cmd is rebuilt from the id`() {
        assertEquals("custom", "/media/file_100.mpg", StalkerVodCommandPolicy.fileRowCmd(100, "http://cdn.test/f.mkv"))
        assertEquals("media", "/media/file_9.mpg", StalkerVodCommandPolicy.fileRowCmd(null, "/media/file_9.mpg"))
        assertNull("url without id", StalkerVodCommandPolicy.fileRowCmd(null, "http://cdn.test/f.mkv"))
    }

    @Test fun `replies resolve relative and strip launchers`() {
        val o = "http://p.test:8080"
        assertEquals("abs", "http://cdn.test/x.m3u8", StalkerVodCommandPolicy.resolveReply("ffmpeg http://cdn.test/x.m3u8", o, "/media/1.mpg"))
        assertEquals("auto", "http://cdn.test/x.m3u8", StalkerVodCommandPolicy.resolveReply("auto http://cdn.test/x.m3u8", o, "/media/1.mpg"))
        assertEquals("rel", "http://p.test:8080/media/1.mpg?t=a", StalkerVodCommandPolicy.resolveReply("/media/1.mpg?t=a", o, "/media/1.mpg"))
        assertEquals("query", "http://p.test:8080/media/1.mpg?token=z", StalkerVodCommandPolicy.resolveReply("?token=z", o, "/media/1.mpg"))
        assertNull("junk", StalkerVodCommandPolicy.resolveReply("nothing", o, "/media/1.mpg"))
        assertNull("blank", StalkerVodCommandPolicy.resolveReply("", o, "/media/1.mpg"))
    }
}
