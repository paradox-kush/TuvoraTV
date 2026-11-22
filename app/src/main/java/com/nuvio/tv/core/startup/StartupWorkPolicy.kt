package com.nuvio.tv.core.startup

import java.util.concurrent.atomic.AtomicBoolean

/** When a piece of process-start work runs. */
enum class StartupPhase {
    /** Every process start (UI or headless), off the main thread. */
    BACKGROUND,

    /** Once, when the first Activity of the process is being created (a UI start), before its onCreate. */
    FIRST_UI,
}

/** What triggered a startup step. */
enum class StartupTrigger { PROCESS_START, FIRST_ACTIVITY }

/**
 * The starters `NuvioApplication` used to inject eagerly and kick off on the main thread in every process
 * start. Field injection built their whole graph (Supabase/Ktor client, Room, sync services) inside
 * `super.onCreate()`, and that is where the headless-start "Timed out while trying to bind" ANRs sat
 * (PostHog app_exit traces, 14 d to 2026-11-21: ~115 of ~130 traces that reached app code were inside
 * Hilt injection under `NuvioApplication.onCreate`; none in the consent read).
 */
enum class StartupTask(val phase: StartupPhase) {
    /** IPTV panel hosts mirror: playback and recordings trust checks need it in any process. */
    IPTV_PANEL_HOSTS(StartupPhase.BACKGROUND),
    /** Live engine memory loaded from disk before the first live tune (recordings can tune headless). */
    LIVE_ENGINE_MEMORY(StartupPhase.BACKGROUND),
    /** Playlist DoH provider mirror warm before the first playback. */
    PLAYLIST_DNS(StartupPhase.BACKGROUND),
    /** Keeps the IPTV refresh worker scheduled to the shortest enabled interval. */
    IPTV_REFRESH_SCHEDULER(StartupPhase.BACKGROUND),
    /** Mirrors the Simkl anime-id preference into a static that Simkl code reads in any process. */
    SIMKL_ANIME_ID_PREFERENCE(StartupPhase.BACKGROUND),

    /** Startup sync pull on sign-in (network + egress): only worth it when someone is looking. */
    STARTUP_SYNC(StartupPhase.FIRST_UI),
    /** Realtime invalidation socket. */
    REALTIME_SYNC(StartupPhase.FIRST_UI),
    /** B205 claim of a redeemed setup code. */
    DEVICE_CLAIM(StartupPhase.FIRST_UI),
    /** Recommendation telemetry restore + flush timer (pending events are on disk, never lost). */
    REC_EVENT_LOGGER(StartupPhase.FIRST_UI),
    /** Launcher channel reconciliation (its JobService injects what it needs on its own). */
    TV_CHANNEL_SYNC(StartupPhase.FIRST_UI),
    /** B64 M3U id migration (re-checks every playlist per profile when it starts). */
    M3U_REKEY(StartupPhase.FIRST_UI),
    /** Xtream match-index warm-up (full catalog downloads). */
    XTREAM_MATCH_WARMUP(StartupPhase.FIRST_UI),
    /** Media-server watch state projection. */
    SERVER_USER_STATE(StartupPhase.FIRST_UI),
    /** Calendar's optional Home row: registered before Home first collects. */
    CALENDAR_HOME_ROW(StartupPhase.FIRST_UI),
}

/**
 * Decides, as a table, which startup work runs in every process and which waits for a UI start. Pure.
 * Nothing here runs on the main thread at process start; that is the point.
 */
object StartupWorkPolicy {

    fun phase(task: StartupTask): StartupPhase = task.phase

    /** The tasks a trigger runs, in declaration order. */
    fun tasksFor(trigger: StartupTrigger): List<StartupTask> {
        val wanted = when (trigger) {
            StartupTrigger.PROCESS_START -> StartupPhase.BACKGROUND
            StartupTrigger.FIRST_ACTIVITY -> StartupPhase.FIRST_UI
        }
        return StartupTask.entries.filter { it.phase == wanted }
    }
}

/** Lets the FIRST_UI work run once per process, on the first Activity, however many Activities follow. */
class FirstUiGate {
    private val claimed = AtomicBoolean(false)

    /** True exactly once: for the first Activity created in this process. */
    fun claim(): Boolean = claimed.compareAndSet(false, true)
}
