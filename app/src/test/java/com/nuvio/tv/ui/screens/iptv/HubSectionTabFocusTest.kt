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
import androidx.compose.ui.input.key.Key
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
 * B132 on the real tab Composables: focus that lands on the Live tab from outside the header (Back
 * from a detail, a restore, Compose's initial-focus search) leaves Movies selected; arrowing along
 * the header still switches. Before the fix the landing alone switched the hub to Live.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class HubSectionTabFocusTest {

    @get:Rule
    val rule = createComposeRule()

    private val live = FocusRequester()
    private val movies = FocusRequester()
    private val series = FocusRequester()
    private val content = FocusRequester()
    private var section by mutableStateOf(XtreamSection.MOVIES)

    private fun setHub() {
        rule.setContent {
            NuvioTheme {
                val headerFocus = remember { HubHeaderFocus() }
                Column {
                    Row(Modifier.hubHeaderFocusScope(headerFocus)) {
                        HubSectionTab("Live", XtreamSection.LIVE, section, headerFocus, live) { section = it }
                        HubSectionTab("Movies", XtreamSection.MOVIES, section, headerFocus, movies) { section = it }
                        HubSectionTab("Series", XtreamSection.SERIES, section, headerFocus, series) { section = it }
                    }
                    Box(Modifier.size(200.dp).focusRequester(content).focusable())
                }
            }
        }
    }

    @Test
    fun `focus landing on Live from outside the header keeps Movies selected`() {
        setHub()
        rule.runOnIdle { assertTrue(content.requestFocusOrFalse()) }
        rule.runOnIdle { assertTrue("focus lands on the Live tab", live.requestFocusOrFalse()) }
        rule.runOnIdle { assertEquals("a landing is not a choice", XtreamSection.MOVIES, section) }

        rule.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        rule.runOnIdle { assertEquals("RIGHT onto Movies", XtreamSection.MOVIES, section) }
        rule.onRoot().performKeyInput { pressKey(Key.DirectionLeft) }
        rule.runOnIdle { assertEquals("LEFT along the header selects Live", XtreamSection.LIVE, section) }
    }

    @Test
    fun `after leaving the header a landing on another tab does not switch`() {
        setHub()
        rule.runOnIdle { assertTrue(movies.requestFocusOrFalse()) }
        rule.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        rule.runOnIdle { assertEquals("travel onto Series", XtreamSection.SERIES, section) }
        // DOWN into the rows, then something (Back, a restore) puts focus straight onto the Live tab.
        rule.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        rule.runOnIdle { assertTrue("focus is down in the rows", content.requestFocusOrFalse()) }
        rule.runOnIdle { assertTrue(live.requestFocusOrFalse()) }
        rule.runOnIdle { assertEquals("a landing after leaving keeps Series", XtreamSection.SERIES, section) }
    }
}
