package com.nuvio.tv.ui.screens.player

import android.util.Log
import com.nuvio.tv.core.contracts.PlaybackResumeOfferRegistry
import com.nuvio.tv.core.contracts.PlaybackSessionReporterRegistry
import com.nuvio.tv.core.contracts.PlaybackSessionState
import com.nuvio.tv.core.diagnostics.LogRedaction
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Tells the source that owns the playing item how the playback goes (a media server keeping its own resume /
 * watched state in step - design 5.7, D3). The PLAYER only announces start / progress(paused) / stop; the cadence
 * (~15 s playing, ~60 s paused, a stop always) and the double-scrobble rules are the reporter's own pure policy.
 * Fed from the same call sites as the Trakt/Simkl scrobble pipeline but independent of it: an own source's items
 * have no TMDB identity, so [emitScrobbleStart] & co. bail out early for them.
 *
 * Nothing here touches a source that does not own the item: [reporting] is a cheap synchronous check first.
 */
internal fun PlayerRuntimeController.reportedVideoId(): String? =
    currentVideoId?.takeIf { it.isNotBlank() } ?: contentId?.takeIf { it.isNotBlank() }

internal fun PlayerRuntimeController.reporting(): Boolean {
    val id = reportedVideoId() ?: return false
    return !PlaybackSessionReporterRegistry.isEmpty && PlaybackSessionReporterRegistry.handlersFor(id, null).isNotEmpty()
}

private fun PlayerRuntimeController.sessionState(): PlaybackSessionState? {
    val videoId = reportedVideoId() ?: return null
    val position = currentPlaybackPositionMs() ?: 0L
    return PlaybackSessionState(
        videoId = videoId,
        parentMetaId = contentId ?: videoId,
        providerAddonId = null,
        positionMs = position,
        durationMs = getEffectiveDuration(position),
    )
}

internal fun PlayerRuntimeController.reportSessionStart() {
    if (!reporting()) return
    val session = sessionState() ?: return
    scope.launch { runCatching { PlaybackSessionReporterRegistry.start(session) }.onFailure { logReportFailure(it) } }
    startSessionReportTick()
    // the first frame is up: if the server's position differed from Tuvora's own record, say so now
    showPendingServerResumeOffer()
}

/**
 * The clock of a source's playback reports: a paused player emits no events and the watch-progress save is slower than
 * the references' ~15 s, so without a tick of its own the server would hear nothing while paused and the session would
 * lapse. The tick is local and cheap - the reporter's pure policy (playing every ~15 s, paused every ~60 s) decides
 * whether anything is sent - and it lives only while a reported session is open and the app is in the foreground
 * (one loop per player: starting again replaces it; [reportSessionStop] and the release end it).
 */
private fun PlayerRuntimeController.startSessionReportTick() {
    sessionReportJob?.cancel()
    sessionReportJob = scope.launch {
        while (true) {
            delay(SESSION_REPORT_TICK_MS)
            if (isReleasingPlayer || isInBackground) continue
            reportSessionProgress(paused = !isPlaybackCurrentlyPlaying())
        }
    }
}

private const val SESSION_REPORT_TICK_MS = 5_000L

/** [paused] = a pause edge (or the periodic check-in while paused); false = a position update while playing. */
internal fun PlayerRuntimeController.reportSessionProgress(paused: Boolean) {
    if (!reporting() || !hasRenderedFirstFrame) return
    val session = sessionState() ?: return
    scope.launch { runCatching { PlaybackSessionReporterRegistry.progress(session, paused) }.onFailure { logReportFailure(it) } }
}

/** Exactly once per session (the reporter's tombstone absorbs a repeated call: exit and release both flush). */
internal fun PlayerRuntimeController.reportSessionStop() {
    sessionReportJob?.cancel()
    sessionReportJob = null
    if (!reporting()) return
    val session = sessionState() ?: return
    // The stop must survive the screen going away (the controller's scope may be cancelled right behind it).
    scope.launch(NonCancellable) { runCatching { PlaybackSessionReporterRegistry.stop(session) }.onFailure { logReportFailure(it) } }
}

private fun logReportFailure(error: Throwable) {
    if (error is kotlinx.coroutines.CancellationException) throw error
    Log.w("SessionReports", "playback report failed: ${LogRedaction.text(error.message)}")
}

/**
 * Resume from the SERVER (D3, owner decisions 2026-10-06): Tuvora resumes from its own record as for every source.
 *  - no local record and the server has a position -> continue from the server's, silently (the source says so: autoStart);
 *  - a local record and the server's is NEWER and differs -> offer "Continue at hh:mm?" (never applied silently).
 * Pure decision: [ServerResumePolicy] over the source's offer. One request at most per play (the source answers from
 * the item it fetches).
 */
internal suspend fun PlayerRuntimeController.askSourceForResume(localPositionMs: Long?, localUpdatedAtMs: Long?): ServerResumePolicy.Decision {
    val videoId = reportedVideoId() ?: return ServerResumePolicy.Decision.None
    if (PlaybackResumeOfferRegistry.isEmpty || !PlaybackResumeOfferRegistry.handles(videoId, null)) return ServerResumePolicy.Decision.None
    val offer = runCatching {
        PlaybackResumeOfferRegistry.offerFor(videoId, null, localPositionMs, localUpdatedAtMs, currentPlaybackDurationMs().takeIf { it > 0 })
    }.getOrNull()
    return ServerResumePolicy.decide(offer)
}

/** The id of the playing item when an own-source lane (a media server) owns it, else null: what the link-refresh recovery keys on. */
internal fun PlayerRuntimeController.ownSourceVideoId(): String? =
    reportedVideoId()?.takeIf { com.nuvio.tv.core.contracts.StreamSourceAccess.current().isHandledId(it) }
