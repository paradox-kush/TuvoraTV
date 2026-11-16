package com.nuvio.tv.ui.screens.iptv

import android.app.Application
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
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
 * Device pass 3: the side rail round trip on the shape MainActivity really has. The rail is an overlay drawn after
 * the content; LEFT is not a focus search Compose runs by itself but MainActivity's own `onKeyEvent`
 * (`moveFocus(Left)`, and only when that fails, open the rail and request its item). RIGHT in the rail collapses it
 * (items go `canFocus = false`) and requests nothing. The hub side is the real header (HubSectionTab inside
 * hubHeaderFocusScope + focusRestorer) and a channel list with a focusRestorer, under the hub's content root.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class HubRailRoundTripTest {

    @get:Rule
    val rule = createComposeRule()

    private val railItem = FocusRequester()
    private val live = FocusRequester()
    private val movies = FocusRequester()
    private val rows = List(8) { FocusRequester() }
    private val firstRow = FocusRequester()
    private var railOpen by mutableStateOf(false)
    /** The group column, collapsed to zero width while the viewer is in the channel list (inCategory). */
    private var columnWidth by mutableStateOf(0)
    private val groupRows = List(4) { FocusRequester() }
    private var focused: String? = null
    private val history = mutableListOf<String>()
    private var aimedRow = 0
    private val contentReturn = HubContentReturn()

    /** Tabs are wrapped (the Card inside takes focus), so they count by hasFocus; the rest by isFocused. */
    private fun Modifier.named(name: String) = onFocusChanged {
        val mine = if (name.startsWith("tab:")) it.hasFocus else it.isFocused
        if (mine) { if (focused != name) history += name; focused = name } else if (focused == name) focused = null
    }

    private fun setHub() {
        rule.setContent {
            NuvioTheme {
                val focusManager = LocalFocusManager.current
                Box(
                    Modifier.fillMaxSize().onKeyEvent { e ->
                        // MainActivity: LEFT that no focus search can serve opens the rail.
                        if (!railOpen && e.type == KeyEventType.KeyDown && e.key == Key.DirectionLeft) {
                            if (focusManager.moveFocus(FocusDirection.Left)) true
                            else { railOpen = true; true }
                        } else false
                    }
                ) {
                    Column(Modifier.padding(start = 80.dp).hubContentFocusRestore(contentReturn)) {
                        val headerFocus = remember { HubHeaderFocus() }
                        Row(
                            Modifier.hubHeaderFocusScope(headerFocus)
                                .onFocusChanged { if (it.hasFocus) contentReturn.onHeaderFocused() }
                                .focusRestorer(live)
                        ) {
                            Box(Modifier.named("tab:live")) { HubSectionTab("Live", XtreamSection.LIVE, XtreamSection.LIVE, headerFocus, live) {} }
                            Box(Modifier.named("tab:movies")) { HubSectionTab("Movies", XtreamSection.MOVIES, XtreamSection.LIVE, headerFocus, movies) {} }
                        }
                        Row {
                        LazyColumn(
                            Modifier.width(columnWidth.dp).height(400.dp)
                                .guideCategoryColumnEntry(collapsed = columnWidth == 0, entryFocus = groupRows[0])
                        ) {
                            items(groupRows.size) { i ->
                                Box(Modifier.size(40.dp).focusRequester(groupRows[i]).named("group$i").focusable())
                            }
                        }
                        LazyColumn(
                            Modifier.height(400.dp)
                                .onFocusChanged { if (it.hasFocus) contentReturn.remember { rows[aimedRow] } }
                                .focusRestorer(firstRow)
                        ) {
                            items(rows.size) { i ->
                                Box(
                                    Modifier.size(40.dp)
                                        .then(if (i == 0) Modifier.focusRequester(firstRow) else Modifier)
                                        .focusRequester(rows[i])
                                        .onFocusChanged { if (it.isFocused) aimedRow = i }
                                        .named("row$i").focusable()
                                )
                            }
                        }
                        }
                    }
                    // The rail: drawn after the content, focusable only while open.
                    Box(
                        Modifier.align(Alignment.TopStart).size(60.dp)
                            .focusRequester(railItem)
                            .focusProperties { canFocus = railOpen }
                            .onPreviewKeyEvent { e ->
                                if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight) { railOpen = false; true } else false
                            }
                            .named("rail").focusable()
                    )
                }
                // MainActivity pendingSidebarFocusRequest: the opened rail takes focus.
                androidx.compose.runtime.LaunchedEffect(railOpen) { if (railOpen) railItem.requestFocusOrFalse() }
            }
        }
    }

    private fun press(key: Key) = rule.onRoot().performKeyInput { pressKey(key) }

    @Test
    fun `LEFT on the Live tab goes straight to the rail`() {
        setHub()
        rule.runOnIdle { assertTrue(live.requestFocusOrFalse()) }
        press(Key.DirectionLeft)
        rule.runOnIdle { assertEquals("history=$history", "rail", focused) }
    }

    @Test
    fun `a row reached by keys comes back after the rail`() {
        setHub()
        rule.runOnIdle { assertTrue(firstRow.requestFocusOrFalse()) }
        repeat(3) { press(Key.DirectionDown) }
        rule.runOnIdle { assertEquals("history=$history", "row3", focused) }
        press(Key.DirectionLeft)
        rule.runOnIdle { assertEquals("history=$history", "rail", focused) }
        press(Key.DirectionRight)
        rule.waitForIdle()
        if (focused == null || focused == "rail") press(Key.DirectionRight)
        rule.runOnIdle { assertEquals("history=$history", "row3", focused) }
    }

    @Test
    fun `a row reached by the entry landing comes back after the rail`() {
        setHub()
        rule.runOnIdle { assertTrue(live.requestFocusOrFalse()) }
        // The entry landing asks the remembered row from the tab (GuideFocusRequestLoop asks at least three times).
        rule.runOnIdle { repeat(3) { rows[5].requestFocusOrFalse() } }
        rule.runOnIdle { assertEquals("history=$history", "row5", focused) }
        press(Key.DirectionLeft)
        rule.runOnIdle { assertEquals("history=$history", "rail", focused) }
        press(Key.DirectionRight)
        rule.waitForIdle()
        if (focused == null || focused == "rail") press(Key.DirectionRight)
        rule.runOnIdle { assertEquals("history=$history", "row5", focused) }
    }

    /**
     * Device pass 3, finding 2: LEFT on Live TV left nothing focused, and a second LEFT reached the rail. Below the
     * tabs, at the same left edge, sits the group column collapsed to zero width: focus search took it as "left of
     * the tab" and focus went into an invisible row.
     */
    @Test
    fun `LEFT on the Live tab skips the collapsed group column`() {
        setHub()
        rule.runOnIdle { assertTrue(live.requestFocusOrFalse()) }
        press(Key.DirectionLeft)
        rule.runOnIdle { assertEquals("history=$history", "rail", focused) }
    }
}
