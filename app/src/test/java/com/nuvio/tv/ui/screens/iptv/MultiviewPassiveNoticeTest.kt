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
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
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
import com.nuvio.tv.ui.theme.NuvioTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** The "Added to Multiview" notice is passive: the channel row keeps focus and OK still reaches it. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class MultiviewPassiveNoticeTest {
    @get:Rule val rule = createComposeRule()
    private val rowFocus = FocusRequester()
    private var notice by mutableStateOf<String?>(null)
    private var rowOk = 0
    private var dismissed = 0

    private fun setup() {
        rule.setContent {
            NuvioTheme {
                Box {
                    Box(
                        Modifier.size(100.dp).semantics { contentDescription = "row" }
                            .focusRequester(rowFocus)
                            .onKeyEvent { e ->
                                if (e.type == KeyEventType.KeyUp && e.key == Key.DirectionCenter) { rowOk++; true } else false
                            }
                            .focusable(),
                    )
                    notice?.let {
                        MultiviewPassiveNotice(message = it, onDismiss = { dismissed++; notice = null }, durationMillis = 4000, key = it)
                    }
                }
            }
        }
        rule.runOnIdle { rowFocus.requestFocus() }
        rule.onNodeWithContentDescription("row").assertIsFocused()
    }

    private fun show(text: String) {
        rule.runOnIdle { notice = text }
        rule.waitForIdle()
    }

    @Test fun rowKeepsFocusAndOkReachesTheRowNotTheNotice() {
        setup()
        show("Added to Multiview (1 of 4)")
        rule.mainClock.advanceTimeBy(1000)
        rule.onNodeWithText("Added to Multiview (1 of 4)").assertExists()
        rule.onNodeWithContentDescription("row").assertIsFocused()
        assertEquals("the notice must expose no focusable node", 0,
            rule.onAllNodes(isFocusable() and !hasContentDescription("row")).fetchSemanticsNodes().size)
        rule.onNodeWithContentDescription("row").performKeyInput { pressKey(Key.DirectionCenter) }
        rule.runOnIdle { assertEquals("OK goes to the row", 1, rowOk) }
        rule.onNodeWithContentDescription("row").assertIsFocused()
    }

    @Test fun noticeDismissesAfterItsDuration() {
        setup()
        show("Added to Multiview (1 of 4)")
        rule.mainClock.advanceTimeBy(3500)
        rule.runOnIdle { assertEquals("still showing before the deadline", 0, dismissed) }
        rule.mainClock.advanceTimeBy(1000)
        rule.runOnIdle { assertEquals("dismissed once after the duration", 1, dismissed) }
        rule.onNodeWithText("Added to Multiview (1 of 4)").assertDoesNotExist()
        rule.onNodeWithContentDescription("row").assertIsFocused()
    }

    @Test fun aNewResultRestartsTheTimer() {
        setup()
        show("Added to Multiview (1 of 4)")
        rule.mainClock.advanceTimeBy(3000)
        show("Added to Multiview (2 of 4)")
        rule.mainClock.advanceTimeBy(3000)
        rule.runOnIdle { assertEquals("replaced notice got a fresh window", 0, dismissed) }
        rule.mainClock.advanceTimeBy(1500)
        rule.waitForIdle()
        rule.runOnIdle { assertEquals(1, dismissed) }
    }
}

