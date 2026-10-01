package com.nuvio.tv.ui.screens.account

import android.view.KeyEvent
import androidx.compose.ui.focus.FocusDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InputFieldKeysTest {

    @Test
    fun `D-pad Down while editing leaves the field downward`() {
        assertEquals(
            "B69: Down must reach Sign In from the password field",
            FocusDirection.Down,
            InputFieldKeys.exitDirection(isEditing = true, isKeyDown = true, keyCode = KeyEvent.KEYCODE_DPAD_DOWN),
        )
    }

    @Test
    fun `D-pad Up while editing leaves the field upward`() {
        assertEquals(
            "Up moves back to the previous field",
            FocusDirection.Up,
            InputFieldKeys.exitDirection(isEditing = true, isKeyDown = true, keyCode = KeyEvent.KEYCODE_DPAD_UP),
        )
    }

    @Test
    fun `Left and Right stay with the text field for the cursor`() {
        assertNull("Left moves the cursor", InputFieldKeys.exitDirection(true, true, KeyEvent.KEYCODE_DPAD_LEFT))
        assertNull("Right moves the cursor", InputFieldKeys.exitDirection(true, true, KeyEvent.KEYCODE_DPAD_RIGHT))
    }

    @Test
    fun `nothing is intercepted when not editing or on key up`() {
        assertNull("not editing: normal focus handling", InputFieldKeys.exitDirection(false, true, KeyEvent.KEYCODE_DPAD_DOWN))
        assertNull("key up is ignored", InputFieldKeys.exitDirection(true, false, KeyEvent.KEYCODE_DPAD_DOWN))
    }

    // UX30 / UX76: the playlist form popped the keyboard every time D-pad focus landed on a field.
    // A field is a row until OK is pressed on it; only OK/ENTER starts editing (and the keyboard).

    @Test
    fun `OK or ENTER on a field that is not being edited starts editing`() {
        assertTrue("DPAD_CENTER", InputFieldKeys.startsEditing(isEditing = false, isKeyDown = true, keyCode = KeyEvent.KEYCODE_DPAD_CENTER))
        assertTrue("ENTER", InputFieldKeys.startsEditing(isEditing = false, isKeyDown = true, keyCode = KeyEvent.KEYCODE_ENTER))
        assertTrue("NUMPAD_ENTER", InputFieldKeys.startsEditing(isEditing = false, isKeyDown = true, keyCode = KeyEvent.KEYCODE_NUMPAD_ENTER))
    }

    @Test
    fun `moving focus onto or past a field never starts editing`() {
        assertFalse("DOWN steps over", InputFieldKeys.startsEditing(false, true, KeyEvent.KEYCODE_DPAD_DOWN))
        assertFalse("UP steps over", InputFieldKeys.startsEditing(false, true, KeyEvent.KEYCODE_DPAD_UP))
        assertFalse("key up of OK is not a second start", InputFieldKeys.startsEditing(false, false, KeyEvent.KEYCODE_DPAD_CENTER))
        assertFalse("already editing", InputFieldKeys.startsEditing(true, true, KeyEvent.KEYCODE_DPAD_CENTER))
    }

    @Test
    fun `BACK while editing returns to the row instead of closing the dialog`() {
        assertTrue("editing", InputFieldKeys.stopsEditingOnBack(isEditing = true, keyCode = KeyEvent.KEYCODE_BACK))
        assertFalse("not editing: BACK closes as usual", InputFieldKeys.stopsEditingOnBack(isEditing = false, keyCode = KeyEvent.KEYCODE_BACK))
        assertFalse("other keys", InputFieldKeys.stopsEditingOnBack(isEditing = true, keyCode = KeyEvent.KEYCODE_DPAD_LEFT))
    }
}
