package com.nuvio.tv.ui.screens.iptv

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Step 2 — "open this playlist's details page when the IPTV settings screen is next shown": set by the
 * setup-code screen once a code has added a playlist. In memory only; a request that never finds its
 * playlist (another profile, a failed pull) goes stale instead of popping a page open much later.
 * Carries the playlist KEY and the provider's name — never the code.
 */
@Singleton
class PlaylistDetailsRequests @Inject constructor() {
    data class Request(val playlistKey: String, val addedBy: String?, val createdAtMs: Long) {
        fun isStale(nowMs: Long): Boolean = nowMs - createdAtMs > MAX_AGE_MS
    }

    enum class Decision { OPEN, WAIT, DROP }

    private val _pending = MutableStateFlow<Request?>(null)
    val pending: StateFlow<Request?> = _pending.asStateFlow()

    fun open(playlistKey: String, addedBy: String?, nowMs: Long = System.currentTimeMillis()) {
        _pending.value = Request(playlistKey, addedBy, nowMs)
    }

    fun consume() {
        _pending.value = null
    }

    companion object {
        const val MAX_AGE_MS = 120_000L

        /** Staleness FIRST: a playlist that only arrives long after the redeem must not open a page unprompted. */
        fun decide(request: Request, hasPlaylist: Boolean, nowMs: Long): Decision = when {
            request.isStale(nowMs) -> Decision.DROP
            hasPlaylist -> Decision.OPEN
            else -> Decision.WAIT
        }
    }
}
