package com.nuvio.tv.core.livepause

import kotlin.math.ceil
import kotlin.math.max

// Adapted from homelessbrian/NuvioPlusIPTV (GPL-3.0), TsSegmenter.kt.
// Changed from the original: file I/O goes through the [LiveSegmentStore] port (pure, runs from byte
// fixtures); the clock is injected and monotonic (never wall clock); retention is NOT inside (the
// owner calls LivePausePolicy.piecesToDrop + [dropOldest]); the playlist text comes from
// [LocalHlsPlaylist]; no @Volatile shared state (confined to one coroutine, [state] is an immutable
// snapshot); no runCatching swallowing (store errors propagate); the first piece's tables are no longer
// written twice; the playlist is rewritten before dropped files are deleted.

/** Immutable snapshot of what a segmenter holds right now. */
data class TsSegmenterState(val pieces: List<LivePiece>, val totalMs: Long, val trimmedMs: Long)

/**
 * Cuts a live MPEG-TS stream into short pieces with a local HLS playlist, as it arrives, so the stream can
 * be paused and rewound (and recorded) while it is still being captured.
 *
 *  - Pieces are about [targetMs] long (the first one about [firstTargetMs], so playback can start soon),
 *    cut where a picture starts cleanly when the stream marks one.
 *  - Every piece starts with the stream's program tables (PAT / PMT), so each one plays on its own.
 *  - Lengths come from the stream's own clock (PCR) when it has one, else [monotonicMs].
 *  - [keepAll] (recordings): the playlist is an EVENT playlist and the owner never drops. A rewind buffer
 *    is not EVENT because it drops from the front.
 *
 * Feed it bytes with [write] (any sizes); call [finish] at the end. Not thread-safe: one coroutine owns it.
 */
