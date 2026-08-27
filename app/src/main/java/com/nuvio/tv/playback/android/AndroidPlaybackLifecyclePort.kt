package com.nuvio.tv.playback.android

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.nuvio.tv.playback.core.PlaybackLifecycleEvent
import com.nuvio.tv.playback.core.PlaybackLifecyclePort
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Converts the UI owner's visible lifecycle into the engine-neutral session lifecycle.
 *
 * STARTED is the ownership boundary: leaving it must stop reconnect work and release the
 * provider connection; destruction is terminal. Each collector receives the current state first,
 * and repeated Android callbacks are suppressed.
 */
class AndroidPlaybackLifecyclePort(
    private val lifecycle: Lifecycle,
) : PlaybackLifecyclePort {
    override fun events(): Flow<PlaybackLifecycleEvent> = callbackFlow {
        var latest: PlaybackLifecycleEvent? = null

        fun publish(value: PlaybackLifecycleEvent) {
            if (latest != value) {
                latest = value
                trySend(value)
            }
        }

        publish(lifecycle.currentState.toPlaybackLifecycleEvent())
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> publish(PlaybackLifecycleEvent.ACTIVE)
                Lifecycle.Event.ON_STOP -> publish(PlaybackLifecycleEvent.INACTIVE)
                Lifecycle.Event.ON_DESTROY -> publish(PlaybackLifecycleEvent.DESTROYED)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        awaitClose { lifecycle.removeObserver(observer) }
    }
}

private fun Lifecycle.State.toPlaybackLifecycleEvent(): PlaybackLifecycleEvent = when {
    this == Lifecycle.State.DESTROYED -> PlaybackLifecycleEvent.DESTROYED
    isAtLeast(Lifecycle.State.STARTED) -> PlaybackLifecycleEvent.ACTIVE
    else -> PlaybackLifecycleEvent.INACTIVE
}
