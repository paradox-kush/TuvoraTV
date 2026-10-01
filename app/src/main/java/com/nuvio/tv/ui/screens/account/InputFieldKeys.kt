package com.nuvio.tv.ui.screens.account

import android.view.KeyEvent
import androidx.compose.ui.focus.FocusDirection

/**
 * Which way a D-pad press should move focus out of an [InputField] that is being edited, or null
 * to let the text field handle the key (B69: Up/Down were swallowed, trapping focus in the
 * password field). Left/Right stay with the text field to move the cursor. Pure for testing.
 *
 * Also the row-then-OK rule shared with the playlist form (UX30 / UX76): a field is a plain row
 * while D-pad focus passes over it; only OK/ENTER starts editing (and so opens the keyboard), and
 * BACK while editing returns to the row instead of closing the surrounding dialog.
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

    /** True when this key press on a not-yet-editing field should start editing (OK / ENTER down). */
    fun startsEditing(isEditing: Boolean, isKeyDown: Boolean, keyCode: Int): Boolean =
        !isEditing && isKeyDown && (
            keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                keyCode == KeyEvent.KEYCODE_ENTER ||
                keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
            )

    /** True when BACK should end editing (focus back to the row) rather than reach the dialog. */
    fun stopsEditingOnBack(isEditing: Boolean, keyCode: Int): Boolean =
        isEditing && keyCode == KeyEvent.KEYCODE_BACK
}
