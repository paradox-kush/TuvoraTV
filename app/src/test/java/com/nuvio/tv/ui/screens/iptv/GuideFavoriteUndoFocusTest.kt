package com.nuvio.tv.ui.screens.iptv

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.dp
import com.nuvio.tv.ui.components.NuvioUndoToast
import com.nuvio.tv.ui.theme.NuvioTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class GuideFavoriteUndoFocusTest {
    @get:Rule val rule = createComposeRule()
    private val rowFocus = FocusRequester()
    private var notice by mutableStateOf(false)
    private var favorite by mutableStateOf(false)
    private var plays = 0
    private lateinit var hostView: android.view.View
    private val channel = GuideChannel("xtream:acc:live:1", "BBC One", null, "http://example.invalid/1.ts", 1, hasArchive = false)

    private fun setup(holdRemote: Boolean = false) {
        rule.setContent {
            hostView = LocalView.current
            NuvioTheme {
                Box(Modifier.width(900.dp)) {
                    GuideChannelRow(
                        number = 1, name = channel.name, isPlaying = true, logo = null, epg = null,
                        windowStartMs = 0, nowMs = 0, isFavorite = favorite, isPinned = false,
                        lockFocus = false, clampUp = true, onFocused = {}, onClick = { plays++ },
                        onLongClick = { favorite = !favorite; notice = true }, onHide = {},
                        focusRequester = rowFocus, channel = channel, catchUpSupported = false,
                        timelineActive = false, onEnterTimeline = {}, onLeaveTimeline = {},
                        onTravel = { false }, onBackOutOfTimeline = {}, onProgrammeClick = {},
                    )
                    if (notice) NuvioUndoToast(
                        message = "BBC One added", actionLabel = "Undo", durationMillis = 8000,
                        onAction = { rowFocus.requestFocus(); favorite = !favorite; notice = false },
                        onDismiss = { rowFocus.requestFocus(); notice = false },
                    )
                }
            }
        }
        rule.runOnIdle { rowFocus.requestFocus() }
        if (holdRemote) {
            rule.onRoot().performKeyInput { keyDown(Key.DirectionCenter) }
            rule.mainClock.advanceTimeBy(1000)
        } else {
            rule.onNode(hasClickAction() and hasAnyDescendant(hasText("BBC One")), useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.OnLongClick) { it() }
        }
        rule.onNodeWithText("Undo").assertIsFocused()
    }

    @Test fun previewLongPressThenUndoRestoresMembershipAndFocusWithoutRetuning() {
        setup()
        rule.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        rule.onNodeWithText("Undo").assertDoesNotExist()
        rule.runOnIdle { assertEquals(false, favorite); assertEquals(0, plays) }
        rule.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        rule.runOnIdle { assertEquals(1, plays) }
    }

    @Test fun arrowDismissesNoticeAndReturnsToPreviewWithoutChangingMembership() {
        setup()
        rule.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        rule.onNodeWithText("Undo").assertDoesNotExist()
        rule.runOnIdle { assertEquals(true, favorite); assertEquals(0, plays) }
        rule.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        rule.runOnIdle { assertEquals(1, plays) }
    }
    @Test fun releasingHeldOkAfterTheToastTakesFocusDoesNotUndoOrRetune() {
        setup(holdRemote = true)
        rule.onRoot().performKeyInput { keyUp(Key.DirectionCenter) }
        rule.runOnIdle { assertEquals("the original hold must not undo its own toggle", true, favorite) }
        rule.onNodeWithText("Undo").assertIsFocused()
        rule.runOnIdle { assertEquals(true, favorite); assertEquals(0, plays) }
        rule.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        rule.onNodeWithText("Undo").assertDoesNotExist()
        rule.runOnIdle { assertEquals(false, favorite); assertEquals(0, plays) }
    }

    @Test fun repeatedDownFromTheOriginalHoldCannotArmUndo() {
        setup(holdRemote = true)
        rule.runOnIdle {
            val now = android.os.SystemClock.uptimeMillis()
            hostView.dispatchKeyEvent(android.view.KeyEvent(
                now - 1000, now, android.view.KeyEvent.ACTION_DOWN,
                android.view.KeyEvent.KEYCODE_DPAD_CENTER, 3,
            ))
        }
        rule.onRoot().performKeyInput { keyUp(Key.DirectionCenter) }
        rule.onNodeWithText("Undo").assertIsFocused()
        rule.runOnIdle { assertEquals(true, favorite); assertEquals(0, plays) }
        rule.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        rule.runOnIdle { assertEquals(false, favorite) }
    }

}
