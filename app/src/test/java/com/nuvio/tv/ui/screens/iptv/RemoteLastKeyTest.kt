package com.nuvio.tv.ui.screens.iptv

import androidx.compose.ui.input.key.Key
import org.junit.Assert.assertEquals
import org.junit.Test

/** Device pass 2 (2026-11-15). JUnit: (message, expected, actual). */
class RemoteLastKeyTest {
    /** LAST (keycode 229, inconclusive on the emulator) is the key both the guide and full screen handle. */
    @Test
    fun `the remote's LAST key is Compose's LastChannel`() {
        assertEquals(Key.LastChannel, Key(android.view.KeyEvent.KEYCODE_LAST_CHANNEL))
        assertEquals(229, android.view.KeyEvent.KEYCODE_LAST_CHANNEL)
    }
}
