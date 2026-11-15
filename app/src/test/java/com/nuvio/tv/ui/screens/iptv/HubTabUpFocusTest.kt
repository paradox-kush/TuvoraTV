package com.nuvio.tv.ui.screens.iptv

import android.app.Application
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import com.nuvio.tv.ui.theme.NuvioTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * Device pass 2, finding C: UP from guide row 1 left NO control focused (A9: no ring anywhere; three RIGHTs then
 * reached Series, i.e. focus sat on the header row itself). Row 1 hands UP to the active tab by request (B254,
 * `onUpToTabs`), on the real header: HubSectionTab + HubChip inside the header's focusRestorer.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class HubTabUpFocusTest {

    @get:Rule
    val rule = createComposeRule()

    private val live = FocusRequester()
    private val movies = FocusRequester()
    private val series = FocusRequester()
    private val search = FocusRequester()
    private val row = FocusRequester()
    private var showHeader by mutableStateOf(true)
    private var focused: String? = null

    private fun Modifier.named(name: String) = onFocusChanged { if (it.hasFocus) focused = name else if (focused == name) focused = null }

    private fun setHub() {
        rule.setContent {
            NuvioTheme {
                Column {
                    if (showHeader) {
                        val headerFocus = remember { HubHeaderFocus() }
                        Row(Modifier.hubHeaderFocusScope(headerFocus).focusRestorer(live)) {
                            Box(Modifier.named("live")) { HubSectionTab("Live", XtreamSection.LIVE, XtreamSection.LIVE, headerFocus, live) {} }
                            Box(Modifier.named("movies")) { HubSectionTab("Movies", XtreamSection.MOVIES, XtreamSection.LIVE, headerFocus, movies) {} }
                            Box(Modifier.named("series")) { HubSectionTab("Series", XtreamSection.SERIES, XtreamSection.LIVE, headerFocus, series) {} }
                            Box(Modifier.named("search")) { HubChip("Search", selected = false, focusRequester = search) {} }
                        }
                    }
                    Box(
                        Modifier.size(200.dp).focusRequester(row).named("row1")
                            .onPreviewKeyEvent { e ->
                                if (e.key == Key.DirectionUp && e.type == KeyEventType.KeyDown) { live.requestFocusPastRestorer(); true } else false
                            }
                            .focusable()
                    )
                }
            }
        }
    }

    @Test
    fun `UP from row one lands on the Live tab`() {
        setHub()
        rule.runOnIdle { assertTrue(row.requestFocusOrFalse()) }
        rule.onRoot().performKeyInput { pressKey(Key.DirectionUp) }
        rule.runOnIdle { assertEquals("live", focused) }
    }

    @Test
    fun `UP from row one lands on the Live tab after the header was last left from another chip`() {
        setHub()
        rule.runOnIdle { assertTrue(search.requestFocusOrFalse()) }
        rule.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        rule.runOnIdle { assertEquals("row1", focused) }
        rule.onRoot().performKeyInput { pressKey(Key.DirectionUp) }
        rule.runOnIdle { assertEquals("live", focused) }
    }

    @Test
    fun `UP from row one lands on the Live tab after full screen recomposed the header`() {
        setHub()
        rule.runOnIdle { assertTrue(search.requestFocusOrFalse()) }
        rule.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        rule.runOnIdle { showHeader = false }
        rule.runOnIdle { showHeader = true }
        rule.runOnIdle { assertTrue(row.requestFocusOrFalse()) }
        rule.onRoot().performKeyInput { pressKey(Key.DirectionUp) }
        rule.runOnIdle { assertEquals("live", focused) }
    }
}
