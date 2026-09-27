package com.nuvio.tv.core.iptv

import org.junit.Assert.assertEquals
import org.junit.Test

/** B58 side finding: the Stalker Save button was permanently disabled (`else -> false`). */
class PlaylistFormSubmitPolicyTest {

    private fun can(type: String, filePicked: Boolean = false, portal: String = "", mac: String = "") =
        PlaylistFormSubmitPolicy.canSubmit(type, filePicked, portal, mac)

    @Test
    fun `stalker can be saved once a portal URL and MAC are entered`() {
        assertEquals("portal + mac", true, can(XtreamAccount.SOURCE_STALKER, portal = "http://portal:8080", mac = "00:1A:79:12:34:56"))
    }

    @Test
    fun `stalker without a portal or MAC cannot be saved`() {
        assertEquals("no portal", false, can(XtreamAccount.SOURCE_STALKER, portal = " ", mac = "00:1A:79:12:34:56"))
        assertEquals("no mac", false, can(XtreamAccount.SOURCE_STALKER, portal = "http://portal:8080", mac = ""))
    }

    @Test
    fun `xtream and url keep their existing behaviour`() {
        assertEquals("xtream", true, can(XtreamAccount.SOURCE_XTREAM))
        assertEquals("url", true, can(XtreamAccount.SOURCE_URL))
    }

    @Test
    fun `file needs a picked document`() {
        assertEquals("nothing picked", false, can(XtreamAccount.SOURCE_FILE))
        assertEquals("picked", true, can(XtreamAccount.SOURCE_FILE, filePicked = true))
    }

    @Test
    fun `unknown source types cannot be saved`() {
        assertEquals("unknown", false, can("something-else"))
    }
}
