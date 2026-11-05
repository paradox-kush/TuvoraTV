package com.nuvio.tv.ui.screens.iptv

import com.nuvio.tv.core.iptv.XtreamCatchUp.ProgrammeAction
import com.nuvio.tv.core.iptv.XtreamProgram
import org.junit.Assert.assertEquals
import org.junit.Test

class GuideCellIntentRecordTest {
    private val now = 1_794_000_000_000L
    private fun programme(start: Long, end: Long) = XtreamProgram("Show", "", start, end, nowPlaying = false)

    private fun intent(action: ProgrammeAction, p: XtreamProgram, canRecordLive: Boolean = false) =
        GuideCellIntent.forProgramme(action, p, now, canRecordLive)

    @Test
    fun `OK on a programme that has not started opens the sheet so it can be recorded`() {
        assertEquals(GuideCellIntent.Intent.OPEN_SHEET, intent(ProgrammeAction.NONE, programme(now + 3_600_000, now + 7_200_000)))
    }

    @Test
    fun `OK on an aired programme with nothing kept still does nothing here`() {
        assertEquals(GuideCellIntent.Intent.NONE, intent(ProgrammeAction.NONE, programme(now - 7_200_000, now - 3_600_000)))
    }

    @Test
    fun `a guide row with no usable times does not open the sheet`() {
        assertEquals(GuideCellIntent.Intent.NONE, intent(ProgrammeAction.NONE, programme(now + 3_600_000, now + 3_600_000)))
    }

    @Test
    fun `a finished replay still plays instantly and the airing archive programme still asks`() {
        assertEquals(GuideCellIntent.Intent.REPLAY, intent(ProgrammeAction.REPLAY, programme(now - 7_200_000, now - 3_600_000)))
        assertEquals(GuideCellIntent.Intent.OPEN_SHEET, intent(ProgrammeAction.START_OVER, programme(now - 600_000, now + 3_000_000)))
    }

    @Test
    fun `live plays at once unless the device can record live`() {
        val airing = programme(now - 600_000, now + 3_000_000)
        assertEquals(GuideCellIntent.Intent.PLAY_LIVE, intent(ProgrammeAction.PLAY_LIVE, airing, canRecordLive = false))
        assertEquals(GuideCellIntent.Intent.OPEN_SHEET, intent(ProgrammeAction.PLAY_LIVE, airing, canRecordLive = true))
    }
}
