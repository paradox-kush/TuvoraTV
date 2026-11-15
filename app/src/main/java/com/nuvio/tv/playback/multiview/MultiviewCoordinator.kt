package com.nuvio.tv.playback.multiview

import android.content.Context
import android.os.Looper
import com.nuvio.tv.core.multiview.MultiviewAudioFocus
import com.nuvio.tv.playback.core.AudioFocusEvent
import com.nuvio.tv.playback.core.AudioFocusPort
import com.nuvio.tv.playback.core.PlaybackCommand
import com.nuvio.tv.playback.core.PlaybackResult
import com.nuvio.tv.playback.mediasession.CleanMediaSessionOwner
import com.nuvio.tv.playback.ui.PlaybackSessionController
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The single system media session, showing whichever tile is audible. */
internal interface AudibleTileMediaSession {
    /** Shows [tile] (null = nothing is audible). Called on the main thread. */
    fun show(tile: AudibleTileSource?)
    fun release()
}

/**
 * Android-side owner of the two things only one thing on the box may hold: audio focus and the media
 * session. Every tile is a host with neither; this holds ONE focus request for the whole screen
 * (the [MultiviewAudioFocus] port the session core drives) and ONE media session for the audible tile.
 * Decisions stay in the core: this only does what it is told.
 *
 * Main thread only, like the core. [focus] is Android's own port; its calls complete without suspending.
 */
internal class MultiviewCoordinator(
    private val scope: CoroutineScope,
    private val focus: AudioFocusPort,
    private val mediaSession: AudibleTileMediaSession,
) : MultiviewAudioFocus {
    private var eventsJob: Job? = null
    private var shown: AudibleTileSource? = null
    private var released = false

    override fun request(onFocusChanged: (Boolean) -> Unit): Boolean {
        eventsJob?.cancel()
        // Subscribe before asking: the focus port's events are not replayed.
        eventsJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            focus.events().collect { event ->
                when (event) {
                    AudioFocusEvent.GAIN -> onFocusChanged(true)
                    AudioFocusEvent.LOSS_TRANSIENT, AudioFocusEvent.LOSS_PERMANENT -> onFocusChanged(false)
                    // The system lowers the volume itself; nothing to pause.
                    AudioFocusEvent.LOSS_DUCK -> Unit
                }
            }
        }
        return completedNow { focus.acquire() } is PlaybackResult.Success
    }

    override fun abandon() {
        eventsJob?.cancel()
        eventsJob = null
        completedNow { focus.abandon() }
    }

    /** The core's audible slot changed: show that tile (or none) in the media session. */
    fun showAudible(tile: AudibleTileSource?) {
        if (released || shown === tile) return
        shown = tile
        mediaSession.show(tile)
    }

    fun release() {
        if (released) return
        released = true
        abandon()
        shown = null
        mediaSession.release()
    }

    /** Runs a port call that is specified not to suspend; a call that does is treated as not done. */
    private fun <T> completedNow(block: suspend () -> T): T? {
        var result: Result<T>? = null
        block.startCoroutine(Continuation(EmptyCoroutineContext) { result = it })
        return result?.getOrNull()
    }
}

/**
 * The real session: one [CleanMediaSessionOwner] created on first use, fed by a proxy controller that
 * mirrors the shown tile's snapshot and forwards pause/resume/stop to it. Releasing it never touches a
 * tile's host (each host releases itself).
 */
internal class AndroidAudibleTileMediaSession(
    private val context: Context,
    private val looper: Looper,
    private val scope: CoroutineScope,
) : AudibleTileMediaSession {
    private var owner: CleanMediaSessionOwner? = null
    private var mirror: Job? = null
    private var current: AudibleTileSource? = null
    private val proxySnapshot = MutableStateFlow(com.nuvio.tv.playback.core.PlaybackSnapshot())

    private val proxy = PlaybackSessionController(
        snapshot = proxySnapshot,
        dispatchCommand = { command ->
            val tile = current
            when (command) {
                PlaybackCommand.Pause -> tile?.pause()
                PlaybackCommand.Resume -> tile?.resume()
                PlaybackCommand.Stop -> tile?.stop()
                else -> Unit
            }
        },
        // The tiles own their hosts; the session never releases one.
        releaseSession = {},
    )

    override fun show(tile: AudibleTileSource?) {
        mirror?.cancel()
        mirror = null
        current = tile
        if (tile == null) return
        proxySnapshot.value = tile.snapshot.value
        mirror = scope.launch { tile.snapshot.collect { proxySnapshot.value = it } }
        val existing = owner
        if (existing == null) {
            owner = runCatching {
                CleanMediaSessionOwner.create(context, looper, scope, proxy, tile.metadata)
            }.getOrNull()
        } else {
            runCatching { existing.updateMetadata(tile.metadata) }
        }
    }

    override fun release() {
        mirror?.cancel()
        mirror = null
        current = null
        val old = owner ?: return
        owner = null
        scope.launch { withContext(NonCancellable) { runCatching { old.release() } } }
    }
}
