package com.nuvio.tv.ui.screens.detail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibraryPickerReminderRowTest {
    @Test
    fun noRowWhenTitleCannotBeFollowed() {
        assertNull("no bell key -> no reminder row", LibraryPickerReminderRow.forBell(null))
    }

    @Test
    fun rowReflectsOffState() {
        val row = LibraryPickerReminderRow.forBell(false)
        assertEquals("off state shown", false, row?.isOn)
        assertEquals("toggling an off row turns it on", true, row?.nextState)
    }

    @Test
    fun rowReflectsOnState() {
        val row = LibraryPickerReminderRow.forBell(true)
        assertEquals("on state shown", true, row?.isOn)
        assertEquals("toggling an on row turns it off", false, row?.nextState)
    }
}
