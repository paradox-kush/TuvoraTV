package com.nuvio.tv.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port of NuvioMobile commonTest AddonSubtitleIdPolicyTest. JUnit order: (message,) expected, actual. */
class AddonSubtitleIdPolicyTest {
    private val m3uEpisode = "xtream:m3u|http://panel.example/get.php?username=u&password=p:episode:991"

    @Test
    fun `provider ids are scoped and never requested`() {
        assertTrue(AddonSubtitleIdPolicy.isProviderScoped(m3uEpisode))
        assertFalse(AddonSubtitleIdPolicy.isProviderScoped("tt0388629:1:2"))
        assertNull(AddonSubtitleIdPolicy.requestVideoId(m3uEpisode, null))
        assertEquals("tt0388629:1:2", AddonSubtitleIdPolicy.requestVideoId(m3uEpisode, "tt0388629:1:2"))
        assertEquals("tt0111161", AddonSubtitleIdPolicy.requestVideoId("tt0111161", null))
    }

    @Test
    fun `public ids follow the Stremio convention`() {
        assertEquals("tt0111161", AddonSubtitleIdPolicy.publicVideoId("tt0111161", false, null, null))
        assertEquals("tt0388629:3:12", AddonSubtitleIdPolicy.publicVideoId("TT0388629", true, 3, 12))
        assertNull(AddonSubtitleIdPolicy.publicVideoId("tt0388629", true, 3, null))
        assertNull(AddonSubtitleIdPolicy.publicVideoId("1111", false, null, null))
        assertEquals("series", AddonSubtitleIdPolicy.requestType("movie", "tt0388629:3:12"))
        assertEquals("movie", AddonSubtitleIdPolicy.requestType("movie", "tt0111161"))
    }
}
