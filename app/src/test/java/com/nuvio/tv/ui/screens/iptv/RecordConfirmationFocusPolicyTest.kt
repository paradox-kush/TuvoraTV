package com.nuvio.tv.ui.screens.iptv

import com.nuvio.tv.core.recordings.api.ConfirmationKind
import com.nuvio.tv.ui.screens.iptv.RecordConfirmationFocusPolicy.Row
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordConfirmationFocusPolicyTest {

    private val bools = listOf(false, true)

    @Test
    fun `no combination ever starts the cursor on Undo`() {
        for (undoable in bools) for (exact in bools) for (view in bools) {
            val rows = RecordConfirmationFocusPolicy.rows(undoable, exact, view)
            assertNotEquals(
                "undoable=$undoable exact=$exact view=$view must not default to Undo",
                Row.UNDO, RecordConfirmationFocusPolicy.defaultFocus(rows),
            )
        }
    }

    @Test
    fun `the cursor starts on Done even when an exact-times offer is on the sheet`() {
        val rows = RecordConfirmationFocusPolicy.rows(undoable = true, offerExact = true, canViewRecordings = true)
        assertEquals("default focus", Row.DONE, RecordConfirmationFocusPolicy.defaultFocus(rows))
        assertTrue("the default row exists on the sheet", Row.DONE in rows)
    }

    @Test
    fun `Done is always present and Undo is only present for an undoable schedule`() {
        for (undoable in bools) for (exact in bools) for (view in bools) {
            val rows = RecordConfirmationFocusPolicy.rows(undoable, exact, view)
            assertTrue("Done present", Row.DONE in rows)
            assertEquals("Undo present iff undoable", undoable, Row.UNDO in rows)
            assertEquals("exact offer present iff offered", exact, Row.ALLOW_EXACT in rows)
            assertEquals("View present iff allowed", view, Row.VIEW_RECORDINGS in rows)
        }
    }

    @Test
    fun `Undo is last so it is reached only by moving there on purpose`() {
        val rows = RecordConfirmationFocusPolicy.rows(undoable = true, offerExact = true, canViewRecordings = true)
        assertEquals(Row.UNDO, rows.last())
        assertEquals(listOf(Row.DONE, Row.VIEW_RECORDINGS, Row.ALLOW_EXACT, Row.UNDO), rows)
    }

    @Test
    fun `a single schedule is undoable and a series or a refusal is not`() {
        listOf(ConfirmationKind.SET, ConfirmationKind.MAY_START_LATE, ConfirmationKind.SAVES_ON_NEXT_OPEN).forEach {
            assertTrue("$it single", RecordConfirmationFocusPolicy.undoable(it, isSeries = false))
            assertFalse("$it series", RecordConfirmationFocusPolicy.undoable(it, isSeries = true))
        }
        listOf(ConfirmationKind.ALREADY_SCHEDULED, ConfirmationKind.CANNOT_RECORD, ConfirmationKind.SERIES_NOTHING_YET).forEach {
            assertFalse("$it", RecordConfirmationFocusPolicy.undoable(it, isSeries = false))
        }
    }
}
