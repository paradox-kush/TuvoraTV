// Ported from homelessbrian/NuvioPlusIPTV @ a86756891 (GPL-3.0), MultiviewSessionCore.kt.
package com.nuvio.tv.core.multiview

/** The only thing the session needs to know about a channel. Equality decides "refreshed". */
internal interface MultiviewChannel {
    val key: String
}

internal data class MultiviewPanePlayback(
    val isBuffering: Boolean = false,
    val isPlaying: Boolean = false,
    val failure: TileFailure? = null,
    /** The stream dropped and is being reconnected; the last picture stays up meanwhile. */
    val isReconnecting: Boolean = false,
)

/** Ports let lifecycle and sound arbitration be tested without codecs or an Android device. */
internal interface MultiviewPanePlayer<C : MultiviewChannel> {
    val playback: MultiviewPanePlayback
    fun prepare(channel: C)
    fun setAudible(audible: Boolean)
    fun setPlaying(playing: Boolean)
    fun release()
}

internal interface MultiviewAudioFocus {
    fun request(onFocusChanged: (Boolean) -> Unit): Boolean
    fun abandon()
}

/**
 * All calls and player callbacks are serialized by the caller (the Android main thread).
 * [createPlayer] gets (slot, buffer bytes for that slot, a callback the player invokes when its
 * playback state changes).
 */
