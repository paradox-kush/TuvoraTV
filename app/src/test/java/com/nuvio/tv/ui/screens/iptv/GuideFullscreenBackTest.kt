package com.nuvio.tv.ui.screens.iptv

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.nuvio.tv.ui.screens.player.clean.live.LiveControlsPolicy
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
 * Guide fullscreen BACK while paused and time-shifted with the controls up: the first BACK hides the
 * controls (the player stays paused), the second leaves. Mirrors the screen's wiring (BackHandler, the
 * focus anchor behind the video, the button row's focusGroup + onExit, the real FullscreenControlsState,
 * the real policy and the real requestFocusOrFalse).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class GuideFullscreenBackTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private var exits = 0
    private val scrub = FocusRequester()
    private val buttonA = FocusRequester()
    private val buttonLast = FocusRequester()

    @Composable
    private fun Harness(controls: FullscreenControlsState, scrubbable: Boolean) {
        val anchor = remember { FocusRequester() }
        fun hideControls() {
            controls.reset()
            anchor.requestFocusOrFalse()
        }
        LaunchedEffect(controls.visible, scrubbable) {
            if (controls.visible) (if (scrubbable) scrub else buttonA).requestFocusOrFalse()
        }
        BackHandler(enabled = true) {
            when (LiveControlsPolicy.backAction(panelOpen = false, controlsVisible = controls.visible)) {
                LiveControlsPolicy.BackAction.HIDE_CONTROLS -> hideControls()
                else -> exits++
            }
        }
        Box(Modifier.size(200.dp)) {
            Box(Modifier.size(10.dp).focusRequester(anchor).focusable())
            if (controls.visible) {
                Box(Modifier.size(10.dp).focusRequester(scrub).onFocusChanged { if (it.isFocused) controls.keepAlive() }.focusable())
                Row(Modifier.focusGroup().focusProperties { onExit = { cancelFocusChange() } }) {
                    Box(Modifier.size(10.dp).focusRequester(buttonA).focusable())
                    Box(Modifier.size(10.dp).focusRequester(buttonLast).onFocusChanged { if (it.isFocused) controls.keepAlive() }.focusable())
                }
            }
        }
    }

    private fun back() {
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }

    private fun run(scrubbable: Boolean, focus: FocusRequester?) {
        val controls = FullscreenControlsState()
        controls.show()
        rule.setContent { Harness(controls, scrubbable) }
        rule.waitForIdle()
        if (focus != null) { rule.runOnUiThread { focus.requestFocusOrFalse() }; rule.waitForIdle() }

        back()
        assertFalse("first BACK hides the controls", controls.visible)
        assertEquals("and does not leave", 0, exits)
        back()
        assertEquals("second BACK leaves", 1, exits)
    }

    @Test fun `BACK with the scrub bar focused hides then leaves`() = run(scrubbable = true, focus = null)

    @Test fun `BACK with the last control focused hides then leaves`() = run(scrubbable = true, focus = buttonLast)

    @Test fun `BACK with a button focused and not scrubbable hides then leaves`() = run(scrubbable = false, focus = buttonLast)
}
