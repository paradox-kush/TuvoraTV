package com.nuvio.tv.playback.multiview

import com.nuvio.tv.core.multiview.MultiviewChannel
import com.nuvio.tv.core.multiview.MultiviewPanePlayback
import com.nuvio.tv.core.multiview.MultiviewPanePlayer
import com.nuvio.tv.core.multiview.TileFailure
import com.nuvio.tv.core.multiview.TileLoadBounds
import com.nuvio.tv.core.multiview.TileOpenGate
import com.nuvio.tv.core.multiview.TileOpenWatch
import com.nuvio.tv.core.multiview.TileOutcome
import com.nuvio.tv.core.multiview.TilePhase
import com.nuvio.tv.playback.core.PlaybackProfileId
import com.nuvio.tv.playback.core.PlaybackSnapshot
import com.nuvio.tv.playback.core.PlaybackState
import com.nuvio.tv.playback.core.ProviderSelectionId
import com.nuvio.tv.playback.core.SessionProfile
import com.nuvio.tv.playback.host.CleanLiveHost
import com.nuvio.tv.playback.live.LiveChannelSelectionPort
import com.nuvio.tv.playback.live.LiveChannelTarget
import com.nuvio.tv.playback.live.LiveInitialRequest
import com.nuvio.tv.playback.live.LiveInitialResult
import com.nuvio.tv.playback.mediasession.CleanMediaSessionMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A live channel on a tile. [key] is the stable identity the session core compares; it is never printed. */
internal data class TileChannel(
    override val key: String,
    val contentId: ProviderSelectionId,
) : MultiviewChannel {
    override fun toString(): String = "TileChannel([REDACTED])"
}

/**
 * What the coordinator needs of the audible tile to show it in the one system media session. The host
 * stays the tile's own; this is a read-only view plus the three transport commands.
 */
internal interface AudibleTileSource {
    val metadata: CleanMediaSessionMetadata
    val snapshot: StateFlow<PlaybackSnapshot>
    suspend fun pause()
    suspend fun resume()
    suspend fun stop()
}

/**
 * One multiview tile on the existing live pipeline: a tile-mode [CleanLiveHost] (no audio focus, no
 * media session, no display switching, Media3 only), driven through the same channel resolution the
 * guide preview uses ([LiveChannelSelectionPort]: Stalker re-mint, headers, user agent and single-use
 * link handling all stay in the pipeline).
 *
 * Threading: every call from the session core, and every state change this publishes, is on the main
 * thread ([scope] must run there). The host is owned by exactly one coroutine, which releases it when
 * it ends, so [release] never blocks and a release racing the open still frees what was created.
 *
 * The open ends in a picture or a typed failure: [TileOpenWatch] holds the stall deadline and the
 * one outcome report (`multiview_tile`). The only retry is the pipeline's own capped live reconnect;
 * the tile's Retry is the core recreating this player.
 */