internal class MultiviewSessionCore<C : MultiviewChannel>(
    initialLayout: MultiviewLayout,
    initialTileCount: Int = initialLayout.selectTiles,
    private val totalBufferBytes: Int,
    private val audioFocus: MultiviewAudioFocus,
    private val createPlayer: (Int, Int, () -> Unit) -> MultiviewPanePlayer<C>,
    private val onChanged: () -> Unit,
    private val mayPlayChannel: (C) -> Boolean = { true },
) {
    var layout: MultiviewLayout = MultiviewLayoutPolicy.effective(initialLayout, initialTileCount)
        private set
    var paneCount: Int = initialTileCount.coerceIn(layout.minTiles, layout.maxTiles)
        private set
    var focusedPane: Int = 0
        private set
    var isForeground: Boolean = false
        private set
    var hasAudioFocus: Boolean = false
        private set
    var audioMode: AudioMode = AudioMode.FollowFocus
        private set

    /** The slot currently heard, null when all are silent. */
    var audibleNow: Int? = null
        private set

    private val channels = arrayOfNulls<MultiviewChannel>(MultiviewBufferBudget.MAX_PANES)
    private val players = arrayOfNulls<MultiviewPanePlayer<C>>(MultiviewBufferBudget.MAX_PANES)
    private val setupFailures = arrayOfNulls<TileFailure>(MultiviewBufferBudget.MAX_PANES)
    private var focusRequested = false
    private var focusGeneration = 0
    private var released = false
    private var applying = false
    private var lastAudible: Int? = null
    private var handoffFrom: Int? = null

    @Suppress("UNCHECKED_CAST")
    fun channelAt(paneId: Int): C? = channels.getOrNull(paneId) as C?
    fun playerAt(paneId: Int): MultiviewPanePlayer<C>? = players.getOrNull(paneId)
    fun playbackAt(paneId: Int): MultiviewPanePlayback =
        players.getOrNull(paneId)?.playback
            ?: MultiviewPanePlayback(failure = setupFailures.getOrNull(paneId))

    fun bounds(): List<TileRect> = MultiviewLayoutPolicy.bounds(layout, paneCount)

    /** Change arrangement and, optionally, how many tiles it shows. Tiles beyond the new count are released. */
    fun setLayout(value: MultiviewLayout, tileCount: Int? = null) {
        if (released) return
        val count = (tileCount ?: if (value.supports(paneCount)) paneCount else value.selectTiles)
            .coerceIn(value.minTiles, value.maxTiles)
        if (layout == value && paneCount == count) return
        muteAll()
        if (count < paneCount) {
            for (id in count until paneCount) {
                releasePane(id)
                channels[id] = null
                setupFailures[id] = null
            }
            audioMode = MultiviewAudioPolicy.afterShrink(audioMode, count)
            if ((lastAudible ?: -1) >= count) lastAudible = null
        }
        layout = value
        paneCount = count
        focusedPane = focusedPane.coerceAtMost(count - 1)
        reconcileAudioFocus()
        applyPlaybackAndSound()
        onChanged()
    }

    fun assignChannel(paneId: Int, channel: C) {
        if (!isVisible(paneId) || released) return
        if (channels[paneId] == channel && players[paneId] != null) return
        muteAll()
        releasePane(paneId)
        channels[paneId] = channel
        setupFailures[paneId] = null
        reconcileAudioFocus()
        if (isForeground) preparePane(paneId)
        applyPlaybackAndSound()
        onChanged()
    }

    fun clearPane(paneId: Int) {
        if (!isVisible(paneId) || released) return
        muteAll()
        releasePane(paneId)
        channels[paneId] = null
        setupFailures[paneId] = null
        audioMode = MultiviewAudioPolicy.afterClear(audioMode, paneId)
        reconcileAudioFocus()
        applyPlaybackAndSound()
        onChanged()
    }

    /** Remove this slot, retaining every other player's stream, decoder and buffer ownership. */
    fun removePane(paneId: Int) {
        if (released || !isVisible(paneId) || paneCount <= 1) return
        muteAll()
        val last = paneCount - 1
        releasePane(paneId)
        for (id in paneId until last) {
            channels[id] = channels[id + 1]
            players[id] = players[id + 1]
            setupFailures[id] = setupFailures[id + 1]
        }
        // The last owner moved down; clearing its old reference must not release it again.
        channels[last] = null
        players[last] = null
        setupFailures[last] = null
        paneCount = last
        layout = MultiviewLayoutPolicy.effective(layout, last)
        focusedPane = when {
            focusedPane > paneId -> focusedPane - 1
            focusedPane == paneId -> paneId.coerceAtMost(last - 1)
            else -> focusedPane
        }
        audioMode = MultiviewAudioPolicy.afterRemoval(audioMode, paneId)
        val heard = lastAudible
        when {
            heard == paneId -> { lastAudible = null; handoffFrom = paneId }
            heard != null && heard > paneId -> lastAudible = heard - 1
        }
        reconcileAudioFocus()
        applyPlaybackAndSound()
        onChanged()
    }

    /** Overlay/menu focus never calls this; the selected video retains sound while browsing. */
    fun focusPane(paneId: Int) {
        if (!isVisible(paneId) || released) return
        if (focusedPane == paneId && hasAudioFocus) return
        muteAll()
        focusedPane = paneId
        // Selecting a video is an explicit playback intent, including the already selected pane.
        // This also recovers a denied request or permanent loss without leaving the screen.
        if (isForeground && !hasAudioFocus) {
            abandonFocus()
            reconcileAudioFocus()
        }
        applyPlaybackAndSound()
        onChanged()
    }

    /** Keep the sound on this tile whatever has focus, until [unpinAudio] or the tile goes. */
    fun pinAudio(paneId: Int) {
        if (!isVisible(paneId) || released) return
        muteAll()
        audioMode = AudioMode.Pinned(paneId)
        applyPlaybackAndSound()
        onChanged()
    }

    fun unpinAudio() {
        if (released || audioMode == AudioMode.FollowFocus) return
        muteAll()
        audioMode = AudioMode.FollowFocus
        applyPlaybackAndSound()
        onChanged()
    }

    /** Recreates only this pane's player. */
    fun retryPane(paneId: Int) {
        if (!isVisible(paneId) || released || channels[paneId] == null) return
        muteAll()
        releasePane(paneId)
        setupFailures[paneId] = null
        if (isForeground) {
            // A user retry may also recover an initially denied audio-focus request.
            if (!hasAudioFocus) abandonFocus()
            reconcileAudioFocus()
            preparePane(paneId)
        }
        applyPlaybackAndSound()
        onChanged()
    }

    fun validateAllowedChannels(allowed: List<C>) {
        if (released) return
        if (channels.none { it != null }) return
        val byKey = allowed.associateBy { it.key }
        var changed = false
        for (id in 0 until paneCount) {
            val selected = channels[id] ?: continue
            val refreshed = byKey[selected.key]
            if (refreshed != selected) {
                if (!changed) muteAll()
                releasePane(id)
                channels[id] = refreshed
                setupFailures[id] = null
                changed = true
            }
        }
        if (!changed) return
        // Publish every refreshed request record before permission checks/playback can prune a
        // second pane whose old URL changed in the same provider/allowed-list update.
        reconcileAudioFocus()
        if (isForeground) for (id in 0 until paneCount) preparePane(id)
        applyPlaybackAndSound()
        onChanged()
    }

    fun onForeground() {
        if (released || isForeground) return
        isForeground = true
        reconcileAudioFocus()
        for (id in 0 until paneCount) preparePane(id)
        applyPlaybackAndSound()
        onChanged()
    }

    /** No hidden/background player keeps loading or retains a decoder, surface, or audio focus. */
    fun onBackground() {
        if (released || !isForeground) return
        isForeground = false
        muteAll()
        for (id in players.indices) releasePane(id)
        abandonFocus()
        forgetSound()
        onChanged()
    }

    /** Reopens every request boundary after the VPN scope changes, retaining selection only. */
    fun restartPlayers() {
        if (released || !isForeground) return
        muteAll()
        for (id in players.indices) releasePane(id)
        for (id in 0 until paneCount) preparePane(id)
        applyPlaybackAndSound()
        onChanged()
    }

    fun release() {
        if (released) return
        isForeground = false
        released = true
        muteAll()
        for (id in players.indices) {
            releasePane(id)
            channels[id] = null
            setupFailures[id] = null
        }
        abandonFocus()
        forgetSound()
        onChanged()
    }

    private fun forgetSound() {
        lastAudible = null
        handoffFrom = null
    }

    private fun isVisible(paneId: Int) = paneId in 0 until paneCount

    @Suppress("UNCHECKED_CAST")
    private fun preparePane(paneId: Int) {
        val channel = channels[paneId] as C? ?: return
        // VPN/lifecycle callbacks may precede Compose's next profile/allowed-list render.
        if (!isChannelAllowed(channel)) {
            releasePane(paneId)
            channels[paneId] = null
            setupFailures[paneId] = null
            reconcileAudioFocus()
            return
        }
        if (players[paneId] != null) return
        try {
            val player = createPlayer(
                paneId, MultiviewBufferBudget.bytesPerPane(totalBufferBytes), ::onPlayerChanged,
            )
            players[paneId] = player
            // New players are also constructed silent. Never start/prepare a new audible sink.
            player.setAudible(false)
            player.setPlaying(false)
            player.prepare(channel)
            setupFailures[paneId] = null
        } catch (_: Exception) {
            releasePane(paneId)
            setupFailures[paneId] = TileFailurePolicy.classify(
                TileSignal.Codec(TileFailurePolicy.CODEC_INSUFFICIENT_RESOURCE),
            )
        }
    }

    private fun releasePane(paneId: Int) {
        val old = players[paneId] ?: return
        players[paneId] = null
        old.setAudible(false)
        old.setPlaying(false)
        old.release()
        if (audibleNow == paneId) audibleNow = null
    }

    private fun reconcileAudioFocus() {
        val needsFocus = isForeground && (0 until paneCount).any { channels[it] != null }
        if (!needsFocus) abandonFocus()
        else if (!focusRequested) {
            focusRequested = true
            val generation = ++focusGeneration
            hasAudioFocus = audioFocus.request { granted ->
                if (generation == focusGeneration) onAudioFocusChanged(granted)
            }
        }
    }

    private fun onAudioFocusChanged(granted: Boolean) {
        if (released || !isForeground || !focusRequested) return
        muteAll()
        hasAudioFocus = granted
        applyPlaybackAndSound()
        onChanged()
    }

    private fun abandonFocus() {
        hasAudioFocus = false
        focusGeneration++
        if (focusRequested) {
            focusRequested = false
            audioFocus.abandon()
        }
    }

    private fun muteAll() {
        players.forEach { it?.setAudible(false) }
        audibleNow = null
    }

    @Suppress("UNCHECKED_CAST")
    private fun isChannelAllowed(channel: MultiviewChannel): Boolean =
        runCatching { mayPlayChannel(channel as C) }.getOrDefault(false)

    private fun statusAt(id: Int): TileStatus {
        if (channels[id] == null) return TileStatus.EMPTY
        val player = players[id] ?: return if (setupFailures[id] != null) TileStatus.FAILED else TileStatus.LOADING
        val p = player.playback
        return when {
            p.failure != null -> TileStatus.FAILED
            p.isPlaying && !p.isReconnecting -> TileStatus.PLAYING
            else -> TileStatus.LOADING
        }
    }

    private fun audibleTarget(): Int? = MultiviewAudioPolicy.audible(
        AudioState(
            tiles = (0 until paneCount).map { AudioTile(it, statusAt(it)) },
            focusedId = focusedPane,
            mode = audioMode,
            lastAudible = lastAudible,
            handoffFrom = handoffFrom,
        ),
    )

    /** A pane's playback changed: re-decide sound only if the answer changed (no needless mute blips). */
    private fun onPlayerChanged() {
        if (!released && !applying && isForeground && hasAudioFocus && audibleTarget() != audibleNow) {
            applyPlaybackAndSound()
        }
        onChanged()
    }

    private fun applyPlaybackAndSound() {
        applying = true
        try {
            // Two passes are deliberate: every old pane is silent before the new one is audible.
            muteAll()
            // Focus gain can arrive before Compose observes a profile or permission change, while
            // paused players still exist. Recheck those owners as well as newly prepared players.
            var removed = false
            for (id in channels.indices) {
                val channel = channels[id] ?: continue
                if (!isChannelAllowed(channel)) {
                    channels[id] = null
                    setupFailures[id] = null
                    releasePane(id)
                    removed = true
                }
            }
            // Do not request focus from inside its callback. Invalidating the existing request
            // before abandon also makes any synchronous/queued old callback harmless.
            if (removed && channels.none { it != null }) abandonFocus()
            val playing = isForeground && hasAudioFocus && !released
            players.forEach { it?.setPlaying(playing) }
            if (playing) {
                val target = audibleTarget()
                if (target != null) {
                    players[target]?.setAudible(true)
                    audibleNow = target
                    lastAudible = target
                    handoffFrom = null
                }
            }
        } finally {
            applying = false
        }
    }
}
