package com.nuvio.tv.core.analytics

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class BreadcrumbsTest {

    private val captured = mutableListOf<Pair<String, Map<String, Any>>>()

    private fun arm() {
        captured.clear()
        Breadcrumbs.resetForTest { event, properties -> captured += event to properties }
    }

    @After
    fun tearDown() {
        Breadcrumbs.resetForTest()
    }

    @Test
    fun screenChangedDedupesConsecutiveRepeats() {
        arm()
        Breadcrumbs.screenChanged("home")
        Breadcrumbs.screenChanged("home")
        Breadcrumbs.screenChanged("stream/{launchId}")
        Breadcrumbs.screenChanged("home")
        assertEquals(
            listOf("home", "stream/{launchId}", "home"),
            captured.map { it.second[Breadcrumbs.SCREEN_NAME_PROPERTY] },
        )
        assertEquals(setOf(Breadcrumbs.SCREEN_EVENT), captured.map { it.first }.toSet())
    }

    @Test
    fun identicalStartsInsideTheDedupeWindowCollapse() {
        arm()
        Breadcrumbs.playbackStarted("live", "mpv", "player", "ts", nowMs = 0L)
        Breadcrumbs.playbackStarted("live", "mpv", "player", "ts", nowMs = 5_000L)
        Breadcrumbs.playbackStarted("live", "mpv", "player", "ts", nowMs = 60_000L)
        assertEquals(2, captured.size)
        assertEquals(setOf(Breadcrumbs.PLAYBACK_EVENT), captured.map { it.first }.toSet())
    }

    @Test
    fun differentChannelsAreNotDeduped() {
        arm()
        Breadcrumbs.playbackStarted("live", "mpv", "player", "ts", nowMs = 0L)
        Breadcrumbs.playbackStarted("live", "mpv", "player", "m3u8", nowMs = 1_000L)
        assertEquals(2, captured.size)
    }

    @Test
    fun hourlyCapBoundsAZapper() {
        arm()
        var now = 0L
        repeat(60) { index ->
            Breadcrumbs.playbackStarted("live", "mpv", "player", "c$index", nowMs = now)
            now += 20_000L
        }
        assertEquals(Breadcrumbs.MAX_PLAYBACK_EVENTS_PER_HOUR, captured.size)
    }

    @Test
    fun capacityReturnsOnceTheWindowSlides() {
        arm()
        repeat(Breadcrumbs.MAX_PLAYBACK_EVENTS_PER_HOUR) { index ->
            Breadcrumbs.playbackStarted("live", "mpv", "player", "c$index", nowMs = index * 1_000L)
        }
        Breadcrumbs.playbackStarted("live", "mpv", "player", "late", nowMs = 30 * 60_000L)
        assertEquals(Breadcrumbs.MAX_PLAYBACK_EVENTS_PER_HOUR, captured.size)
        Breadcrumbs.playbackStarted("live", "mpv", "player", "later", nowMs = 62 * 60_000L)
        assertEquals(Breadcrumbs.MAX_PLAYBACK_EVENTS_PER_HOUR + 1, captured.size)
    }
}
