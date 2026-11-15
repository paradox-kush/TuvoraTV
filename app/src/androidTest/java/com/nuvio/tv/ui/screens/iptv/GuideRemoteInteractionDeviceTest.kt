package com.nuvio.tv.ui.screens.iptv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import com.nuvio.tv.ui.screens.player.clean.live.LiveControlsPolicy
import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.core.iptv.GuideLayoutPreset
import com.nuvio.tv.core.iptv.GuideDisplayPreferences
import com.nuvio.tv.ui.theme.NuvioTheme
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises production rows/dialogs with physical key events; no provider or account writes. */
@RunWith(AndroidJUnit4::class)
class GuideRemoteInteractionDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val focus = FocusRequester()
    private var menu by mutableStateOf(false)
    private var size by mutableStateOf(false)
    private var preset by mutableStateOf(GuideLayoutPreset.STANDARD)
    private var favorites = 0
    private var hidden = 0
    private var plays = 0
    private val channel = GuideChannel("xtream:fixture:live:1", "Fixture TV", null, "http://example.invalid/1.ts", 1, hasArchive = false)

    private fun setup() {
        compose.setContent {
            NuvioTheme {
                Box(Modifier.width(900.dp).height(540.dp)) {
                    GuideChannelRow(number = 1, name = channel.name, isPlaying = true, logo = null, epg = null,
                        windowStartMs = 0, nowMs = 0, isFavorite = false, isPinned = false, lockFocus = false,
                        onFocused = {}, onClick = { plays++ }, onLongClick = { menu = true }, onHide = { menu = true },
                        focusRequester = focus, channel = channel, catchUpSupported = false, timelineActive = false,
                        onEnterTimeline = {}, onLeaveTimeline = {}, onTravel = { false }, onBackOutOfTimeline = {},
                        onProgrammeClick = {}, layout = preset)
                    fun close() { menu = false; size = false }
                    // Redesign change 3: Watch full screen, Add to Favorites, Guide size, Hide channel.
                    if (menu) GuideChannelPanel(channel.name,
                        GuideChannelPanelPolicy.rows(GuideChannelPanelPolicy.Variant.CHANNEL, false,
                            listOf(GuideChannelMenuPolicy.Action.HIDE), noGuide = false, guideSizeHere = true),
                        focusRow = null, guideSize = preset,
                        onRow = { row ->
                            when (row) {
                                GuideChannelPanelPolicy.Row.ADD_FAVORITE -> { favorites++; close() }
                                GuideChannelPanelPolicy.Row.HIDE -> { hidden++; close() }
                                GuideChannelPanelPolicy.Row.GUIDE_SIZE -> { menu = false; size = true }
                                else -> close()
                            }
                        }, onClose = { close() })
                    if (size) GuideLayoutPanel(preset, onSelect = { preset = it; close() }, onClose = { close() })
                }
                LaunchedEffect(menu, size) { if (!menu && !size) focus.requestFocus() }
            }
        }
        compose.waitForIdle()
    }
    private fun key(code: Int) { instrumentation.sendKeyDownUpSync(code); compose.waitForIdle() }
    private fun save(label: String) {
        val dir = File(instrumentation.targetContext.getExternalFilesDir(null), "guide-remote-ux").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            File(dir, "$label.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
    @Test fun menuAndBackLeaveChannelUnchangedUntilAnActionIsChosen() {
        setup()
        key(KeyEvent.KEYCODE_MENU)
        compose.onNodeWithText("Watch full screen").assertIsFocused()
        compose.onNodeWithText("Add to Favorites").assertIsDisplayed()
        compose.onNodeWithText("Hide channel").assertIsDisplayed()
        assertEquals(0, favorites); assertEquals(0, hidden); assertEquals(0, plays)
        save("channel-menu")
        key(KeyEvent.KEYCODE_BACK)
        compose.onNode(hasClickAction() and hasText("Fixture TV", substring = true)).assertIsFocused()
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(1, plays)
        key(KeyEvent.KEYCODE_MENU)
        repeat(3) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(1, hidden); assertEquals(0, favorites)
    }
    @Test fun heldOkOpensOptionsWithoutTuningOrFavoriting() {
        setup()
        instrumentation.sendKeySync(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER))
        compose.mainClock.advanceTimeBy(800)
        compose.waitForIdle()
        instrumentation.sendKeySync(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER))
        compose.waitForIdle()
        compose.onNodeWithText("Add to Favorites").assertIsDisplayed()
        assertEquals(0, favorites); assertEquals(0, hidden); assertEquals(0, plays)
        key(KeyEvent.KEYCODE_BACK)
        compose.onNode(hasClickAction() and hasText("Fixture TV", substring = true)).assertIsFocused()
    }
    @Test fun guideSizeChangesActualRowAndKeepsFocus() {
        setup()
        for ((label, expected) in listOf("Compact" to GuideLayoutPreset.COMPACT, "Large" to GuideLayoutPreset.LARGE, "Standard" to GuideLayoutPreset.STANDARD)) {
            key(KeyEvent.KEYCODE_MENU)
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.onNodeWithText("Compact").assertIsDisplayed()
            // The picker opens on the size already in use, so arrow presses are relative to it.
            val current = preset.name.lowercase().replaceFirstChar { it.uppercase() }
            compose.waitUntil(2_000) { runCatching { compose.onNodeWithText(current).assertIsFocused() }.isSuccess }
            val delta = expected.ordinal - preset.ordinal
            repeat(kotlin.math.abs(delta)) { key(if (delta < 0) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN) }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.waitForIdle()
            val row = compose.onNode(hasClickAction() and hasText("Fixture TV", substring = true))
            row.assertIsFocused().assertHeightIsEqualTo((expected.rowHeightDp - ROW_VERTICAL_PADDING_DP * 2).dp) // the row's 1dp vertical padding sits inside its slot
            assertEquals(expected, preset)
            save("layout-$label")
        }
        assertEquals(0, plays); assertEquals(0, hidden)
    }
    @Test fun fullscreenBrowserMovesHighlightWithoutTuningAndBackClosesOnlyThePanel() {
        val second = channel.copy(contentId = "xtream:fixture:live:2", name = "Nature TV", streamId = 2)
        val anchor = FocusRequester()
        var panel by mutableStateOf(false)
        var picked: String? = null
        var exits = 0
        compose.setContent {
            NuvioTheme {
                BackHandler(enabled = !panel) { exits++ }
                Box(Modifier.fillMaxSize().onPreviewKeyEvent { event ->
                    val action = LiveControlsPolicy.keyAction(event.key, event.type == KeyEventType.KeyDown,
                        controlsVisible = false, paused = false, panelOpen = panel, hasLineup = true)
                    when (action) {
                        LiveControlsPolicy.KeyAction.OPEN_CHANNEL_LIST -> { panel = true; true }
                        LiveControlsPolicy.KeyAction.CONSUME -> true
                        else -> false
                    }
                }) {
                    Box(Modifier.focusRequester(anchor).focusable())
                    if (panel) LiveChannelListOverlay(listOf(channel, second), emptyMap(), channel.contentId, favoriteIds = emptySet(),
                        onBrowse = {}, onPick = { picked = it.contentId; panel = false }, onClose = { panel = false })
                }
                LaunchedEffect(panel) { if (!panel) anchor.requestFocus() }
            }
        }
        compose.waitForIdle()
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        compose.onNodeWithText("Nature TV", substring = true).assertIsDisplayed()
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        assertNull(picked)
        save("fullscreen-channel-browser")
        key(KeyEvent.KEYCODE_BACK)
        assertFalse(panel); assertEquals(0, exits)
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(second.contentId, picked); assertFalse(panel)
    }

    @Test fun guideSizePersistsWithoutAccountOrNetworkWork(): Unit = runBlocking {
        val prefs = GuideDisplayPreferences(instrumentation.targetContext)
        val original = prefs.layout.first()
        try {
            // Not the default, so a pass proves the choice was stored.
            prefs.setLayout(GuideLayoutPreset.LARGE)
            assertEquals(GuideLayoutPreset.LARGE, GuideDisplayPreferences(instrumentation.targetContext).layout.first())
        } finally { prefs.setLayout(original) }
    }
    private companion object { const val ROW_VERTICAL_PADDING_DP = 1 }
}
