package com.nuvio.tv.core.startup

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * One piece of startup I/O started on its own thread as early as possible in `Application.onCreate`, so it
 * overlaps Hilt injection instead of running after it on the main thread. The main thread later takes the
 * value with a deadline ([await]); a value that is not ready in time is reported as null and the caller
 * takes its documented fallback. The thread never touches Main and is a daemon: if the work hangs it is
 * abandoned, never joined.
 */
class StartupPrefetch<T : Any>(name: String, private val work: () -> T) {
    private val done = CountDownLatch(1)

    @Volatile private var value: T? = null

    init {
        Thread({
            try {
                value = runCatching(work).getOrNull()
            } finally {
                done.countDown()
            }
        }, name).apply { isDaemon = true }.start()
    }

    /** The value, or null when it failed or did not arrive within [timeoutMs]. */
    fun await(timeoutMs: Long): T? =
        if (runCatching { done.await(timeoutMs, TimeUnit.MILLISECONDS) }.getOrDefault(false)) value else null
}
