package com.nuvio.tv.ui.screens.player

import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Sync 17 follow-up (ANR rule): the styled-subtitle sync reads libmpv on every subtitle change.
 * The read runs on the mpv lane; the result is shown back on the caller's (Main) thread.
 */
class MpvStyledSubtitleSyncTest {

    @Test
    fun theStyledSubtitleReadNeverRunsOnMainAndTheResultIsShownOnMain() = runBlocking {
        val main = Executors.newSingleThreadExecutor { Thread(it, "fake-main") }.asCoroutineDispatcher()
        try {
            val mainThread = main.executor.let { exec ->
                java.util.concurrent.CompletableFuture.supplyAsync({ Thread.currentThread() }, exec).get()
            }
            var readThread: Thread? = null
            var showThread: Thread? = null
            var shown: String? = null
            MpvStyledSubtitleSync.launch(
                scope = CoroutineScope(main),
                read = { readThread = Thread.currentThread(); "hello" },
                isCurrent = { true },
                show = { showThread = Thread.currentThread(); shown = it },
            ).join()
            assertNotSame("the mpv subtitle read ran on Main", mainThread, readThread)
            assertSame("the subtitle text must be shown on Main", mainThread, showThread)
            assertEquals("hello", shown)
        } finally {
            main.close()
        }
    }

    @Test
    fun aResultForAReleasedPlayerIsDropped() = runBlocking {
        var shown: String? = null
        MpvStyledSubtitleSync.launch(
            scope = this,
            read = { "stale" },
            isCurrent = { false },
            show = { shown = it },
        ).join()
        assertNull("text read for an old mpv view must not reach the screen", shown)
    }
}
