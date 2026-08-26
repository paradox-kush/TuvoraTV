package com.nuvio.tv.player.mpv

import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * The single mpv control thread ("mpv-ctl"), extracted from NuvioMpvSurfaceView into the fork-owned
 * engine package (research/tv-player-mpv-engine-ownership.md, A4b). Every mpv write (property set,
 * command, seek, teardown) runs here, serialized in submission order: `mpv_set_property`/`mpv_command`
 * take the mpv core lock, and on a wedged live demuxer a write on the main thread blocks >5s → ANR.
 * See [[nuvio-mpv-anr-fix]].
 *
 * The lifecycle predicates are supplied by the surface view (which owns `initialized`/`nativeCoreAlive`/
 * the instance id) so a stale write can never touch a core that has been re-created underneath it —
 * the double-check (before submit + inside the runnable) is the core-swap guard.
 */
internal class MpvControlQueue(
    private val currentInstanceId: () -> Long,
    private val isInitialized: () -> Boolean,
    private val isCoreAlive: () -> Boolean,
) {
    private val exec = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "mpv-ctl")
    }

    /**
     * Queue a guarded control write. No-ops if the core is not live now, and again (on the ctl thread)
     * if the core was torn down / re-created between submit and execution.
     */
    fun submit(block: () -> Unit) {
        val instanceId = currentInstanceId()
        if (!isInitialized() || !isCoreAlive() || instanceId <= 0L) {
            // Loud drop: a silently discarded control write here has already cost a field bug
            // (guide zaps that never loaded — the picture stayed on the previous channel with no
            // trace). Every gate decision must be visible in logcat.
            android.util.Log.w(
                TAG,
                "drop@submit init=${isInitialized()} coreAlive=${isCoreAlive()} instance=$instanceId"
            )
            return
        }
        runCatching {
            exec.execute {
                if (!isCoreAlive() || currentInstanceId() != instanceId) {
                    android.util.Log.w(
                        TAG,
                        "drop@execute coreAlive=${isCoreAlive()} " +
                            "instance=${currentInstanceId()} (submitted=$instanceId)"
                    )
                    return@execute
                }
                runCatching(block).onFailure {
                    android.util.Log.w(TAG, "control write threw: ${it.message}")
                }
            }
        }.onFailure {
            android.util.Log.w(TAG, "executor rejected control write: ${it.message}")
        }
    }

    private companion object {
        private const val TAG = "MpvControlQueue"
    }

    /**
     * Queue the ordered teardown (unguarded — it runs the destroy itself, after any queued writes).
     * Returns the [Future] so the caller can await completion before re-initializing on the same
     * instance.
     */
    fun submitTeardown(block: () -> Unit): Future<*> = exec.submit(block)
}
