package com.nuvio.tv.core.player

import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide count of active internal players. The IPTV auto-refresh worker consults this at run
 * time and reschedules (retry) rather than re-ingesting a playlist while a stream is playing —
 * re-ingest does an atomic catalog swap that shouldn't run mid-playback.
 *
 * A player increments on start and decrements when released; the count is best-effort (a killed
 * process resets it to 0, which is correct — nothing is playing after a restart).
 */
@Singleton
class PlaybackActivityTracker @Inject constructor() {
    private val activePlayers = AtomicInteger(0)

    fun onPlayerStarted() { activePlayers.incrementAndGet() }

    fun onPlayerStopped() { if (activePlayers.get() > 0) activePlayers.decrementAndGet() }

    /** True while any internal player is active. */
    fun isPlaybackActive(): Boolean = activePlayers.get() > 0
}