internal class TileHostPanePlayer(
    private val scope: CoroutineScope,
    private val profileId: PlaybackProfileId,
    private val channelSelection: LiveChannelSelectionPort,
    private val createHost: suspend () -> CleanLiveHost,
    private val gate: TileOpenGate,
    private val bounds: TileLoadBounds,
    private val tileCount: () -> Int,
    private val nowMs: () -> Long,
    private val changed: () -> Unit,
    private val sessionProfile: SessionProfile = SessionProfile.GUIDE,
) : MultiviewPanePlayer<TileChannel>, AudibleTileSource {

    override var playback: MultiviewPanePlayback = MultiviewPanePlayback(isBuffering = true)
        private set

    private val mutableSnapshot = MutableStateFlow(PlaybackSnapshot())
    override val snapshot: StateFlow<PlaybackSnapshot> = mutableSnapshot
    override var metadata: CleanMediaSessionMetadata =
        CleanMediaSessionMetadata.fromIngress(redactedContentFingerprint = "", title = "Live TV")
        private set

    private val watch = TileOpenWatch(bounds.stallMs)
    private var released = false
    private var wantAudible = false
    private var wantPlaying = false
    private var sourceType = "unknown"
    private var lastSnapshot: PlaybackSnapshot? = null

    /** The last failure seen before the session gave up: a tile's hand-off dead end hides the real cause. */
    private var priorCause: com.nuvio.tv.playback.core.PlaybackFailure? = null
    private var host: CleanLiveHost? = null
    private var openJob: Job? = null
    private var timerJob: Job? = null
    private var timerAtMs: Long? = null
    private var ticket: TileOpenGate.Ticket? = null
    private var audioIssued: Pair<Long, Boolean>? = null
    private var playIssued: Pair<Long, Boolean>? = null
    private val commands = Channel<suspend (CleanLiveHost) -> Unit>(Channel.UNLIMITED)

    override fun prepare(channel: TileChannel) {
        if (released || openJob != null) return
        openJob = scope.launch { open(channel) }
    }

    override fun setAudible(audible: Boolean) {
        if (wantAudible == audible) return
        wantAudible = audible
        reconcile()
    }

    override fun setPlaying(playing: Boolean) {
        if (wantPlaying == playing) return
        wantPlaying = playing
        reconcile()
    }

    override fun release() {
        if (released) return
        released = true
        timerJob?.cancel()
        timerJob = null
        ticket?.settle()
        commands.close()
        // Cancelling ends the owning coroutine, whose `finally` releases the host (NonCancellable).
        openJob?.cancel()
    }

    override suspend fun pause() {
        host?.pause()
    }

    override suspend fun resume() {
        host?.resume()
    }

    override suspend fun stop() {
        host?.stop()
    }

    private suspend fun open(channel: TileChannel) {
        var created: CleanLiveHost? = null
        try {
            ticket = gate.awaitTurn()
            // The stall window starts when this tile is admitted, not while it queues behind another.
            observe(TilePhase.WAITING, 0, null)
            val target = when (val selected = select(channel)) {
                null -> return fail(TileFailure.Other)
                is LiveInitialResult.Target -> selected.target
                is LiveInitialResult.Rejected -> {
                    // Enum names and class names only: never a host, URL, key or message.
                    android.util.Log.w(LOG_TAG, "open rejected: ${selected.reason}")
                    return fail(TilePlaybackMapper.rejectionFailure(selected.reason))
                }
            }
            sourceType = target.selection.sourceType.name.lowercase()
            metadata = CleanMediaSessionMetadata.fromIngress(target.mediaFingerprint, target.title)
            created = try {
                createHost()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                android.util.Log.w(LOG_TAG, "host creation failed: ${e.javaClass.simpleName}")
                return fail(TileFailure.Other)
            }
            host = created
            val live = created
            scope.launch { for (command in commands) runCatching { command(live) } }
            // The first picture or failure arrives through the snapshot, so a tune that is accepted but
            // never produces anything still ends at the watch's deadline.
            scope.launch { live.snapshot.collect(::onSnapshot) }
            try {
                live.tune(target.selection, sessionProfile, metadata)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                android.util.Log.w(LOG_TAG, "tune failed: ${e.javaClass.simpleName}")
                return fail(TileFailure.Other)
            }
            // Park: the host lives as long as this tile. Cancellation (release) ends the wait.
            awaitCancellation()
        } finally {
            host = null
            timerJob?.cancel()
            ticket?.settle()
            val owned = created
            if (owned != null) withContext(NonCancellable) { runCatching { owned.release() } }
        }
    }

    /** Null when the selection itself threw: that says nothing about the playlist, so it is not "unavailable". */
    private suspend fun select(channel: TileChannel): LiveInitialResult? = try {
        channelSelection.select(LiveInitialRequest(channel.contentId, profileId))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (e: Exception) {
        android.util.Log.w(LOG_TAG, "selection failed: ${e.javaClass.simpleName}")
        null
    }

    /** A failure before any snapshot exists (selection, host creation, tune call). */
    private fun fail(failure: TileFailure) {
        if (released) return
        observe(TilePhase.FAILED, 0, failure)
        publish(MultiviewPanePlayback(failure = failure))
    }

    private fun onSnapshot(snapshot: PlaybackSnapshot) {
        if (released) return
        lastSnapshot = snapshot
        mutableSnapshot.value = snapshot
        if (snapshot.state != PlaybackState.FAILED) snapshot.failure?.let { priorCause = it }
        val phase = TilePlaybackMapper.phaseOf(snapshot)
        val failure = if (phase == TilePhase.FAILED) {
            TilePlaybackMapper.failureOf(snapshot.failure, bounds.stallMs, priorCause)
        } else {
            null
        }
        observe(phase, TilePlaybackMapper.stageOf(snapshot), failure)
        // The next tile may open as soon as this one has a frame, fails, or is gone.
        if (phase == TilePhase.PLAYING || phase == TilePhase.FAILED) ticket?.settle()
        reconcile()
        publish(TilePlaybackMapper.map(snapshot, watch.stalled, bounds.stallMs, priorCause))
    }

    private fun observe(phase: TilePhase, stage: Int, failure: TileFailure?) {
        watch.observe(phase, stage, failure, nowMs())?.let(::finished)
        armTimer()
    }

    private fun finished(outcome: TileOutcome) {
        runCatching { bounds.report(outcome, sourceType, tileCount()) }
        if (outcome is TileOutcome.TimedOut) {
            // A tile that made no progress must not keep a provider connection: stop it. Retry recreates it.
            ticket?.settle()
            commands.trySend { it.stop() }
        }
    }

    /** One timer, always for the watch's current deadline; the viewer never waits past it. */
    private fun armTimer() {
        val deadline = watch.deadlineAtMs
        if (deadline == null) {
            timerJob?.cancel()
            timerJob = null
            timerAtMs = null
            return
        }
        if (timerAtMs == deadline && timerJob?.isActive == true) return
        timerJob?.cancel()
        timerAtMs = deadline
        timerJob = scope.launch {
            delay((deadline - nowMs()).coerceAtLeast(0L))
            if (released) return@launch
            watch.expire(nowMs())?.let { outcome ->
                finished(outcome)
                publish(
                    lastSnapshot?.let { TilePlaybackMapper.map(it, stalled = true, stallMs = bounds.stallMs) }
                        ?: MultiviewPanePlayback(failure = TileFailure.NoData),
                )
            }
        }
    }

    /**
     * Applies what the core asked for once the session can honour it: pause/resume and the audio track
     * only reach the engine while a picture-carrying state is active, and an engine rebuilt by a
     * reconnect forgets the audio choice, so both are re-asserted per generation.
     */
    private fun reconcile() {
        val s = lastSnapshot ?: return
        if (released || s.state !in ACTIVE) return
        val generation = s.generation
        if (audioIssued != generation to wantAudible) {
            audioIssued = generation to wantAudible
            val on = wantAudible
            commands.trySend { it.setAudioEnabled(on) }
        }
        if (s.playWhenReady != wantPlaying && playIssued != generation to wantPlaying) {
            playIssued = generation to wantPlaying
            val play = wantPlaying
            commands.trySend { if (play) it.resume() else it.pause() }
        }
    }

    private fun publish(next: MultiviewPanePlayback) {
        if (released || next == playback) return
        playback = next
        changed()
    }

    private companion object {
        const val LOG_TAG = "TileHost"
        val ACTIVE = setOf(PlaybackState.PLAYING, PlaybackState.DEGRADED)
    }
}
