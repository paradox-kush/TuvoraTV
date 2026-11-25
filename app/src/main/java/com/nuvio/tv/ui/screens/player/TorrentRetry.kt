package com.nuvio.tv.ui.screens.player

/** What a torrent Retry re-opens: the stream's info hash and, for multi-file torrents, the file index. */
internal data class TorrentRetryTarget(val infoHash: String, val fileIdx: Int?)

/**
 * Retry on a torrent stream (B285). [teardown] (releasePlayer + stopTorrentStream) clears the controller's
 * hash and file index, so the target is read from [current] FIRST and handed to [relaunch]. Returns false
 * (nothing done) when the current stream is not a torrent with a known hash.
 */
internal inline fun retryTorrent(
    current: () -> TorrentRetryTarget?,
    teardown: () -> Unit,
    relaunch: (TorrentRetryTarget) -> Unit,
): Boolean {
    val target = current() ?: return false
    teardown()
    relaunch(target)
    return true
}
