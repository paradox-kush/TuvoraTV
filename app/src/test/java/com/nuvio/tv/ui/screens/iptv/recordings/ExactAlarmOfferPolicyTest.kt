package com.nuvio.tv.ui.screens.iptv.recordings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExactAlarmOfferPolicyTest {

    @Test
    fun `the offer is on the Recordings page while exact alarms are off and something is scheduled`() {
        assertTrue(ExactAlarmOfferPolicy.showOnRecordingsScreen(exactAllowed = false, scheduledCount = 1, settingsAvailable = true))
        assertTrue(ExactAlarmOfferPolicy.showOnRecordingsScreen(exactAllowed = false, scheduledCount = 7, settingsAvailable = true))
    }

    @Test
    fun `closing the confirmation sheet without choosing does not lose the offer`() {
        // The sheet's own once-only offer is spent on first show, yet the page decision reads only
        // the live state: it is still true afterwards.
        val afterSheetClosed = ExactAlarmOfferPolicy.showOnRecordingsScreen(exactAllowed = false, scheduledCount = 1, settingsAvailable = true)
        assertTrue("offer survives the sheet", afterSheetClosed)
    }

    @Test
    fun `no offer once exact alarms are allowed, with nothing scheduled, or with no settings screen`() {
        assertFalse("allowed", ExactAlarmOfferPolicy.showOnRecordingsScreen(exactAllowed = true, scheduledCount = 3, settingsAvailable = true))
        assertFalse("nothing scheduled", ExactAlarmOfferPolicy.showOnRecordingsScreen(exactAllowed = false, scheduledCount = 0, settingsAvailable = true))
        assertFalse("no settings screen", ExactAlarmOfferPolicy.showOnRecordingsScreen(exactAllowed = false, scheduledCount = 2, settingsAvailable = false))
    }
}
