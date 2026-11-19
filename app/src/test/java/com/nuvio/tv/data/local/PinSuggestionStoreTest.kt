package com.nuvio.tv.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.nuvio.tv.core.iptv.PinSuggestionPolicy
import com.nuvio.tv.core.iptv.PlayCountLog
import com.nuvio.tv.core.profile.ProfileManager
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PinSuggestionStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val activeProfileId = MutableStateFlow(1)
    private val job: Job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.IO)
    private lateinit var subject: PinSuggestionStore
    private val now = 1_000L * PinSuggestionPolicy.DAY_MS

    @Before
    fun setUp() {
        val stores = mutableMapOf<Int, DataStore<Preferences>>()
        val factory = mockk<ProfileDataStoreFactory>()
        every { factory.get(any(), any()) } answers {
            val pid = firstArg<Int>()
            synchronized(stores) {
                stores.getOrPut(pid) {
                    PreferenceDataStoreFactory.create(scope = scope) { File(temporaryFolder.root, "pins-$pid.preferences_pb") }
                }
            }
        }
        val profileManager = mockk<ProfileManager>()
        every { profileManager.activeProfileId } returns activeProfileId
        subject = PinSuggestionStore(factory, profileManager)
    }

    @After
    fun tearDown() {
        runBlocking { job.cancelAndJoin() }
    }

    @Test
    fun `plays are recorded per profile`() = runTest {
        subject.recordPlay(1, "a", now)
        subject.recordPlay(2, "b", now)
        assertEquals(setOf("a"), subject.snapshot(1).plays.keys)
        assertEquals(setOf("b"), subject.snapshot(2).plays.keys)
    }

    @Test
    fun `a repeat inside 10 minutes counts once and a later one counts again`() = runTest {
        subject.recordPlay(1, "a", now)
        subject.recordPlay(1, "a", now + 1_000)
        subject.recordPlay(1, "a", now + PlayCountLog.DEDUPE_MS)
        assertEquals(listOf(now, now + PlayCountLog.DEDUPE_MS), subject.snapshot(1).plays.getValue("a"))
    }

    @Test
    fun `the log is capped by channels and by plays per channel`() = runTest {
        for (i in 0 until PlayCountLog.MAX_CHANNELS + 10) subject.recordPlay(1, "c$i", now + i * 1_000L)
        assertEquals(PlayCountLog.MAX_CHANNELS, subject.snapshot(1).plays.size)
        for (i in 0 until 20) subject.recordPlay(1, "busy", now + 200_000 + i * PlayCountLog.DEDUPE_MS)
        assertEquals(PlayCountLog.MAX_PLAYS_PER_CHANNEL, subject.snapshot(1).plays.getValue("busy").size)
    }

    @Test
    fun `clear play counts forgets the log and keeps the switch and snooze`() = runTest {
        subject.recordPlay(1, "a", now)
        subject.snooze(now, 1)
        subject.setEnabled(false, 1)
        subject.clearPlayCounts(1)
        val snap = subject.snapshot(1)
        assertTrue("log is empty", snap.plays.isEmpty())
        assertEquals("the switch stays off", false, snap.prefs.enabled)
        assertEquals("the snooze stays", PinSuggestionPolicy.snoozedUntil(now), snap.prefs.snoozedUntilMs)
    }

    @Test
    fun `clearing one profile leaves another's counts`() = runTest {
        subject.recordPlay(1, "a", now)
        subject.recordPlay(2, "b", now)
        subject.clearPlayCounts(1)
        assertEquals(setOf("b"), subject.snapshot(2).plays.keys)
    }

    @Test
    fun `defaults are on with no snooze, Not now snoozes and turning on again ends it`() = runTest {
        assertEquals(PinSuggestionPolicy.Prefs(enabled = true, snoozedUntilMs = 0L), subject.snapshot(1).prefs)
        subject.snooze(now, 1)
        assertEquals(PinSuggestionPolicy.snoozedUntil(now), subject.snapshot(1).prefs.snoozedUntilMs)
        subject.setEnabled(false, 1)
        subject.setEnabled(true, 1)
        assertEquals("switching it back on ends the snooze", 0L, subject.snapshot(1).prefs.snoozedUntilMs)
        assertEquals(true, subject.enabled.first())
    }

    @Test
    fun `a blank id and a bad profile record nothing`() = runTest {
        subject.recordPlay(1, " ", now)
        subject.recordPlay(0, "a", now)
        assertTrue(subject.snapshot(1).plays.isEmpty())
    }
}
