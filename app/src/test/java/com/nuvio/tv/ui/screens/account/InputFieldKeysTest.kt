package com.nuvio.tv.ui.screens.account

import android.view.KeyEvent
import androidx.compose.ui.focus.FocusDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
