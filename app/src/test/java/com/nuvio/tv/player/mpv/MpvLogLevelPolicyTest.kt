package com.nuvio.tv.player.mpv

import org.junit.Assert.assertEquals
import org.junit.Test

/** B116: raw mpv/FFmpeg modules that format provider URLs must print nothing (twin: KMP commonTest). */
class MpvLogLevelPolicyTest {
    @Test
    fun `url bearing modules are silenced`() {
        listOf("cplayer", "stream", "ffmpeg", "ffmpeg/demuxer", "lavf", "demux", "file", "osd/libass").forEach { module ->
            assertEquals("module $module", "no", MpvLogLevelPolicy.levelFor(module))
        }
    }

    @Test
    fun `decoder and output modules stay visible`() {
        assertEquals("presentation-fault lines (MpvPresentationFaultPolicy)", "v", MpvLogLevelPolicy.levelFor("vo/gpu/aimagereader"))
        assertEquals("video decoder", "warn", MpvLogLevelPolicy.levelFor("vd"))
        assertEquals("audio output", "warn", MpvLogLevelPolicy.levelFor("ao/audiotrack"))
        assertEquals("ffmpeg video decoder", "warn", MpvLogLevelPolicy.levelFor("ffmpeg/video"))
    }

    @Test
    fun `last matching entry wins like mpv msg c`() {
        assertEquals("override order", "warn", MpvLogLevelPolicy.levelFor("ffmpeg/video", "all=no,ffmpeg=no,ffmpeg/video=warn"))
        assertEquals("prefix needs a slash boundary", "no", MpvLogLevelPolicy.levelFor("vdx", "all=no,vd=warn"))
        assertEquals("library default reproduces the leak", "v", MpvLogLevelPolicy.levelFor("stream", "all=v"))
    }
}
