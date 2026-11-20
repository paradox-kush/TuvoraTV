package com.nuvio.tv.playback.media3

import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.nuvio.tv.playback.core.ExternalSubtitleResolver
import com.nuvio.tv.playback.core.PlaybackResult
import io.mockk.mockk
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B271: leaving multiview released every tile's ExoPlayer on the main thread. ExoPlayer.release blocks the
 * calling (application) thread until the playback thread acknowledges, up to the release timeout, so three tiles
 * on a slow Amlogic decoder froze the UI for ~3.3 s. Tile players now live on their own application thread.
 */
class TilePlayerReleaseOffMainTest {
    private val mainThread = Executors.newSingleThreadExecutor { Thread(it, "fake-main") }
    private val main: CoroutineDispatcher = mainThread.asCoroutineDispatcher()
    private val started = mutableListOf<FakePlayerThread>()

    @After
    fun tearDown() {
        started.forEach { it.quit() }
        mainThread.shutdownNow()
    }

    private inner class FakePlayerThread(name: String) : PlayerThread {
        private val executor = Executors.newSingleThreadExecutor { Thread(it, name) }
        override val looper: Looper = mockk(relaxed = true)
        override val dispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()
        val quitCalls = AtomicInteger()
        override fun quit() {
            quitCalls.incrementAndGet()
            executor.shutdown()
        }
    }

    private val threads = PlayerThreadFactory { name -> FakePlayerThread(name).also { started += it } }

    private fun tileConfig() = com.nuvio.tv.playback.core.TileHostConfig(
        bufferBytes = 1_000_000,
    )

    private fun backendOn(execution: PlayerExecution, releaseMs: Long): Pair<Any, Media3Backend> {
        val controller = object : Media3ReleaseController {
            override fun releaseWithProof(player: ExoPlayer): Boolean {
                Thread.sleep(releaseMs) // ExoPlayer.release blocks its calling thread like this
                return true
            }
            override fun awaitReleaseWithProof(player: ExoPlayer): Boolean = true
        }
        val backend = AndroidMedia3Backend(
            player = mockk<ExoPlayer>(relaxed = true),
            mediaItem = mockk<MediaItem>(relaxed = true),
            calls = TrackingCallRegistry(),
            byteProgress = Media3ByteProgressSignal(),
            dispatcher = execution.dispatcher,
            releaseController = controller,
            plan = mockk(relaxed = true),
            externalSubtitleResolver = ExternalSubtitleResolver { null },
            onTerminated = execution::finish,
        )
        return Unit to backend
    }

    @Test
    fun `a tile player gets its own thread and a fullscreen player keeps the main dispatcher`() {
        val tile = PlayerThreading.select(tileConfig(), main, threads)
        val fullscreen = PlayerThreading.select(null, main, threads)

        assertNotSame("tile must not run on main", main, tile.dispatcher)
        assertTrue("tile execution owns a looper", tile.thread != null)
        assertSame("fullscreen is unchanged", main, fullscreen.dispatcher)
        assertEquals("only the tile started a thread", 1, started.size)
    }

    @Test
    fun `releasing three tiles never blocks the main thread and runs in parallel`() {
        val releaseMs = 400L
        val executions = List(3) { PlayerThreading.select(tileConfig(), main, threads) }
        val backends = executions.map { backendOn(it, releaseMs).second }
        val scope = CoroutineScope(SupervisorJob() + main)

        val mainRan = CountDownLatch(1)
        val begin = System.nanoTime()
        val all = scope.async { backends.map { async { it.release() } }.awaitAll() }
        // Main must stay serviceable while the players shut down.
        Thread.sleep(50)
        var mainDelayMs = -1L
        val probeStart = System.nanoTime()
        mainThread.execute { mainDelayMs = (System.nanoTime() - probeStart) / 1_000_000; mainRan.countDown() }
        assertTrue("main thread was blocked by the releases", mainRan.await(2, TimeUnit.SECONDS))
        assertTrue("main thread stalled ${mainDelayMs}ms behind the releases", mainDelayMs < 150)

        val results = runBlocking { withTimeout(5_000) { all.await() } }
        val totalMs = (System.nanoTime() - begin) / 1_000_000
        assertTrue("every tile release must succeed", results.all { it is PlaybackResult.Success })
        assertTrue("three 400ms releases must overlap, took ${totalMs}ms", totalMs < 900)
        assertEquals("every tile thread is quit once its player is released", listOf(1, 1, 1), started.map { it.quitCalls.get() })
        assertFalse(started.isEmpty())
    }
}
