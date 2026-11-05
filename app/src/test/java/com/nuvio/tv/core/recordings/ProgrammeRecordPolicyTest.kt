package com.nuvio.tv.core.recordings

import com.nuvio.tv.core.recordings.api.CaptureMode
import com.nuvio.tv.core.recordings.api.ProgrammeRecordPolicy
import com.nuvio.tv.core.recordings.api.RecordOffer
import com.nuvio.tv.core.recordings.api.RecordUnavailable
import com.nuvio.tv.core.recordings.api.RecordingCapabilities
import com.nuvio.tv.core.recordings.api.RecordingStatus
import com.nuvio.tv.core.recordings.api.RecordingsState
import com.nuvio.tv.core.recordings.api.SeriesOffer
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull

class ProgrammeRecordPolicyTest {
    private val withArchive = channel(catchUp = xtream(3))
    private val noArchive = channel()
    private val empty = RecordingsState(loaded = true)

    private fun offer(
        ch: com.nuvio.tv.core.recordings.api.RecordingChannel,
        start: Long = NOW + HOUR,
        end: Long = start + HOUR,
        caps: RecordingCapabilities = RecordingCapabilities.IPHONE,
        state: RecordingsState = empty,
    ) = ProgrammeRecordPolicy.offer(ch, start, end, NOW, caps, state)

    @Test
    fun `a channel with an archive offers a catch-up record`() {
        assertEquals(RecordOffer.Record(CaptureMode.CATCH_UP), offer(withArchive))
    }

    @Test
    fun `a past programme inside the archive window can still be recorded`() {
        assertEquals(RecordOffer.Record(CaptureMode.CATCH_UP), offer(withArchive, start = NOW - 5 * HOUR, end = NOW - 4 * HOUR))
    }

    @Test
    fun `no archive and no live capture is unavailable and says it needs catch-up`() {
        assertEquals(RecordOffer.Unavailable(RecordUnavailable.NEEDS_CATCH_UP), offer(noArchive))
    }

    @Test
    fun `a finished programme past the window is unavailable as over`() {
        val past = NOW - 10 * DAY
        assertEquals(RecordOffer.Unavailable(RecordUnavailable.PROGRAMME_OVER), offer(withArchive, start = past, end = past + HOUR))
    }

    @Test
    fun `a finished programme on a channel with no archive is over`() {
        assertEquals(RecordOffer.Unavailable(RecordUnavailable.PROGRAMME_OVER), offer(noArchive, start = NOW - 3 * HOUR, end = NOW - 2 * HOUR, caps = RecordingCapabilities.ANDROID))
    }

    @Test
    fun `live capture where the platform can do it offers a live record without an archive`() {
        assertEquals(RecordOffer.Record(CaptureMode.LIVE), offer(noArchive, caps = RecordingCapabilities.ANDROID))
    }

    @Test
    fun `a guide row without usable times is unavailable`() {
        assertEquals(RecordOffer.Unavailable(RecordUnavailable.BAD_TIMES), offer(withArchive, start = 0, end = 0))
        assertEquals(RecordOffer.Unavailable(RecordUnavailable.BAD_TIMES), offer(withArchive, start = NOW + HOUR, end = NOW + HOUR))
    }

    @Test
    fun `an already scheduled programme offers cancel instead`() {
        val s = schedule(id = "ch1:x", mode = CaptureMode.CATCH_UP, start = NOW + HOUR, ch = withArchive)
        val state = RecordingsState(schedules = listOf(s), loaded = true)
        assertEquals(RecordOffer.Scheduled("ch1:x", capturing = false), offer(withArchive, start = NOW + HOUR, state = state))
    }

