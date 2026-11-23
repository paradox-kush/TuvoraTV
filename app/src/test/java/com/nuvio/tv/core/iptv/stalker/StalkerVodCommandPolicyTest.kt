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
            StalkerVodCommandPolicy.decide(Row(10, "http://cdn.test/a.mkv", rtspUrl = "http://cdn.test/a.mkv")))

    @Test fun `has_files resolves files first and never tries the movie cmd`() =
        assertEquals("has_files", Decision.ResolveFiles(null),
            StalkerVodCommandPolicy.decide(Row(10, "http://cdn.test/a.mkv", hasFiles = 1)))

    @Test fun `media cmd passes unchanged`() {
        assertEquals("movie", Decision.Cmd("/media/101.mpg"), StalkerVodCommandPolicy.decide(Row(101, "/media/101.mpg")))
        assertEquals("file", Decision.Cmd("/media/file_5.mpg"), StalkerVodCommandPolicy.decide(Row(7, "/media/file_5.mpg")))
    }

    @Test fun `media cmd wins over has_files so a stock row keeps its cmd`() =
        assertEquals("stock", Decision.Cmd("/media/101.mpg"),
            StalkerVodCommandPolicy.decide(Row(101, "/media/101.mpg", hasFiles = 1)))

    @Test fun `opaque cmd passes unchanged`() =
        assertEquals("xc", Decision.Cmd("eyJ0eXBlIjoibW92aWUifQ=="),
            StalkerVodCommandPolicy.decide(Row(3, "eyJ0eXBlIjoibW92aWUifQ==")))

    @Test fun `a cold row with only a url resolves files then falls back to the movie cmd`() =
        assertEquals("cold", Decision.ResolveFiles("/media/10.mpg"),
            StalkerVodCommandPolicy.decide(Row(10, "ffmpeg http://cdn.test/a.mkv")))

    @Test fun `a raw url is never a Cmd decision`() {
        listOf("http://a/b.mkv", "rtsp://a/b", "auto http://a/b.mkv").forEach {
            assertEquals(it, true, StalkerVodCommandPolicy.decide(Row(1, it)) !is Decision.Cmd)
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
