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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
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
 * F1b (sim pass 2026-11-16): LEFT from a channel row re-expands the group column, but focus landed on its top row
 * ("Search") instead of the selected group ("All channels"); the same on entering docked browse mode and after a side
 * rail round trip. Spec: comparison.md:100 ("the selected group's row"), B238 (never Search by default).
 *
 * The real shape: the column carries the screen's own entry modifier ([guideCategoryColumnEntry]: the collapse guard
 * plus the focusRestorer whose fallback is the selected group's row), collapses to zero width while the viewer is in
 * the channel list, and LEFT (exitCategory) re-expands it and then requests the column's requester. The rail round
 * trip goes through the hub's real content root ([hubContentFocusRestore]).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class GuideCategoryColumnEntryTest {

    @get:Rule
    val rule = createComposeRule()

    /** Search, All favorites, Favorites, Recent, All channels, a provider group. */
    private val groupCount = 6
    private var selectedGroup by mutableStateOf(4)
    private val listFocus = FocusRequester()
    private val entryFocus = FocusRequester()
    private val groupRows = List(groupCount) { FocusRequester() }
    private val channelRows = List(5) { FocusRequester() }
    private val firstChannel = FocusRequester()
    private val railItem = FocusRequester()
    private var collapsed by mutableStateOf(false)
    private var railOpen by mutableStateOf(false)
    private var focused: String? = null
    private val history = mutableListOf<String>()
    private val contentReturn = HubContentReturn()

    private fun Modifier.named(name: String) = onFocusChanged {
        if (it.isFocused) { if (focused != name) history += name; focused = name } else if (focused == name) focused = null
    }

    private fun setGuide() {
        rule.setContent {
            NuvioTheme {
                Box(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(start = 80.dp).hubContentFocusRestore(contentReturn)) {
                        Row {
                            LazyColumn(
                                Modifier.width(if (collapsed) 0.dp else 200.dp).height(400.dp)
                                    .focusRequester(listFocus)
                                    .onFocusChanged { if (it.hasFocus) contentReturn.remember { listFocus } }
                                    .guideCategoryColumnEntry(collapsed = collapsed, entryFocus = entryFocus)
                            ) {
                                items(groupCount) { i ->
                                    Box(
                                        Modifier.size(40.dp)
                                            .then(if (i == selectedGroup) Modifier.focusRequester(entryFocus) else Modifier)
                                            .focusRequester(groupRows[i])
                                            .named("group$i").focusable()
                                    )
                                }
                            }
                            LazyColumn(
                                Modifier.height(400.dp)
                                    .onFocusChanged { if (it.hasFocus) { collapsed = true; contentReturn.remember { channelRows[0] } } }
                                    .focusRestorer(firstChannel)
                            ) {
                                items(channelRows.size) { i ->
                                    Box(
                                        Modifier.size(40.dp)
                                            .then(if (i == 0) Modifier.focusRequester(firstChannel) else Modifier)
                                            .focusRequester(channelRows[i])
                                            .named("row$i").focusable()
                                    )
                                }
                            }
                        }
                    }
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
            }
        }
    }

    /** exitCategory(): expand first, then (once the column has its width) ask the column's requester. */
    private fun exitCategory() {
        rule.runOnIdle { collapsed = false }
        rule.waitForIdle()
        rule.runOnIdle { assertTrue("the column took the request", listFocus.requestFocusOrFalse()) }
    }

    @Test
    fun `LEFT from a channel row lands on the selected group, not Search`() {
        setGuide()
        // The viewer is on "All channels" and moves RIGHT into the list (the row's right = the channel list).
        rule.runOnIdle { groupRows[4].requestFocusPastRestorer() }
        rule.runOnIdle { assertEquals("history=$history", "group4", focused) }
        rule.runOnIdle { assertTrue(firstChannel.requestFocusOrFalse()) }
        rule.runOnIdle { assertEquals("history=$history", "row0", focused) }
        exitCategory()
        rule.runOnIdle { assertEquals("LEFT lands on the selected group; history=$history", "group4", focused) }
    }

    @Test
    fun `entering the column with no remembered row lands on the selected group`() {
        setGuide()
        // Docked browse mode: focus starts in the channel list and the column was never entered.
        rule.runOnIdle { assertTrue(firstChannel.requestFocusOrFalse()) }
        exitCategory()
        rule.runOnIdle { assertEquals("never Search by default (B238); history=$history", "group4", focused) }
    }

    @Test
    fun `a side rail round trip from the group column comes back to the last chosen group`() {
        setGuide()
        // The viewer chose the provider group (focus follows selection: its row is now the selected one).
        rule.runOnIdle { groupRows[5].requestFocusPastRestorer(); selectedGroup = 5 }
        rule.runOnIdle { assertEquals("history=$history", "group5", focused) }
        rule.runOnIdle { railOpen = true }
        rule.runOnIdle { assertTrue(railItem.requestFocusOrFalse()) }
        rule.runOnIdle { assertEquals("history=$history", "rail", focused) }
        rule.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        rule.waitForIdle()
        if (focused == null || focused == "rail") rule.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        rule.runOnIdle { assertEquals("the rail returns to the group left; history=$history", "group5", focused) }
    }
}
