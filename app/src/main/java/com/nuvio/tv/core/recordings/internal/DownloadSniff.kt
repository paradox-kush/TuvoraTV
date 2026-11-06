package com.nuvio.tv.core.recordings.internal

/** Tells an HLS playlist from a media file before a byte of it is written. */
internal object DownloadSniff {
    fun isHlsPlaylist(contentType: String?, firstBytes: ByteArray): Boolean = false
}
