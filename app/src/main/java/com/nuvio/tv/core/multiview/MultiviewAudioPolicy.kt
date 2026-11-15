package com.nuvio.tv.core.multiview

internal sealed interface AudioMode {
    /** Sound follows the focused tile (default on TV). */
    data object FollowFocus : AudioMode

    /** Sound stays on one tile whatever has focus. */
    data class Pinned(val tileId: Int) : AudioMode
}

internal enum class TileStatus { EMPTY, LOADING, PLAYING, FAILED }

internal data class AudioTile(val id: Int, val status: TileStatus)

/**
 * @property tiles visible tiles in reading order.
 * @property lastAudible the tile that had sound before this decision, if any.
 * @property handoffFrom where to look for a new owner of the sound when [lastAudible] is gone
 *   (a removed tile's slot, after the others shifted down); null when there is no hand-off pending.
 */
internal data class AudioState(
    val tiles: List<AudioTile>,
    val focusedId: Int?,
    val mode: AudioMode = AudioMode.FollowFocus,
    val lastAudible: Int? = null,
    val handoffFrom: Int? = null,
)

internal object MultiviewAudioPolicy {
    /** The one tile that may be heard, or null for silence. A loading or failed tile never is. */
    fun audible(state: AudioState): Int? {
        val playing = state.tiles.filter { it.status == TileStatus.PLAYING }.map { it.id }
        val wanted = when (val m = state.mode) {
            AudioMode.FollowFocus -> state.focusedId
            is AudioMode.Pinned -> m.tileId
        }
        if (wanted != null && wanted in playing) return wanted
        val last = state.lastAudible
        if (last != null && last in playing) return last
        val anchor = if (last != null) last + 1 else state.handoffFrom
        if (anchor == null || playing.isEmpty()) return null
        return playing.firstOrNull { it >= anchor } ?: playing.first()
    }

    /** The mode after tile [removedId] is removed and later tiles shift down by one. */
    fun afterRemoval(mode: AudioMode, removedId: Int): AudioMode = when (mode) {
        AudioMode.FollowFocus -> mode
        is AudioMode.Pinned -> when {
            mode.tileId == removedId -> AudioMode.FollowFocus
            mode.tileId > removedId -> AudioMode.Pinned(mode.tileId - 1)
            else -> mode
        }
    }

    /** The mode after the slot [clearedId] is emptied (the tile stays, its stream is gone). */
    fun afterClear(mode: AudioMode, clearedId: Int): AudioMode =
        if (mode is AudioMode.Pinned && mode.tileId == clearedId) AudioMode.FollowFocus else mode

    /** The mode after the visible tile count drops to [tileCount]. */
    fun afterShrink(mode: AudioMode, tileCount: Int): AudioMode =
        if (mode is AudioMode.Pinned && mode.tileId >= tileCount) AudioMode.FollowFocus else mode
}
