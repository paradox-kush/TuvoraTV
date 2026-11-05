package com.nuvio.tv.core.livepause

import java.io.ByteArrayOutputStream
import java.io.IOException

/** Byte fixtures: real 188-byte MPEG-TS packets built from the spec, so the segmenter runs with no files. */
internal object Ts {
    const val PMT_PID = 0x100
    const val VIDEO_PID = 0x101
    const val AUDIO_PID = 0x102

    private fun blank(pid: Int, pusi: Boolean): ByteArray {
        val p = ByteArray(188) { 0xFF.toByte() }
        p[0] = 0x47
        p[1] = ((if (pusi) 0x40 else 0) or (pid shr 8)).toByte()
        p[2] = (pid and 0xFF).toByte()
        p[3] = 0x10 // payload only
        return p
    }

    fun pat(): ByteArray {
        val p = blank(0, true)
        p[4] = 0 // pointer field
        val section = intArrayOf(
            0x00, 0xB0, 13, 0x00, 0x01, 0xC1, 0x00, 0x00,
            0x00, 0x01, 0xE0 or (PMT_PID shr 8), PMT_PID and 0xFF,
            0, 0, 0, 0, // CRC (not checked)
        )
        section.forEachIndexed { i, b -> p[5 + i] = b.toByte() }
        return p
    }

    fun pmt(pcrPid: Int = VIDEO_PID): ByteArray {
        val p = blank(PMT_PID, true)
        p[4] = 0
        val section = intArrayOf(
            0x02, 0xB0, 18, 0x00, 0x01, 0xC1, 0x00, 0x00,
            0xE0 or (pcrPid shr 8), pcrPid and 0xFF, 0xF0, 0x00,
            0x1B, 0xE0 or (VIDEO_PID shr 8), VIDEO_PID and 0xFF, 0xF0, 0x00,
            0, 0, 0, 0,
        )
        section.forEachIndexed { i, b -> p[5 + i] = b.toByte() }
        return p
    }

    /** A video packet: with a PCR (ms of stream time) and/or the random-access flag, or plain payload. */
    fun video(pcrMs: Long?, rai: Boolean = false, pusi: Boolean = false, pid: Int = VIDEO_PID): ByteArray {
        val p = blank(pid, pusi)
        if (pcrMs == null && !rai) return p
        p[3] = 0x30 // adaptation field + payload bits; the field fills the packet
        p[4] = 183.toByte()
        p[5] = ((if (rai) 0x40 else 0) or (if (pcrMs != null) 0x10 else 0)).toByte()
        if (pcrMs != null) {
            val base = pcrMs * 90
            p[6] = (base shr 25).toByte()
            p[7] = (base shr 17).toByte()
            p[8] = (base shr 9).toByte()
            p[9] = (base shr 1).toByte()
            p[10] = (((base and 1L) shl 7) or 0x7EL).toByte()
            p[11] = 0
        }
        return p
    }

    fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        parts.forEach { out.write(it) }
        return out.toByteArray()
    }

    /**
     * Packets every [stepMs] from [fromMs] to [toMs] inclusive, each carrying the PCR for its time.
     * [rai] and [pusi] decide the flags per timestamp.
     */
    fun stream(
        fromMs: Long,
        toMs: Long,
        stepMs: Long = 500,
        withPcr: Boolean = true,
        rai: (Long) -> Boolean = { true },
        pusi: (Long) -> Boolean = { false },
    ): ByteArray {
        val out = ByteArrayOutputStream()
        var t = fromMs
        while (t <= toMs) {
            out.write(video(if (withPcr) t else null, rai(t), pusi(t)))
            t += stepMs
        }
        return out.toByteArray()
    }

    fun tables(): ByteArray = concat(pat(), pmt())

    fun pidOf(packet: ByteArray): Int = ((packet[1].toInt() and 0x1F) shl 8) or (packet[2].toInt() and 0xFF)
    fun packetAt(bytes: ByteArray, index: Int): ByteArray = bytes.copyOfRange(index * 188, index * 188 + 188)
}

/** In-memory [LiveSegmentStore]: every piece's bytes, closes, deletes, and each playlist written. */
internal class FakeLiveSegmentStore : LiveSegmentStore {
    val pieces = LinkedHashMap<String, ByteArrayOutputStream>()
    val closed = mutableListOf<String>()
    val deleted = mutableListOf<String>()
    val playlists = mutableListOf<String>()
    val opened = mutableListOf<String>()
    var failWritesOn: String? = null

    val lastPlaylist: String get() = playlists.last()

    override fun openPiece(name: String): LiveSegmentWriter {
        opened += name
        val buf = ByteArrayOutputStream().also { pieces[name] = it }
        return object : LiveSegmentWriter {
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                if (failWritesOn == name) throw IOException("disk full")
                buf.write(bytes, offset, length)
            }

            override fun close() {
                closed += name
            }
        }
    }

    override fun delete(name: String) {
        deleted += name
        pieces.remove(name)
    }

    override fun writePlaylistAtomically(text: String) {
        playlists += text
    }

    fun bytesOf(name: String): ByteArray = pieces.getValue(name).toByteArray()
}
