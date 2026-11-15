package com.nuvio.tv.core.diagnostics

import android.app.Application
import com.nuvio.tv.data.local.SentrySettingsDataStore
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.flow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * SentryInitializer.start runs inside Application.onCreate. It used to runBlocking a DataStore read
 * there, even in fork builds where Sentry is inert (no DSN).
 */
class SentryInitializerTest {

    @Before
    fun reset() = SentryInitializer.resetForTest()

    @After
    fun tearDown() = SentryInitializer.resetForTest()

    @Test
    fun `blank DSN performs no settings read`() {
        var reads = 0
        val settings = mockk<SentrySettingsDataStore> {
            every { enabled } returns flow { reads++; emit(true) }
        }

        SentryInitializer.start(mockk<Application>(relaxed = true), settings, dsn = "  ")

        assertEquals("settings reads with a blank DSN", 0, reads)
    }

    @Test
    fun `with a DSN the setting is read off the calling thread`() {
        val read = CountDownLatch(1)
        var readThread: Thread? = null
        val settings = mockk<SentrySettingsDataStore> {
            // false: applyEnabled only checks Sentry.isEnabled(), no Android init.
            every { enabled } returns flow { readThread = Thread.currentThread(); read.countDown(); emit(false) }
        }

        SentryInitializer.start(mockk<Application>(relaxed = true), settings, dsn = "https://key@example.invalid/1")

        assertTrue("the setting is still read, on the initializer's scope", read.await(5, TimeUnit.SECONDS))
        assertNotEquals("the setting must not be read on the calling thread", Thread.currentThread(), readThread)
    }
}
