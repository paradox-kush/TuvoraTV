package com.nuvio.tv.playback.android.output

import com.nuvio.tv.playback.core.VideoDimensions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidDisplayModeSelectorTest {
    @Test
    fun `cadence ranking is the highest exact multiple then two and a half times`() {
        // Kodi (default whitelist), SmartTube, Nova, Just Player and Apple TV all prefer the
        // highest exact multiple: every frame is held the same number of refreshes and the UI
        // stays responsive.
        assertSwitch(24f, modes(1 to 50f, 2 to 24f, 3 to 48f), expectedModeId = 3)
        assertSwitch(24f, modes(1 to 50f, 3 to 48f, 4 to 72f), expectedModeId = 4)
        assertSwitch(24f, modes(1 to 50f, 2 to 24f), expectedModeId = 2)
        assertSwitch(24f, modes(1 to 50f, 5 to 60f), expectedModeId = 5)
        assertTrue(
            select(24f, modes(1 to 60f, 4 to 72f)) is AndroidDisplayModeSelection.Switch,
        )
    }

    @Test
    fun `PAL live doubles to 50 Hz instead of dropping the display to 25 Hz`() {
        // Device-measured on the Onn (2026-10-02): 25p at 59.94 Hz held frames 2 or 3 vsyncs
        // (judder); at 50 Hz 298 of 310 frames held exactly 2. The Onn also offers 25 Hz, which
        // would make the remote and UI feel sluggish.
        val onn = modes(1 to 59.94f, 2 to 25f, 3 to 50f, 4 to 60f)
        assertSwitch(25f, onn, expectedModeId = 3)
        assertSwitch(50f, onn, expectedModeId = 3)
    }

    @Test
    fun `NTSC rates double to the matching fractional 60 family`() {
        val modes = modes(1 to 50f, 2 to 29.97f, 3 to 59.94f, 4 to 60f)
        assertSwitch(30000f / 1001f, modes, expectedModeId = 3)
        assertSwitch(30f, modes, expectedModeId = 4)
        assertSwitch(60000f / 1001f, modes, expectedModeId = 3)
    }

    @Test
    fun `a sub 48 Hz output is chosen only for film rates`() {
        // 25 Hz and 30 Hz outputs are never chosen: if 50/60 is missing we stay put.
        assertTrue(
            select(25f, modes(1 to 59.94f, 2 to 25f)) is AndroidDisplayModeSelection.AlreadyEffective,
        )
        assertTrue(
            select(30f, modes(1 to 50f, 2 to 30f)) is AndroidDisplayModeSelection.AlreadyEffective,
        )
        assertSwitch(24000f / 1001f, modes(1 to 59.94f, 2 to 23.976f), expectedModeId = 2)
    }

    @Test
    fun `the current mode is kept when it already is the best multiple`() {
        val selection = AndroidDisplayModeSelector.select(
            AndroidDisplayModeSelectionInput(
                display = AndroidDisplayModeSnapshot(
                    currentModeId = 3,
                    supportedModes = modes(1 to 59.94f, 2 to 25f, 3 to 50f),
                ),
                factualFrameRate = 25f,
                factualDimensions = null,
                resolutionMatchingEnabled = false,
            ),
        )
        assertTrue(selection is AndroidDisplayModeSelection.AlreadyEffective)
    }

    @Test
    fun `factual fractional rate selects matching fractional display mode`() {
        assertSwitch(
            frameRate = 24000f / 1001f,
            modes = modes(1 to 60f, 2 to 23.976f, 3 to 24f),
            expectedModeId = 2,
        )
    }

    @Test
    fun `same resolution is required by default`() {
        val selection = select(
            frameRate = 24f,
            modes = listOf(mode(1, 1920, 1080, 60f), mode(2, 3840, 2160, 24f)),
            dimensions = VideoDimensions(3840, 2160),
            resolutionMatching = false,
        )
        assertTrue(selection is AndroidDisplayModeSelection.AlreadyEffective)
    }

    @Test
    fun `resolution matching chooses nearest supported dimensions before cadence`() {
        val selection = select(
            frameRate = 24f,
            modes = listOf(
                mode(1, 1920, 1080, 60f),
                mode(2, 3840, 2160, 24f),
                mode(3, 4096, 2160, 24f),
            ),
            dimensions = VideoDimensions(3800, 2100),
            resolutionMatching = true,
        )
        assertEquals(2, (selection as AndroidDisplayModeSelection.Switch).mode.modeId)
    }

    @Test
    fun `resolution only preserves the closest current display refresh`() {
        val selection = select(
            frameRate = null,
            modes = listOf(
                mode(1, 1920, 1080, 59.94f),
                mode(2, 3840, 2160, 24f),
                mode(3, 3840, 2160, 60f),
            ),
            dimensions = VideoDimensions(3840, 2160),
            resolutionMatching = true,
        )
        assertEquals(3, (selection as AndroidDisplayModeSelection.Switch).mode.modeId)
    }

    @Test
    fun `fallback is deterministic and keeps current on an equal cadence score`() {
        val selection = select(24f, modes(1 to 58f, 2 to 62f))
        assertTrue(selection is AndroidDisplayModeSelection.AlreadyEffective)
    }

    @Test
    fun `invalid topology has no compatible mode`() {
        val selection = select(24f, listOf(mode(0, 1920, 1080, 24f)))
        assertTrue(selection is AndroidDisplayModeSelection.NoCompatibleMode)
        assertTrue(
            select(
                frameRate = 9f,
                modes = listOf(mode(1, 1920, 1080, 60f), mode(2, 3840, 2160, 60f)),
                dimensions = VideoDimensions(3840, 2160),
                resolutionMatching = true,
            ) is AndroidDisplayModeSelection.NoCompatibleMode,
        )
    }

    private fun assertSwitch(
        frameRate: Float,
        modes: List<AndroidDisplayMode>,
        expectedModeId: Int,
    ) {
        val selection = select(frameRate, modes)
        assertEquals(expectedModeId, (selection as AndroidDisplayModeSelection.Switch).mode.modeId)
    }

    private fun select(
        frameRate: Float?,
        modes: List<AndroidDisplayMode>,
        dimensions: VideoDimensions? = null,
        resolutionMatching: Boolean = false,
    ) = AndroidDisplayModeSelector.select(
        AndroidDisplayModeSelectionInput(
            display = AndroidDisplayModeSnapshot(currentModeId = 1, supportedModes = modes),
            factualFrameRate = frameRate,
            factualDimensions = dimensions,
            resolutionMatchingEnabled = resolutionMatching,
        ),
    )

    private fun modes(vararg values: Pair<Int, Float>) =
        values.map { (id, rate) -> mode(id, 1920, 1080, rate) }

    private fun mode(id: Int, width: Int, height: Int, rate: Float) =
        AndroidDisplayMode(id, width, height, rate)
}
