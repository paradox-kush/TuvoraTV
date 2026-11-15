package com.nuvio.tv.ui.screens.iptv

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import com.nuvio.tv.ui.theme.NuvioTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * Device pass 2, finding B: in the guide, "9" showed "No channel 9" with row 9 on screen. The 1.5 s auto-commit ran
 * in an effect keyed only on the entry state, so it kept the commit function of the guide's FIRST composition, when
 * the channel list was still empty; every timed commit matched against 0 rows. (OK commits from the live key
 * handler, which is why OK, and full screen's OK, worked.) The timer must commit against the list on screen now.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class NumberEntryTimersTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `the timed commit matches against the rows on screen now, not at first composition`() {
        val entry = NumberEntryState()
        var rows by mutableStateOf(0)
        var committed: Int? = null
        rule.mainClock.autoAdvance = false
        rule.setContent {
            NuvioTheme {
                val count = rows
                NumberEntryTimers(entry) {
                    committed = NumberEntryPolicy.match(entry.take(), count)
                }
            }
        }
        rule.mainClock.advanceTimeByFrame()
        // The list lands after the guide first composed.
        rule.runOnIdle { rows = 834 }
        rule.mainClock.advanceTimeByFrame()
        rule.runOnIdle { entry.digit(9) }
        rule.mainClock.advanceTimeBy(NumberEntryPolicy.COMMIT_MS + 100)
        rule.runOnIdle { assertEquals("9 lands on row 9 (index 8) of the list on screen", 8, committed) }
    }
}
