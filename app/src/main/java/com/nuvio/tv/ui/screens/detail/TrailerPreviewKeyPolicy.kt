package com.nuvio.tv.ui.screens.detail

import android.view.KeyEvent

/**
 * What a remote key does while the detail page's AUTO trailer preview is playing (the muted-UI
 * preview that starts [MetaDetailsViewModel]'s trailer delay after the Play button takes focus —
 * 7 s by default — and hides the hero buttons).
 *
 * B118 (Onn pass 2026-10-03): pressing Play about 8 s after landing showed the trailer instead of
 * the sources. The preview had just started, and it swallowed every key but Back, so the press the
 * viewer aimed at the Play button they had been looking at did nothing but leave the trailer
 * playing. The preview only ever arms while Play is focused, so OK (or a Play key) during it means
 * exactly that: stop the preview and play the title. Back still just stops it; everything else is
 * still swallowed so the page doesn't scroll under the preview.
 *
 * Pure so the routing is pinned by tests; the screen owns the side effects.
 */
internal object TrailerPreviewKeyPolicy {

    enum class Action {
        /** Not ours — let the screen's normal handling (Back) run. */
        PASS,

        /** Stop the preview, put focus back on Play, and play the title. */
        PLAY,

        /** Consume and do nothing. */
        SWALLOW,
    }

    /**
     * [selectDownSeen] = this preview already acted on a select key's DOWN, so its UP must not act
     * again. An UP with no DOWN seen is a press that began on the Play button a beat before the
     * preview started — that release is the click the viewer meant.
     */
    fun actionFor(keyCode: Int, action: Int, repeatCount: Int, selectDownSeen: Boolean = false): Action {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) return Action.PASS
        if (!isPlayKey(keyCode)) return Action.SWALLOW
        return when (action) {
            KeyEvent.ACTION_DOWN -> if (repeatCount == 0) Action.PLAY else Action.SWALLOW
            KeyEvent.ACTION_UP -> if (selectDownSeen) Action.SWALLOW else Action.PLAY
            else -> Action.SWALLOW
        }
    }

    private fun isPlayKey(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_NUMPAD_ENTER,
        KeyEvent.KEYCODE_MEDIA_PLAY,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> true
        else -> false
    }
}
