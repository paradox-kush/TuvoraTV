package com.nuvio.tv.core.analytics

import com.posthog.PostHog

/**
 * Screen and playback breadcrumbs.
 *
 * Crash telemetry kept answering "what died" but never "what was the user doing": autocaptured
 * `$screen` only ever reports the host Activity name, and `app_exit` is reported one launch
 * later so it has no live session to correlate with. Every breadcrumb therefore goes two ways
 * at once — a live analytics event for same-session correlation (`$exception`,
 * `live_playback_freeze`), and a persisted note through [CrashWriter] that [AppExitReporter]
 * attaches to the next-launch `app_exit` event.
 *
 * Only code identities travel here — route patterns, engine ids, container extensions — never
 * content titles, URLs, or anything else about *what* is being watched.
 */
object Breadcrumbs {

    /** Persists breadcrumbs so a process-death reporter can read them on the next launch. */
    interface CrashWriter {
        fun onScreen(name: String)
        fun onPlaybackStarted(kind: String, engine: String, surface: String)
        fun onPlaybackStopped()
    }

    /** Registered by the application host; null (a test) just skips persistence. */
    var crashWriter: CrashWriter? = null

    private var capture: (String, Map<String, Any>) -> Unit = { event, properties ->
        PostHog.capture(event, properties = properties)
    }
    private var lastScreen: String? = null
    private var lastPlaybackKey: String? = null
    private var lastPlaybackEmitAtMs = 0L
    private val recentEmitTimestampsMs = ArrayDeque<Long>()

    fun screenChanged(name: String) {
        if (name.isBlank() || name == lastScreen) return
        lastScreen = name
        crashWriter?.onScreen(name)
        capture(SCREEN_EVENT, mapOf(SCREEN_NAME_PROPERTY to name))
    }

    /**
     * Call when playback has actually produced output (first frame), not when a source is
     * merely resolving — startup failures are a different signal. The guide preview is the
     * one deliberate exception; see its call site.
     */
    fun playbackStarted(kind: String, engine: String, surface: String, container: String, nowMs: Long) {
        // The persisted note always updates: it must reflect reality at the moment of death
        // even when the analytics event below is deduped or rate-capped.
        crashWriter?.onPlaybackStarted(kind, engine, surface)

        // Reconnect ladders and channel zapping re-fire starts; identical back-to-back starts
        // inside the window are one viewing decision, not many.
        val key = "$kind|$engine|$surface|$container"
        if (key == lastPlaybackKey && nowMs - lastPlaybackEmitAtMs < DEDUPE_WINDOW_MS) return
        if (!allowEmit(nowMs)) return
        lastPlaybackKey = key
        lastPlaybackEmitAtMs = nowMs
        capture(
            PLAYBACK_EVENT,
            mapOf(
                "kind" to kind,
                "engine" to engine,
                "surface" to surface,
                "stream_container" to container,
            ),
        )
    }

    fun playbackStopped() {
        crashWriter?.onPlaybackStopped()
    }

    private fun allowEmit(nowMs: Long): Boolean {
        while (recentEmitTimestampsMs.isNotEmpty() && nowMs - recentEmitTimestampsMs.first() > RATE_WINDOW_MS) {
            recentEmitTimestampsMs.removeFirst()
        }
        if (recentEmitTimestampsMs.size >= MAX_PLAYBACK_EVENTS_PER_HOUR) return false
        recentEmitTimestampsMs.addLast(nowMs)
        return true
    }

    /** Tests swap the capture sink and clear the dedupe/rate state between cases. */
    internal fun resetForTest(
        capture: (String, Map<String, Any>) -> Unit = { event, properties ->
            PostHog.capture(event, properties = properties)
        },
    ) {
        this.capture = capture
        crashWriter = null
        lastScreen = null
        lastPlaybackKey = null
        lastPlaybackEmitAtMs = 0L
        recentEmitTimestampsMs.clear()
    }

    const val SCREEN_EVENT = "\$screen"
    const val SCREEN_NAME_PROPERTY = "\$screen_name"
    const val PLAYBACK_EVENT = "playback_started"

    /** A zapper flipping channels all evening is bounded; the first hour tells the story. */
    const val MAX_PLAYBACK_EVENTS_PER_HOUR = 40
    private const val RATE_WINDOW_MS = 60L * 60L * 1000L
    private const val DEDUPE_WINDOW_MS = 15_000L
}
