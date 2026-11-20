package com.nuvio.tv.playback.media3

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import com.nuvio.tv.playback.core.TileHostConfig
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable

/**
 * An application thread for one ExoPlayer ([ExoPlayer.Builder.setLooper]: "the Looper that must be used for all calls
 * to the player and that is used to call listeners on").
 *
 * Why tiles have one (B271): `ExoPlayer.release()` blocks its calling thread until the playback thread acknowledges,
 * up to the release timeout, and slow TV decoders (Amlogic) sit at that timeout. On the main thread, leaving a
 * three-tile multiview was three back-to-back blocks (~3.3 s of dropped frames). On a thread of its own the block
 * costs only that thread, and the tiles release in parallel.
 */
internal interface PlayerThread {
    val looper: Looper
    val dispatcher: CoroutineDispatcher

    /** Stops the thread once its queued work has run. Only after the player is proven released. */
    fun quit()
}

internal fun interface PlayerThreadFactory {
    fun start(name: String): PlayerThread
}

/** Where one player's calls run: the dispatcher every backend call hops to, and the thread to end afterwards. */
internal class PlayerExecution(val dispatcher: CoroutineDispatcher, val thread: PlayerThread?) {
    /** Called once the backend is terminal (released or hard-aborted); a no-op for the shared main dispatcher. */
    fun finish() {
        thread?.quit()
    }
}

internal object PlayerThreading {
    /**
     * A multiview tile gets its own application thread; the single fullscreen / guide player keeps [main]
     * exactly as before (Media3 UI components and the surface views assume it).
     */
    fun select(tile: TileHostConfig?, main: CoroutineDispatcher, threads: PlayerThreadFactory): PlayerExecution =
        if (tile == null) {
            PlayerExecution(main, null)
        } else {
            val thread = threads.start("tuvora-tile-player")
            PlayerExecution(thread.dispatcher, thread)
        }
}

internal object HandlerPlayerThreadFactory : PlayerThreadFactory {
    override fun start(name: String): PlayerThread {
        val thread = HandlerThread(name).apply { start() }
        return HandlerPlayerThread(thread)
    }
}

private class HandlerPlayerThread(private val thread: HandlerThread) : PlayerThread {
    override val looper: Looper = thread.looper
    private val handler = Handler(looper)
    override val dispatcher: CoroutineDispatcher = object : CoroutineDispatcher() {
        override fun isDispatchNeeded(context: CoroutineContext): Boolean = Looper.myLooper() !== looper

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            // After quit() the handler rejects work. Only post-release bookkeeping can arrive then (every
            // player call checks `released` first), so it runs on Default rather than being dropped.
            if (!handler.post(block)) Dispatchers.Default.dispatch(context, block)
        }

        override fun toString(): String = "PlayerThread(${thread.name})"
    }

    override fun quit() {
        thread.quitSafely()
    }
}
