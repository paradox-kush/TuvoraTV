package com.nuvio.tv.core.startup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupWorkPolicyTest {

    @Test
    fun `headless process start runs no first-UI task`() {
        val atStart = StartupWorkPolicy.tasksFor(StartupTrigger.PROCESS_START)
        assertEquals(
            "process start runs only BACKGROUND work",
            emptyList<StartupTask>(),
            atStart.filter { StartupWorkPolicy.phase(it) != StartupPhase.BACKGROUND },
        )
    }

    @Test
    fun `network and heavy-graph starters wait for the first visible activity`() {
        val firstUi = StartupWorkPolicy.tasksFor(StartupTrigger.FIRST_ACTIVITY)
        listOf(
            StartupTask.STARTUP_SYNC, StartupTask.REALTIME_SYNC, StartupTask.DEVICE_CLAIM,
            StartupTask.XTREAM_MATCH_WARMUP, StartupTask.REC_EVENT_LOGGER, StartupTask.M3U_REKEY,
            StartupTask.SERVER_USER_STATE, StartupTask.TV_CHANNEL_SYNC, StartupTask.CALENDAR_HOME_ROW,
        ).forEach { assertTrue("$it must wait for a UI start", it in firstUi) }
    }

    @Test
    fun `work headless components need still runs in every process`() {
        val atStart = StartupWorkPolicy.tasksFor(StartupTrigger.PROCESS_START)
        listOf(
            StartupTask.IPTV_PANEL_HOSTS, StartupTask.LIVE_ENGINE_MEMORY,
            StartupTask.PLAYLIST_DNS, StartupTask.IPTV_REFRESH_SCHEDULER,
        ).forEach { assertTrue("$it must run at process start", it in atStart) }
    }

    @Test
    fun `every task runs under exactly one trigger`() {
        val atStart = StartupWorkPolicy.tasksFor(StartupTrigger.PROCESS_START)
        val firstUi = StartupWorkPolicy.tasksFor(StartupTrigger.FIRST_ACTIVITY)
        assertEquals("all tasks covered", StartupTask.entries.toSet(), (atStart + firstUi).toSet())
        assertEquals("no task runs twice", emptySet<StartupTask>(), atStart.toSet() intersect firstUi.toSet())
    }

    @Test
    fun `first-UI work runs exactly once per process`() {
        val gate = FirstUiGate()
        assertTrue("first activity claims", gate.claim())
        assertFalse("second activity does not", gate.claim())
        assertFalse("nor any later one", gate.claim())
    }
}
