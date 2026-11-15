package com.nuvio.tv.ui.screens.iptv

import android.app.Application
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.test.junit4.createComposeRule
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
 * Device pass 3, finding 3: "nothing focused" as a bug class. Two ways focus goes missing, two answers:
 * - the focused control leaves composition: Compose re-enters from the root by itself (this test proves it never
 *   stays empty), and the hub's `enter` (hubContentFocusRestore) sends that re-entry to the region the viewer was
 *   in, not the first control on screen (the tab);
 * - focus moves onto a control of zero size (the collapsed group column): collapsedFocusGuard, HubRailRoundTripTest.
 * A watch that re-lands focus when the hub "has none" never fires, because the first case refills at once.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class HubFocusSafetyNetTest {
    @get:Rule
    val rule = createComposeRule()

    private val tab = FocusRequester()
    private val rowA = FocusRequester()
    private val rowB = FocusRequester()
    private var showRowA by mutableStateOf(true)
    private var focused: String? = null
    private val contentReturn = HubContentReturn()

    private fun Modifier.named(name: String) = onFocusChanged { if (it.isFocused) focused = name else if (focused == name) focused = null }

    @Test
    fun `focus dropped by a disposed control re-enters the region the viewer was in, not the tab`() {
        rule.setContent {
            NuvioTheme {
                Column(Modifier.hubContentFocusRestore(contentReturn)) {
                    Box(Modifier.size(40.dp).focusRequester(tab).named("tab").focusable())
                    // The guide's channel list: it names the row to come back to (here the one that survives).
                    Row(Modifier.onFocusChanged { if (it.hasFocus) contentReturn.remember { rowB } }) {
                        if (showRowA) Box(Modifier.size(40.dp).focusRequester(rowA).named("rowA").focusable())
                        Box(Modifier.size(40.dp).focusRequester(rowB).named("rowB").focusable())
                    }
                }
            }
        }
        rule.runOnIdle { assertTrue(rowA.requestFocusOrFalse()) }
        rule.runOnIdle { showRowA = false }
        rule.waitForIdle()
        rule.runOnIdle { assertEquals("never nothing focused, and not the tab", "rowB", focused) }
    }
}