    @Test
    fun `a capturing programme offers cancel and says it is capturing`() {
        val s = schedule(id = "s", mode = CaptureMode.CATCH_UP, start = NOW + HOUR, ch = withArchive)
        val r = recording("rec:s", status = RecordingStatus.RECORDING, start = NOW + HOUR).copy(scheduleId = "s")
        val state = RecordingsState(schedules = listOf(s), recordings = listOf(r), loaded = true)
        assertEquals(RecordOffer.Scheduled("s", capturing = true), offer(withArchive, start = NOW + HOUR, state = state))
    }

    @Test
    fun `a programme already saved is reported and not offered again`() {
        val r = recording("rec:s", status = RecordingStatus.DONE, start = NOW + HOUR, channelKey = "ch1")
        val state = RecordingsState(recordings = listOf(r), loaded = true)
        assertEquals(RecordOffer.AlreadySaved, offer(withArchive, start = NOW + HOUR, state = state))
    }

    @Test
    fun `a failed earlier try does not stop the viewer from recording again`() {
        val r = recording("rec:s", status = RecordingStatus.FAILED, start = NOW + HOUR, channelKey = "ch1")
        val state = RecordingsState(recordings = listOf(r), loaded = true)
        assertEquals(RecordOffer.Record(CaptureMode.CATCH_UP), offer(withArchive, start = NOW + HOUR, state = state))
    }

    @Test
    fun `the same start on another channel is not a match`() {
        val s = schedule(id = "x", start = NOW + HOUR, ch = channel(key = "other", catchUp = xtream(3)), mode = CaptureMode.CATCH_UP)
        val state = RecordingsState(schedules = listOf(s), loaded = true)
        assertEquals(RecordOffer.Record(CaptureMode.CATCH_UP), offer(withArchive, start = NOW + HOUR, state = state))
    }

    // ---- series -------------------------------------------------------------------------

    @Test
    fun `a series can be recorded on a channel with an archive`() {
        assertEquals(SeriesOffer.Record, ProgrammeRecordPolicy.seriesOffer(withArchive, "Show", RecordingCapabilities.IPHONE, empty))
    }

    @Test
    fun `a series needs an archive or live capture`() {
        assertEquals(SeriesOffer.Unavailable, ProgrammeRecordPolicy.seriesOffer(noArchive, "Show", RecordingCapabilities.IPHONE, empty))
        assertEquals(SeriesOffer.Record, ProgrammeRecordPolicy.seriesOffer(noArchive, "Show", RecordingCapabilities.ANDROID, empty))
    }

    @Test
    fun `a blank title cannot be a series`() {
        assertEquals(SeriesOffer.Unavailable, ProgrammeRecordPolicy.seriesOffer(withArchive, "  ", RecordingCapabilities.IPHONE, empty))
    }

    @Test
    fun `an existing rule for the same title folds NEW marks and shows as active`() {
        val rule = rule(id = "series:ch1:the show", title = "The Show", ch = withArchive)
        val state = RecordingsState(series = listOf(rule), loaded = true)
        assertEquals(SeriesOffer.Active("series:ch1:the show"), ProgrammeRecordPolicy.seriesOffer(withArchive, "The Show (NEW)", RecordingCapabilities.IPHONE, state))
    }

    @Test
    fun `a rule on another channel does not count`() {
        val rule = rule(id = "series:other:the show", title = "The Show", ch = channel(key = "other"))
        val state = RecordingsState(series = listOf(rule), loaded = true)
        assertEquals(SeriesOffer.Record, ProgrammeRecordPolicy.seriesOffer(withArchive, "The Show", RecordingCapabilities.IPHONE, state))
    }

    // ---- episode label --------------------------------------------------------------------

    @Test
    fun `the episode label is read from the title or the description`() {
        assertEquals("S02E05", ProgrammeRecordPolicy.episodeLabel("Show S02E05", null))
        assertEquals("S01E03", ProgrammeRecordPolicy.episodeLabel("Show", "Previously. S1 E3 - the fall"))
        assertNull(ProgrammeRecordPolicy.episodeLabel("Show", "Nothing numbered here"))
    }
}
