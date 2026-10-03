package com.nuvio.tv.ui.screens.iptv

import android.app.Application
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import com.nuvio.tv.core.iptv.XtreamProgram
import com.nuvio.tv.ui.theme.NuvioTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * B114 end to end on the real cells Composable: a timeline window with nothing to press (here the
 * future, on a channel with no archive) must still hold the cursor and keep travelling — before,
 * the landing requester was never attached, `requestFocus()` silently did nothing (it no longer
 * throws in current Compose), and LEFT/RIGHT went nowhere.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class GuideTimelineStripFocusTest {

    @get:Rule
    val rule = createComposeRule()

    private val hour = 60 * 60_000L
    private val now = 1_710_000_000_000L
    private val futureWindowStart = now + 4 * hour - (now + 4 * hour) % GuideTimeTravel.SLOT_MS
    private val channel = GuideChannel(
        contentId = "xtream:acc:live:7",
        name = "No Archive One",
        logo = null,
        streamUrl = "http://example.invalid/7.ts",
        streamId = 7,
        hasArchive = false,
    )
    private val laterTonight = XtreamProgram(
        title = "Later tonight",
        description = "",
        startMs = futureWindowStart,
        endMs = futureWindowStart + hour,
        nowPlaying = false,
    )

    @Test
    fun `a future window holds the cursor on the strip and LEFT and RIGHT keep travelling`() {
        val leading = FocusRequester()
        val trailing = FocusRequester()
        val strip = FocusRequester()
        val travels = mutableListOf<Int>()
        rule.setContent {
            NuvioTheme {
                Row(Modifier.width(900.dp).height(44.dp)) {
                    GuideProgrammeCells(
                        programmes = listOf(laterTonight),
                        channel = channel,
                        windowStartMs = futureWindowStart,
                        nowMs = now,
                        catchUpSupported = true,
                        interactive = true,
                        onProgrammeClick = {},
                        onTravel = { travels += it },
                        leadingEdgeFocus = leading,
                        trailingEdgeFocus = trailing,
                        stripFocus = strip,
                    )
                }
            }
        }
        rule.runOnIdle {
            assertFalse("a future programme is not a cell to stop on", leading.requestFocusOrFalse())
            assertTrue("the strip takes the cursor instead", strip.requestFocusOrFalse())
        }
        rule.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        rule.onRoot().performKeyInput { pressKey(Key.DirectionLeft) }
        rule.runOnIdle {
            assertEquals(
                "one page forward, one page back",
                listOf(GuideTimeTravel.EDGE_TRAVEL_SLOTS, -GuideTimeTravel.EDGE_TRAVEL_SLOTS),
                travels,
            )
        }
    }

    @Test
    fun `a window with a playable cell lands on the cell, and the strip is not a stop`() {
        val leading = FocusRequester()
        val strip = FocusRequester()
        val liveWindowStart = GuideTimeTravel.liveWindowStartMs(now)
        val airing = XtreamProgram("Airing", "", now - hour / 2, now + hour / 2, nowPlaying = true)
        rule.setContent {
            NuvioTheme {
                Row(Modifier.width(900.dp).height(44.dp)) {
                    GuideProgrammeCells(
                        programmes = listOf(airing),
                        channel = channel,
                        windowStartMs = liveWindowStart,
                        nowMs = now,
                        catchUpSupported = true,
                        interactive = true,
                        onProgrammeClick = {},
                        onTravel = {},
                        leadingEdgeFocus = leading,
                        trailingEdgeFocus = FocusRequester(),
                        stripFocus = strip,
                    )
                }
            }
        }
        rule.runOnIdle {
            assertTrue("the airing programme takes the cursor", leading.requestFocusOrFalse())
        }
    }
}
