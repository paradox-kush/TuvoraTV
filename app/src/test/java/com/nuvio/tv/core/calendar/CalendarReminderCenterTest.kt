package com.nuvio.tv.core.calendar

import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarReminderCenterTest {
    private val now = Instant.parse("2026-11-04T12:00:00Z")

    private fun show(key: String, airedAgo: Duration) = CalendarTitleRecord(
        "series:imdb:$key", CalendarTitleType.SERIES, CalendarIds(imdb = key), name = key,
        sources = mapOf(CalendarSource.TVMAZE to listOf(CalendarEntry(season = 1, episode = 1, airInstantMs = now.minus(airedAgo).toEpochMilli())))
    )

    private class FakeData(titles: List<CalendarTitleRecord>, bells: Set<String>, private val nowMs: Long, run: CalendarRunResult? = null) : CalendarReminderData {
        override val state = MutableStateFlow(CalendarState(profileId = 1, loaded = true, titles = titles, lastRun = run))
        override val settings = MutableStateFlow<CalendarSettings?>(CalendarSettings(profileId = 1, bellOn = bells))
        val seen = mutableListOf<String>()
        val refreshes = mutableListOf<CalendarRefreshReason>()
        override suspend fun dueReminders() = dueReminders(state.value.titles, settings.value!!.bellOn, seen.toSet(), { _, _ -> false }, Instant.ofEpochMilli(nowMs))
        override suspend fun markSeen(episodeKeys: Collection<String>) { seen += episodeKeys }
        override fun requestRefresh(reason: CalendarRefreshReason) { refreshes += reason }
        override fun nowMs(): Long = nowMs
    }

    @Test fun homeShowsWhatIsDueOnTheCardAndMarksItSeen() = runBlocking {
        val data = FakeData(listOf(show("tt1", Duration.ofHours(3))), setOf("series:imdb:tt1"), now.toEpochMilli())
        val center = CalendarReminderCenter(data)
        center.onHomeResumed()
        assertEquals("card row", listOf("series:imdb:tt1|S1E1"), center.card.value?.items?.map { it.episodeKey })
        assertEquals("seen", listOf("series:imdb:tt1|S1E1"), data.seen)
        assertEquals("never ran: Home with a bell refreshes", listOf(CalendarRefreshReason.HOME_RESUMED), data.refreshes)
    }

    @Test fun withoutABellHomeNeitherShowsACardNorRefreshes() = runBlocking {
        val data = FakeData(listOf(show("tt1", Duration.ofHours(3))), emptySet(), now.toEpochMilli())
        CalendarReminderCenter(data).onHomeResumed()
        assertTrue("no refresh for a Home that does not use the Calendar", data.refreshes.isEmpty())
    }

    @Test fun theCornerNoteAnnouncesOnlyWhatBecameDueAfterTheFirstCheck() = runBlocking {
        val data = FakeData(listOf(show("tt1", Duration.ofHours(3))), setOf("series:imdb:tt1", "series:imdb:tt2"), now.toEpochMilli())
        val center = CalendarReminderCenter(data)
        center.checkCorner()
        assertNull("already due at start belongs to the Home card", center.corner.value)
        data.state.value = data.state.value.copy(titles = data.state.value.titles + show("tt2", Duration.ofHours(2)))
        val wait = center.checkCorner()
        assertNotNull("new one announced", center.corner.value)
        assertEquals("names", listOf("tt2"), center.corner.value!!.names)
        assertEquals("nothing else waiting: sleep the max", Duration.ofHours(6), wait)
        data.settings.value = data.settings.value!!.copy(cornerMessage = false)
        center.dismissCorner(center.corner.value!!.id)
        data.state.value = data.state.value.copy(titles = data.state.value.titles + show("tt3", Duration.ofHours(2)))
        data.settings.value = data.settings.value!!.copy(bellOn = data.settings.value!!.bellOn + "series:imdb:tt3")
        center.checkCorner()
        assertNull("corner note switched off", center.corner.value)
    }
}
