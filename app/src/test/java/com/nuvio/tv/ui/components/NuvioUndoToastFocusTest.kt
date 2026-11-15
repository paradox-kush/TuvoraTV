package com.nuvio.tv.ui.components

import android.app.Application
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.nuvio.tv.ui.theme.NuvioTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * The toast's action must end up focused even when something else takes focus a frame after it appears: the channel
 * menu that raised "Add to Multiview" closes and hands focus back to its row in that frame. One focus ask lost that race,
 * so "Open now" could not be reached with the remote (device pass 2026-11-15, emulator).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class NuvioUndoToastFocusTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun `the action keeps focus when a closing menu hands focus back a frame later`() {
        rule.setContent {
            NuvioTheme {
                val row = remember { FocusRequester() }
                Column {
                    Box(Modifier.size(40.dp).testTag("row").focusRequester(row).focusable())
                    NuvioUndoToast(
                        message = "Already set aside for Multiview",
                        actionLabel = "Open now",
                        onAction = {},
                        onDismiss = {},
                        durationMillis = 60_000,
                    )
                }
                // The closing menu restores focus to the row it came from, one frame after the toast appeared.
                LaunchedEffect(Unit) {
                    withFrameNanos { }
                    runCatching { row.requestFocus() }
                }
            }
        }
        rule.waitForIdle()

        rule.onNodeWithTag("row").assertIsNotFocused()
        rule.onNodeWithText("Open now").assertIsFocused()
    }
}
