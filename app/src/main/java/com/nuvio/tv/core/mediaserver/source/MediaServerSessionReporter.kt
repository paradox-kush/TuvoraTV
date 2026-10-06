package com.nuvio.tv.core.mediaserver.source

import com.nuvio.tv.core.mediaserver.MsLog as Logger
import com.nuvio.tv.core.contracts.PlaybackPlayMethod
import com.nuvio.tv.core.contracts.PlaybackSessionReporter
import com.nuvio.tv.core.contracts.PlaybackSessionState
import com.nuvio.tv.core.mediaserver.client.MediaServerException
import com.nuvio.tv.core.mediaserver.client.MediaServerServices
import com.nuvio.tv.core.mediaserver.client.PlaybackReport
import com.nuvio.tv.core.mediaserver.client.PlaybackReportKind
import com.nuvio.tv.core.mediaserver.policy.MediaServerIds
import com.nuvio.tv.core.mediaserver.policy.PlaybackDecisionPolicy
import com.nuvio.tv.core.mediaserver.policy.ProgressReportPolicy
import com.nuvio.tv.core.mediaserver.store.MediaServerEntryStore
import kotlinx.coroutines.CancellationException

/**
 * Keeps the server's watched/resume state in step with what plays in Tuvora (design 5.7, D3): start, progress
 * (pause, resume, seek), stop - so other apps on the same account stay current. The PLAYER only announces; the
 * cadence (10 s while playing, a pause report then a check-in every <= 4 min, always a stop) is
 * [ProgressReportPolicy]'s, the true play method is the one the mint step recorded
 * ([MediaServerPlaybackSessions]), and the explicit "mark played" fires only when Tuvora considers the item
 * finished but the stop report did not cross the server's own threshold (no double-scrobble).
 */
internal class MediaServerSessionReporter(
    private val store: MediaServerEntryStore,
    private val services: MediaServerServices,
    private val nowMs: () -> Long,
    /** Tuvora's own completion rule (percent), so the explicit mark follows what Tuvora shows as watched. */
    private val tuvoraFinishedPercent: Float = 90f,
    /** Told after a report so the Home contributor can refetch (the server's rows changed). */
    private val onReported: (sourceKey: String) -> Unit = {},
) : PlaybackSessionReporter {
    override val name: String = "mediaserver"
    private val log = Logger.withTag("MediaServerReporter")
    private val lock = Any()
    private val states = mutableMapOf<String, ProgressReportPolicy.State>()

    override fun handles(videoId: String, providerAddonId: String?): Boolean =
        MediaServerIds.isContentId(videoId) || (providerAddonId != null && MediaServerIds.isOwnProviderId(providerAddonId))

    override suspend fun onStart(session: PlaybackSessionState) {
        val target = target(session) ?: return
        // a NEW session of a video that was stopped before starts from a clean state (the stopped tombstone is only for stragglers)
        val step = advance(session) { ProgressReportPolicy.start(if (it.stopped) ProgressReportPolicy.State() else it, nowMs(), session.positionMs) }
        step.report?.let { send(target, it, session) }
    }

    override suspend fun onProgress(session: PlaybackSessionState, paused: Boolean) {
        val target = target(session) ?: return
        val step = advance(session) { ProgressReportPolicy.progress(it, nowMs(), session.positionMs, paused) }
        step.report?.let { send(target, it, session) }
    }

    override suspend fun onStop(session: PlaybackSessionState) {
        val target = target(session) ?: return
        val step = advance(session) { ProgressReportPolicy.stop(it, nowMs(), session.positionMs) }
        step.report?.let { send(target, it, session) }
        if (step.report != null) {
            val finished = session.durationMs > 0 && session.positionMs * 100f / session.durationMs >= tuvoraFinishedPercent
            if (ProgressReportPolicy.shouldMarkPlayedExplicitly(finished, session.positionMs, session.durationMs)) markPlayed(target)
        }
    }

    private class Target(val serverKey: String, val itemId: String, val mediaSourceId: String?, val playSessionId: String?, val playMethod: PlaybackPlayMethod)

    /** The server item a playing video id (or a matched-lane play) refers to, with what the mint step negotiated for it. */
    private fun target(session: PlaybackSessionState): Target? {
        val parsed = MediaServerIds.parse(session.videoId)
        val recorded = if (parsed != null) MediaServerPlaybackSessions.forItem(parsed.serverKey, parsed.itemId)
        else session.providerAddonId?.let(MediaServerIds::serverKeyOfMatchGroup)?.let(MediaServerPlaybackSessions::latestFor)
        if (recorded != null) return Target(recorded.serverKey, recorded.itemId, recorded.mediaSourceId, recorded.playSessionId, recorded.playMethod)
        // no mint record (an external player, a restored session): report against the id itself, play method unknown -> direct play
        return parsed?.let { Target(it.serverKey, it.itemId, null, null, PlaybackPlayMethod.DIRECT_PLAY) }
    }

    private fun advance(session: PlaybackSessionState, step: (ProgressReportPolicy.State) -> ProgressReportPolicy.Step): ProgressReportPolicy.Step =
        synchronized(lock) {
            val key = session.stateKey()
            val result = step(states[key] ?: ProgressReportPolicy.State())
            states.remove(key)
            states[key] = result.state // most recent last: the map is a tiny LRU of sessions, stopped ones kept as tombstones
            while (states.size > MAX_TRACKED_SESSIONS) states.remove(states.keys.first())
            result
        }

    private suspend fun send(target: Target, report: ProgressReportPolicy.Report, session: PlaybackSessionState) {
        val entry = store.entryByServerKey(target.serverKey) ?: return
        val client = services.clientFor(entry) ?: return
        val kind = when (report.kind) {
            ProgressReportPolicy.Kind.START -> PlaybackReportKind.START
            ProgressReportPolicy.Kind.PROGRESS -> PlaybackReportKind.PROGRESS
            ProgressReportPolicy.Kind.STOP -> PlaybackReportKind.STOPPED
        }
        val method = session.playMethod ?: target.playMethod // the player's value when it knows one, else what the mint chose
        try {
            client.report(
                PlaybackReport(
                    kind = kind,
                    itemId = target.itemId,
                    mediaSourceId = target.mediaSourceId,
                    positionTicks = PlaybackDecisionPolicy.msToTicks(report.positionMs),
                    isPaused = report.paused,
                    playMethod = PlaybackDecisionPolicy.wireMethod(method),
                    playSessionId = target.playSessionId,
                ),
            )
            if (kind == PlaybackReportKind.STOPPED) {
                MediaServerIds.parse(session.videoId)?.let { onReported(it.sourceKey) }
                // a server-built (transcoded / remuxed) play leaves an ffmpeg job behind: end it with the session
                if (method != PlaybackPlayMethod.DIRECT_PLAY) target.playSessionId?.let { runCatching { client.stopEncoding(it) } }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: MediaServerException.Http) {
            if (e.isUnauthorized) services.onUnauthorized(entry.serverKey)
            log.w { "report failed: HTTP ${e.status}" }
        } catch (e: MediaServerException) {
            log.w { "report failed: ${e::class.simpleName}" }
        }
    }

    private suspend fun markPlayed(target: Target) {
        val entry = store.entryByServerKey(target.serverKey) ?: return
        val client = services.clientFor(entry) ?: return
        try {
            client.setPlayed(target.itemId, true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: MediaServerException) {
            log.w { "mark played failed: ${e::class.simpleName}" }
        }
    }

    private companion object {
        const val MAX_TRACKED_SESSIONS = 32
    }

    private fun PlaybackSessionState.stateKey() = "$videoId|$providerAddonId"
}
