package com.nuvio.tv.ui.screens.multiview

import android.app.Application
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.withFrameNanos
import com.nuvio.tv.ui.screens.iptv.requestFocusOrFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * RIGHT or OK on a picker group asks the channel list to take focus. The request is a piece of state the effect is keyed
 * on, so clearing it before the scroll and the focus asks have run restarts the effect and cancels them: focus then never
 * left the group (device pass 2026-11-14, emulator).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class ChannelEntryEffectTest {
    @get:Rule val rule = createComposeRule()

    private val keys = listOf("a", "b", "c")

    @Test
    fun `the channel list takes focus after the group commits and the list scrolls`() {
        var pending by mutableStateOf<String?>(null)
        var entryKey by mutableStateOf<String?>(null)
        val scrolled = mutableListOf<Int>()
        rule.setContent {
            val row = remember { FocusRequester() }
            Box(Modifier.size(100.dp).testTag("row").focusRequester(row).focusable())
            ChannelEntryEffect(
                pendingGroup = pending,
                selectedGroup = "g",
                loading = false,
                keys = keys,
                entryKey = entryKey,
                onScreen = setOf("a"),
                // The real list scroll waits for a frame, so the request is cleared while the effect is suspended.
                scrollTo = { index -> withFrameNanos { }; scrolled += index },
                requestFocus = { row.requestFocusOrFalse() },
                onEntered = { entryKey = it },
                onDone = { pending = null },
            )
        }
        rule.waitForIdle()
        pending = "g"
        rule.waitForIdle()

        rule.onNodeWithTag("row").assertIsFocused()
        assertEquals("scrolled to the first channel that is not on screen", listOf(1), scrolled)
        assertEquals("b", entryKey)
        assertNull("the request is cleared once focus has landed", pending)
    }

    @Test
    fun `an empty group clears the request and keeps focus where it was`() {
        var pending by mutableStateOf<String?>("g")
        var asked = false
        rule.setContent {
            ChannelEntryEffect(
                pendingGroup = pending,
                selectedGroup = "g",
                loading = false,
                keys = emptyList(),
                entryKey = null,
                onScreen = emptySet(),
                scrollTo = {},
                requestFocus = { asked = true; true },
                onEntered = {},
                onDone = { pending = null },
            )
        }
        rule.waitForIdle()
        assertNull(pending)
        assertEquals("no focus request into an empty list", false, asked)
    }
}
