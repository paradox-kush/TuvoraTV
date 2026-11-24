package com.nuvio.tv.ui.screens.player

import android.util.Log
import com.nuvio.tv.core.diagnostics.LogRedaction
import com.nuvio.tv.core.network.PlaybackNetworkSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Registers this player with the process [PlaybackNetworkSwitch] for the stream being opened (one registration per
 * player; a rebuild replaces it, [releasePlayer] drops it). On a real default-network switch the shared VOD OkHttp
 * stack is flushed — Media3's load retry and the parallel-range chunk retry then re-open on the new network — and a
 * libmpv player reloads at the playhead, since FFmpeg's socket would otherwise stay on the old network for good.
 * [singleUseLink]: a single-use catch-up replay, never re-opened here.
 */
internal fun PlayerRuntimeController.registerNetworkSwitch(singleUseLink: Boolean) {
    networkSwitchRegistration?.dispose()
    networkSwitchRegistration = PlaybackNetworkSwitch.coordinator(context).register(singleUseLink) {
        scope.launch(Dispatchers.Main.immediate) { reconnectMpvOnCurrentNetwork() }
    }
}

private fun PlayerRuntimeController.reconnectMpvOnCurrentNetwork() {
    if (isReleasingPlayer || !isUsingMpvEngine()) return
    val view = mpvView ?: return
    val (url, headers) = mpvLoadedSource ?: return
    val positionMs = currentPlaybackPositionMs()?.coerceAtLeast(0L) ?: 0L
    val keepPlaying = hasActivePlayIntent()
    Log.i(PlayerRuntimeController.TAG, "default network changed: reloading libmpv at ${positionMs}ms on it")
    runCatching {
        view.setMedia(url, headers, positionMs)
        // mpv keeps its pause flag across a loadfile: say the intent explicitly either way.
        view.setPaused(!keepPlaying)
    }.onFailure { Log.w(PlayerRuntimeController.TAG, "network-switch reload failed: ${LogRedaction.text(it.message)}") }
}
