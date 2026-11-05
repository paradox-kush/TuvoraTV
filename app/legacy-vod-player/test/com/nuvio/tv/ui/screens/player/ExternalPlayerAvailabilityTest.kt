package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Test

class ExternalPlayerAvailabilityTest {
    @Test
    fun aMediaServerStreamIsNeverHandedToAnExternalPlayer() {
        // The URL carries the server session (token headers, transcode session) an external app cannot reuse.
        assertEquals("server streams stay in the app", false, ExternalPlayerAvailability.canOpen(isServerStream = true))
    }

    @Test
    fun otherStreamsKeepTheExternalPlayerButton() {
        assertEquals("add-on and IPTV streams keep the button", true, ExternalPlayerAvailability.canOpen(isServerStream = false))
    }
}
