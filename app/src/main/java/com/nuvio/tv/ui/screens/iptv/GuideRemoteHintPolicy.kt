package com.nuvio.tv.ui.screens.iptv

/**
 * B253: while the category column holds focus, the info panel must not read as if channel row 1 had
 * it. The hint then describes the category column, and the programme block is dimmed.
 * (The panel is covered by the video in fullscreen, so fullscreen is not an input.)
 */
internal object GuideRemoteHintPolicy {
    enum class Hint { CATEGORY, CHANNEL }

    fun hintFor(categoryFocused: Boolean): Hint =
        if (categoryFocused) Hint.CATEGORY else Hint.CHANNEL

    fun dimChannelInfo(categoryFocused: Boolean): Boolean = categoryFocused
}
