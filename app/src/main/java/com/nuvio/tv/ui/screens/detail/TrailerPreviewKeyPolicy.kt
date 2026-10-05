package com.nuvio.tv.ui.screens.detail

/** STUB (red step): today's behaviour — every key but Back/Escape is swallowed. */
internal object TrailerPreviewKeyPolicy {
    enum class Action { PASS, PLAY, SWALLOW }

    fun actionFor(keyCode: Int, action: Int, repeatCount: Int): Action =
        if (keyCode == android.view.KeyEvent.KEYCODE_BACK || keyCode == android.view.KeyEvent.KEYCODE_ESCAPE) Action.PASS else Action.SWALLOW
}
