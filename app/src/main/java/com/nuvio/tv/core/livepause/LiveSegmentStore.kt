package com.nuvio.tv.core.livepause

/** One finished piece of a live buffer / recording. */
data class LivePiece(
    val sequence: Long,
    val name: String,
    val durationMs: Long,
    val bytes: Long = 0L,
    val discontinuity: Boolean = false,
)

interface LiveSegmentWriter {
    fun write(bytes: ByteArray, offset: Int, length: Int)
    fun close()
}

interface LiveSegmentStore {
    fun openPiece(name: String): LiveSegmentWriter
    fun delete(name: String)
    fun writePlaylistAtomically(text: String)
}
