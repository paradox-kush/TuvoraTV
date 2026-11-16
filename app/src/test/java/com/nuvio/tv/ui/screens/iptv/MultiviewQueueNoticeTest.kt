package com.nuvio.tv.ui.screens.iptv

import android.app.Application
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import com.nuvio.tv.core.multiview.QueueResult
import com.nuvio.tv.ui.theme.NuvioTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * The queue notice dispatches on [QueueResult.offersOpenNow]: Added is passive (the row keeps focus), a duplicate
 * or a full queue is a focusable "Open now" toast. Fails if offersOpenNow stops reaching the UI.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class MultiviewQueueNoticeTest {
    @get:Rule val rule = createComposeRule()
    private val rowFocus = FocusRequester()
    private var result by mutableStateOf<QueueResult?>(null)
    private var opened = 0
    private var dismissed = 0
    private var restored = 0

    private fun setup() {
        rule.setContent {
            NuvioTheme {
                Box {
                    Box(Modifier.size(100.dp).semantics { contentDescription = "row" }.focusRequester(rowFocus).focusable())
                    result?.let { r ->
                        MultiviewQueueNotice(
                            result = r,
                            message = "notice",
                            openLabel = "Open now",
                            onOpen = { opened++ },
                            onDismiss = { dismissed++; result = null },
                            onRestoreFocus = { restored++; rowFocus.requestFocus() },
                            durationMillis = 8000,
                        )
                    }
                }
            }
        }
        rule.runOnIdle { rowFocus.requestFocus() }
        rule.onNodeWithContentDescription("row").assertIsFocused()
    }

    private fun show(r: QueueResult) {
        rule.runOnIdle { result = r }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(500)
    }

    private fun assertOwnsFocusAndOpens(r: QueueResult) {
        setup()
        show(r)
        rule.onNodeWithText("Open now").assertExists().assertIsFocused()
        rule.onNodeWithText("Open now").performKeyInput { pressKey(Key.DirectionCenter) }
        rule.runOnIdle {
            assertEquals("OK on Open now opens multiview once", 1, opened)
            assertEquals("the notice is dismissed", true, result == null)
        }
    }

    @Test fun fullQueueOffersFocusedOpenNow() = assertOwnsFocusAndOpens(QueueResult.Full)

    @Test fun duplicateOffersFocusedOpenNow() = assertOwnsFocusAndOpens(QueueResult.AlreadyQueued(2))

    @Test fun backOrArrowOnOpenNowDismissesAndRestoresFocusToTheRow() {
        setup()
        show(QueueResult.Full)
        rule.onNodeWithText("Open now").assertIsFocused()
        rule.onNodeWithText("Open now").performKeyInput { pressKey(Key.DirectionDown) }
        rule.runOnIdle {
            assertEquals("dismissed", 1, dismissed)
            assertEquals("focus restored", 1, restored)
            assertEquals("nothing opened", 0, opened)
        }
        rule.onNodeWithContentDescription("row").assertIsFocused()
    }

    @Test fun addedIsPassiveAndTheRowKeepsFocus() {
        setup()
        show(QueueResult.Added(1))
        rule.onNodeWithText("notice").assertExists()
        rule.onNodeWithText("Open now").assertDoesNotExist()
        rule.onNodeWithContentDescription("row").assertIsFocused()
        assertEquals("no focusable node besides the row", 0,
            rule.onAllNodes(isFocusable() and !hasContentDescription("row")).fetchSemanticsNodes().size)
    }
}
