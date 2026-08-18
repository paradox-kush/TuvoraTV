package com.nuvio.tv.updater

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * "The player owns the screen right now."
 *
 * Set by [com.nuvio.tv.ui.screens.player.PlayerScreen] and read by app-level chrome that must not
 * steal pixels from it — today the update banner, which is a layout sibling of the whole app and so
 * shrinks whatever sits below it.
 *
 * A [StateFlow] rather than a plain flag because Compose has to recompose when it flips.
 *
 * Reference counted so a screen transition, where the arriving and leaving players are both briefly
 * composed, cannot let the leaving one clear a flag the arriving one still needs.
 */
object ImmersivePlaybackGate {
    private var activeCount = 0
    private val _isActive = MutableStateFlow(false)

    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    /** Balance every `true` with a `false` — call sites do it from `DisposableEffect.onDispose`. */
    @Synchronized
    fun setImmersive(active: Boolean) {
        activeCount = if (active) activeCount + 1 else (activeCount - 1).coerceAtLeast(0)
        _isActive.value = activeCount > 0
    }

    /** Test hook — drops any leaked counts between cases. */
    @Synchronized
    fun resetForTest() {
        activeCount = 0
        _isActive.value = false
    }
}
