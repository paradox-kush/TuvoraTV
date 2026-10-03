package com.nuvio.tv.ui.screens.iptv

import androidx.compose.ui.focus.FocusRequester
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guide's focus fallbacks were written against a `requestFocus()` that threw when nothing was
 * attached. It no longer does, so `runCatching { … }.isFailure` read every miss as a success.
 */
class GuideFocusTest {

    @Test
    fun `requestFocus on an unattached requester no longer throws so isFailure never sees the miss`() {
        assertTrue("no exception", runCatching { FocusRequester().requestFocus() }.isSuccess)
    }

    @Test
    fun `requestFocusOrFalse reports the miss`() {
        assertFalse("nothing attached", FocusRequester().requestFocusOrFalse())
    }
}
