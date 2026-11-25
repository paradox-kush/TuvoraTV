package com.nuvio.tv.ui.screens.gamedetail

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.nuvio.tv.core.sports.detail.LineupPlayer
import com.nuvio.tv.core.sports.detail.screen.PitchSpot
import com.nuvio.tv.ui.theme.NuvioTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * B284: the football game screen crashed ("Padding must be non-negative") when a lineup dot sat within a label's
 * half-width of the pitch's left edge or near its top: the shirt number was placed with padding = position - half
 * its size. 14 crashes on ~11 boxes, v1.22-1.24.1. Edge spots must render, label centred on the dot.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class PitchShirtNumbersTest {
    @get:Rule val rule = createComposeRule()

    private fun spot(number: String, x: Float, y: Float) =
        PitchSpot(LineupPlayer(number = number, name = "P$number", pos = null, starter = true), x, y)

    @Test
    fun spotsOnThePitchEdgeRenderInsteadOfCrashing() {
        rule.setContent {
            NuvioTheme {
                Box(Modifier.size(400.dp, 200.dp)) {
                    PitchShirtNumbers(
                        home = listOf(spot("1", x = 0f, y = 0f), spot("9", x = 0.05f, y = 0.02f)),
                        away = listOf(spot("10", x = 1f, y = 0f)),
                    )
                }
            }
        }
        rule.onNodeWithText("1").assertExists()
        rule.onNodeWithText("9").assertExists()
        rule.onNodeWithText("10").assertExists()
    }
}
