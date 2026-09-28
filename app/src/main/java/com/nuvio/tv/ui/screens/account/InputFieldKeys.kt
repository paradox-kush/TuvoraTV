package com.nuvio.tv.ui.screens.account

import android.view.KeyEvent
import androidx.compose.ui.focus.FocusDirection

/**
 * Which way a D-pad press should move focus out of an [InputField] that is being edited, or null
 * to let the text field handle the key (B69: Up/Down were swallowed, trapping focus in the
 * password field). Left/Right stay with the text field to move the cursor. Pure for testing.
 */
internal object InputFieldKeys {
    fun exitDirection(isEditing: Boolean, isKeyDown: Boolean, keyCode: Int): FocusDirection? {
        if (!isEditing || !isKeyDown) return null
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN -> FocusDirection.Down
            KeyEvent.KEYCODE_DPAD_UP -> FocusDirection.Up
            else -> null
        }
    }
}