class TsSegmenter(
    private val store: LiveSegmentStore,
    private val monotonicMs: () -> Long,
    private val targetMs: Long = 6_000L,
    private val firstTargetMs: Long = 2_000L,
    private val keepAll: Boolean = false,
    /** A piece was saved; the playlist is already updated. The owner applies retention here. */
    private val onPiece: (LivePiece) -> Unit = {},
) {
    private val pieces = ArrayList<LivePiece>()
    private var trimmedMs = 0L
    private var totalMs = 0L

    // Packet assembly (input arrives in any sizes; TS packets are 188 bytes).
    private val pkt = ByteArray(PACKET)
    private var pktFill = 0
    private var synced = false

    // Program tables, repeated at the start of every piece.
    private var pat: ByteArray? = null
    private val pmtPids = HashSet<Int>()
    private val pmts = LinkedHashMap<Int, ByteArray>()
    private var pcrPid = -1

    // The piece being written.
    private var seq = 0L
    private var out: LiveSegmentWriter? = null
    private var outName: String? = null
    private var pieceStartPcr = -1L
    private var lastPcr = -1L
    private var pieceStartMs = 0L
    private var pieceBytes = 0L
    private var pieceHasMedia = false
    private var pieceDiscontinuity = false
    private var nextIsDiscontinuity = false

    // Steady from the start: only ever grows, so a player never sees the target shrink.
    private var targetDurationSec = MIN_TARGET_DURATION_SEC

    /** An immutable snapshot of the pieces now in the buffer. */
    fun state(): TsSegmenterState = TsSegmenterState(pieces.toList(), totalMs, trimmedMs)

    fun write(buf: ByteArray, off: Int, len: Int) {
        var i = off
        val end = off + len
        while (i < end) {
            if (!synced) {
                // Find the sync byte that starts a packet.
                if (buf[i] != SYNC) { i++; continue }
                synced = true
                pktFill = 0
            }
            val take = minOf(PACKET - pktFill, end - i)
            buf.copyInto(pkt, pktFill, i, i + take)
            pktFill += take
            i += take
            if (pktFill == PACKET) {
                pktFill = 0
                if (pkt[0] != SYNC) { synced = false; continue }
                packet(pkt)
            }
        }
    }

    /**
     * The connection dropped and a new one is starting: close the current piece and mark the next as a
     * fresh start (its clock starts over), so the player carries on across the gap.
     */
    fun reconnected() {
        closePiece()
        synced = false
        pktFill = 0
        lastPcr = -1L
        nextIsDiscontinuity = seq > 0
    }

    /** Closes the last piece and marks the playlist finished (no more to come). */
    fun finish(ended: Boolean = true) {
        closePiece()
        writePlaylist(ended)
    }

    /**
     * Retention, applied by the owner (`LivePausePolicy.piecesToDrop`): drops the [n] oldest pieces. The
     * playlist is rewritten first so a player never reads an entry whose file is already gone.
     */
    fun dropOldest(n: Int) {
        val count = n.coerceIn(0, pieces.size)
        if (count == 0) return
        val dropped = ArrayList<LivePiece>(pieces.subList(0, count))
        repeat(count) { pieces.removeAt(0) }
        for (p in dropped) {
            trimmedMs += p.durationMs
            totalMs -= p.durationMs
        }
        writePlaylist(ended = false)
        for (p in dropped) store.delete(p.name)
    }

    private fun packet(p: ByteArray) {
        val pid = ((p[1].toInt() and 0x1F) shl 8) or (p[2].toInt() and 0xFF)
        val pusi = (p[1].toInt() and 0x40) != 0
        val afc = (p[3].toInt() shr 4) and 0x3
        var randomAccess = false
        var pcr = -1L
        if ((afc == 2 || afc == 3) && (p[4].toInt() and 0xFF) > 0) {
            val flags = p[5].toInt() and 0xFF
            randomAccess = (flags and 0x40) != 0
            if ((flags and 0x10) != 0 && (p[4].toInt() and 0xFF) >= 7) {
                pcr = ((p[6].toLong() and 0xFF) shl 25) or ((p[7].toLong() and 0xFF) shl 17) or
                    ((p[8].toLong() and 0xFF) shl 9) or ((p[9].toLong() and 0xFF) shl 1) or
                    ((p[10].toLong() and 0x80) shr 7)
            }
        }
        // Tables: keep the latest copy of each.
        var isTable = false
        if (pid == 0 && pusi) {
            pat = p.copyOf()
            readPat(p)
            isTable = true
        } else if (pid in pmtPids && pusi) {
            pmts[pid] = p.copyOf()
            readPmt(p)
            isTable = true
        }
        if (pcr >= 0 && (pcrPid < 0 || pid == pcrPid)) {
            if (pcrPid < 0) pcrPid = pid
            lastPcr = pcr
        }

        // Time to start a new piece?
        var opened = false
        if (out == null) {
            // Wait for the tables before the first piece, so it plays on its own.
            if (pat == null || pmts.isEmpty()) return
            openPiece()
            opened = true
        } else if (pieceHasMedia) {
            // (A piece with no media yet has no length to judge; the first packet always goes in.)
            val len = currentLengthMs()
            val target = if (seq == 0L) firstTargetMs else targetMs
            val cleanCut = randomAccess && len >= target
            val forcedCut = len >= target * 2 && pusi && pid == pcrPid
            if (cleanCut || forcedCut || len >= target * 3) {
                closePiece()
                openPiece()
                opened = true
            }
        }
        // A table packet that opened the piece is already in it (openPiece writes the latest copies).
        if (opened && isTable) return
        out?.write(p, 0, PACKET)
        pieceBytes += PACKET
        pieceHasMedia = true
        // The piece's PCR clock starts at the first PCR it sees (the tables open it before any PCR).
        if (pieceStartPcr < 0 && lastPcr >= 0) pieceStartPcr = lastPcr
    }

    private fun currentLengthMs(): Long {
        if (pieceStartPcr >= 0 && lastPcr >= pieceStartPcr) {
            val d = (lastPcr - pieceStartPcr) / 90 // 90 kHz ticks to ms
            if (d < MAX_PCR_PIECE_MS) return d
        }
        return monotonicMs() - pieceStartMs
    }

    private fun openPiece() {
        val name = "seg$seq.ts"
        val w = store.openPiece(name)
        outName = name
        out = w
        pieceBytes = 0
        pat?.let { w.write(it, 0, PACKET); pieceBytes += PACKET }
        for (t in pmts.values) { w.write(t, 0, PACKET); pieceBytes += PACKET }
        pieceStartPcr = lastPcr
        pieceStartMs = monotonicMs()
        pieceHasMedia = false
        pieceDiscontinuity = nextIsDiscontinuity
        nextIsDiscontinuity = false
    }

    private fun closePiece() {
        val w = out ?: return
        val name = outName ?: return
        val len = max(currentLengthMs(), 1L)
        val bytes = pieceBytes
        val hadMedia = pieceHasMedia
        out = null
        outName = null
        w.close()
        if (!hadMedia) { store.delete(name); return }
        val piece = LivePiece(seq, name, len, bytes, pieceDiscontinuity)
        pieces += piece
        totalMs += len
        seq++
        targetDurationSec = max(targetDurationSec, ceil(len / 1000.0).toInt())
        writePlaylist(ended = false)
        onPiece(piece)
    }

    private fun writePlaylist(ended: Boolean) {
        if (pieces.isEmpty()) return
        store.writePlaylistAtomically(
            LocalHlsPlaylist.render(
                pieces = pieces,
                targetDurationSec = targetDurationSec,
                mediaSequence = pieces.first().sequence,
                ended = ended,
                eventType = keepAll,
                initSegment = null,
            ),
        )
    }

    /** The PMT packet ids listed in the PAT. */
    private fun readPat(p: ByteArray) {
        val start = payloadStart(p) ?: return
        var i = start + 1 + (p[start].toInt() and 0xFF) // pointer field
        if (i + 8 > PACKET) return
        val sectionLen = ((p[i + 1].toInt() and 0x0F) shl 8) or (p[i + 2].toInt() and 0xFF)
        val endOfPrograms = minOf(i + 3 + sectionLen - 4, PACKET)
        i += 8
        val found = HashSet<Int>()
        while (i + 4 <= endOfPrograms) {
            val program = ((p[i].toInt() and 0xFF) shl 8) or (p[i + 1].toInt() and 0xFF)
            val pid = ((p[i + 2].toInt() and 0x1F) shl 8) or (p[i + 3].toInt() and 0xFF)
            if (program != 0) found += pid
            i += 4
        }
        if (found.isNotEmpty() && found != pmtPids) {
            pmtPids.clear(); pmtPids += found
            pmts.keys.retainAll(found)
        }
    }

    /** The PCR packet id from a PMT (the stream's clock). */
    private fun readPmt(p: ByteArray) {
        val start = payloadStart(p) ?: return
        val i = start + 1 + (p[start].toInt() and 0xFF)
        if (i + 10 > PACKET) return
        val pid = ((p[i + 8].toInt() and 0x1F) shl 8) or (p[i + 9].toInt() and 0xFF)
        if (pid != 0x1FFF) pcrPid = pid
    }

    private fun payloadStart(p: ByteArray): Int? {
        val afc = (p[3].toInt() shr 4) and 0x3
        return when (afc) {
            1 -> 4
            3 -> (5 + (p[4].toInt() and 0xFF)).takeIf { it < PACKET }
            else -> null
        }
    }

    private companion object {
        const val PACKET = 188
        const val SYNC: Byte = 0x47
        const val MIN_TARGET_DURATION_SEC = 20
        const val MAX_PCR_PIECE_MS = 120_000L
    }
}
