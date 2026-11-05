package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B200: libmpv's `http-header-fields` is one global option holding the STREAM's headers (cookies, tokens),
 * sent on every HTTP request mpv makes. A remote `sub-add` would hand them to the subtitle host, so libmpv
 * may only `sub-add` a local file (the player downloads addon subtitles itself with scoped headers).
 */
class MpvExternalSubtitlePolicyTest {
    @Test
    fun aRemoteSubtitleUrlIsNeverSubAdded() {
        assertFalse("https", MpvExternalSubtitlePolicy.isAllowedSubAddSource("https://subs.example/a.srt"))
        assertFalse("http", MpvExternalSubtitlePolicy.isAllowedSubAddSource("http://subs.example/a.srt"))
        assertFalse("upper-case scheme", MpvExternalSubtitlePolicy.isAllowedSubAddSource("HTTPS://subs.example/a.srt"))
        assertFalse("other network scheme", MpvExternalSubtitlePolicy.isAllowedSubAddSource("ftp://subs.example/a.srt"))
        assertFalse("blank", MpvExternalSubtitlePolicy.isAllowedSubAddSource("  "))
    }

    @Test
    fun aLocalCachedFileIsSubAdded() {
        assertTrue(MpvExternalSubtitlePolicy.isAllowedSubAddSource("/data/user/0/com.tuvora.tv/cache/subtitles/mpv_1.srt"))
        assertTrue(MpvExternalSubtitlePolicy.isAllowedSubAddSource("file:///data/user/0/com.tuvora.tv/cache/subtitles/mpv_1.srt"))
    }
}
