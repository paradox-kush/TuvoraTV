package com.nuvio.tv.core.livepause

import kotlin.math.max
import kotlin.math.roundToLong

// Informed by homelessbrian/NuvioPlusIPTV (GPL-3.0), LiveTvTimeshift.kt (the HLS recorder). Rewritten as a
// pure decision object: it fixes the reference's `seq <= lastSeq forever` (a provider restarting its
// numbering froze the picture), its "always the highest BANDWIDTH" variant choice (see
// LivePausePolicy.pickVariant) and its uncapped refetch loop (the owner's stall timer + LiveCaptureReconnect).

/**
 * Decides which segments of a live HLS media playlist to fetch, each exactly once. Pure: feed it every
 * playlist text the owner downloads; it never does I/O and never looks at a clock. "Unchanged for too
 * long" is the OWNER's monotonic stall timer, not the tracker's.
 */
class HlsLiveSegmentTracker {
    data class MediaSegment(val sequence: Long, val durationMs: Long, val uri: String, val discontinuity: Boolean)

    sealed interface Update {
        /** Only never-seen sequences, in order. */
        data class NewSegments(val segments: List<MediaSegment>) : Update
        /** The provider restarted its numbering: accept everything, the first carries `discontinuity = true`. */
        data class SequenceReset(val segments: List<MediaSegment>) : Update
        data object Unchanged : Update
        /** EXT-X-KEY with METHOD other than NONE, EXT-X-BYTERANGE, a master playlist, malformed text. */
        data class Unsupported(val reason: String) : Update
    }

    /** More than 3 inside one capture and the owner Fails (never a silent frozen picture). */
    var resetCount: Int = 0
        private set
    var targetDurationMs: Long = 0L
        private set
    /** EXT-X-MAP (fMP4), resolved against the playlist's URI. */
    var initSegmentUri: String? = null
        private set
    /** The last playlist carried EXT-X-ENDLIST. */
    var ended: Boolean = false
        private set

    private var lastSeen = NONE
    /** sequence → uri (without query) of the segments saved so far, for restart detection. */
    private val saved = LinkedHashMap<Long, String>()

    fun onPlaylist(text: String, baseUri: String): Update {
        val parsed = parse(text, baseUri)
        if (parsed is Parsed.Bad) return Update.Unsupported(parsed.reason)
        parsed as Parsed.Ok

        targetDurationMs = parsed.targetDurationMs
        parsed.initSegmentUri?.let { initSegmentUri = it }
        ended = parsed.ended
        val segments = parsed.segments
        if (segments.isEmpty()) return Update.Unchanged

        if (lastSeen == NONE) return accept(segments, reset = false)

        val first = segments.first().sequence
        val window = segments.size
        val wentFarBack = first < lastSeen - max(window, 3)
        val restartedInOverlap = first <= lastSeen && segments.any { s ->
            s.sequence <= lastSeen && saved[s.sequence]?.let { it != stable(s.uri) } == true
        }
        if (wentFarBack || restartedInOverlap) {
            resetCount++
            saved.clear()
            return accept(segments, reset = true)
        }

        val fresh = segments.filter { it.sequence > lastSeen }
        if (fresh.isEmpty()) return Update.Unchanged
        // Segments we never saw fell off the window: carry on, but tell the player the stream jumped.
        val gap = fresh.first().sequence > lastSeen + 1
        return accept(if (gap) listOf(fresh.first().copy(discontinuity = true)) + fresh.drop(1) else fresh, reset = false)
    }

    private fun accept(segments: List<MediaSegment>, reset: Boolean): Update {
        val out = if (reset) listOf(segments.first().copy(discontinuity = true)) + segments.drop(1) else segments
        for (s in out) saved[s.sequence] = stable(s.uri)
        while (saved.size > MAX_REMEMBERED) saved.remove(saved.keys.first())
        lastSeen = out.last().sequence
        return if (reset) Update.SequenceReset(out) else Update.NewSegments(out)
    }

    private sealed interface Parsed {
        class Bad(val reason: String) : Parsed
        class Ok(
            val segments: List<MediaSegment>,
            val targetDurationMs: Long,
            val initSegmentUri: String?,
            val ended: Boolean,
        ) : Parsed
    }

    private fun parse(text: String, baseUri: String): Parsed {
        val lines = text.lines().map { it.trim() }
        if (lines.firstOrNull { it.isNotEmpty() } != "#EXTM3U") return Parsed.Bad("malformed")
        var mediaSequence = 0L
        var target = 0L
        var initUri: String? = null
        var ended = false
        var pendingDuration = 0L
        var pendingDiscontinuity = false
        var seq = 0L
        var seqSet = false
        val segments = ArrayList<MediaSegment>()
        for (line in lines) {
            when {
                line.isEmpty() -> {}
                line.startsWith("#EXT-X-STREAM-INF") -> return Parsed.Bad("master playlist")
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> {
                    mediaSequence = line.substringAfter(':').trim().toLongOrNull() ?: return Parsed.Bad("malformed")
                }
                line.startsWith("#EXT-X-TARGETDURATION:") -> {
                    target = (line.substringAfter(':').trim().toDoubleOrNull() ?: return Parsed.Bad("malformed"))
                        .let { (it * 1000).roundToLong() }
                }
                line.startsWith("#EXT-X-KEY:") -> {
                    val method = attributes(line.substringAfter(':'))["METHOD"]
                    if (method != null && method != "NONE") return Parsed.Bad("encrypted ($method)")
                }
                line.startsWith("#EXT-X-BYTERANGE") -> return Parsed.Bad("byte range")
                line.startsWith("#EXT-X-MAP:") -> {
                    val attrs = attributes(line.substringAfter(':'))
                    if (attrs.containsKey("BYTERANGE")) return Parsed.Bad("byte range")
                    attrs["URI"]?.let { initUri = resolveHlsUri(baseUri, it) }
                }
                line == "#EXT-X-DISCONTINUITY" -> pendingDiscontinuity = true
                line == "#EXT-X-ENDLIST" -> ended = true
                line.startsWith("#EXTINF:") -> {
                    val d = line.substringAfter(':').substringBefore(',').trim().toDoubleOrNull()
                        ?: return Parsed.Bad("malformed")
                    pendingDuration = (d * 1000).roundToLong()
                }
                line.startsWith("#") -> {}
                else -> {
                    if (!seqSet) { seq = mediaSequence; seqSet = true }
                    segments += MediaSegment(seq, pendingDuration, resolveHlsUri(baseUri, line), pendingDiscontinuity)
                    seq++
                    pendingDuration = 0L
                    pendingDiscontinuity = false
                }
            }
        }
        return Parsed.Ok(segments, target, initUri, ended)
    }

    /** A segment's identity for restart detection: its URI without the (often rotating) query token. */
    private fun stable(uri: String): String = uri.substringBefore('?').substringBefore('#')

    private companion object {
        const val NONE = Long.MIN_VALUE
        const val MAX_REMEMBERED = 256
    }
}

/** The variants of an HLS master playlist (empty if [text] is not a master). I-frame playlists are skipped. */
fun parseHlsMaster(text: String, baseUri: String): List<LivePausePolicy.HlsVariant> {
    val lines = text.lines().map { it.trim() }
    val out = ArrayList<LivePausePolicy.HlsVariant>()
    for ((i, line) in lines.withIndex()) {
        if (!line.startsWith("#EXT-X-STREAM-INF:")) continue
        val uri = lines.drop(i + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: continue
        val attrs = attributes(line.substringAfter(':'))
        val bandwidth = attrs["BANDWIDTH"]?.toLongOrNull() ?: 0L
        val height = attrs["RESOLUTION"]?.substringAfter('x', "")?.toIntOrNull()
        out += LivePausePolicy.HlsVariant(resolveHlsUri(baseUri, uri), bandwidth, height)
    }
    return out
}

/** Attribute list `KEY=value,KEY="quoted, value"` → map (quotes removed). */
private fun attributes(list: String): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    var i = 0
    while (i < list.length) {
        val eq = list.indexOf('=', i)
        if (eq < 0) break
        val key = list.substring(i, eq).trim()
        var j = eq + 1
        val value: String
        if (j < list.length && list[j] == '"') {
            val close = list.indexOf('"', j + 1).let { if (it < 0) list.length else it }
            value = list.substring(j + 1, close)
            j = close + 1
        } else {
            val comma = list.indexOf(',', j).let { if (it < 0) list.length else it }
            value = list.substring(j, comma).trim()
            j = comma
        }
        out[key] = value
        i = list.indexOf(',', j).let { if (it < 0) list.length else it + 1 }
    }
    return out
}

private val URI_PARTS = Regex("^([a-zA-Z][a-zA-Z0-9+.\\-]*):(//([^/?#]*))?([^?#]*)(\\?[^#]*)?(#.*)?$")

/** RFC 3986-ish reference resolution, pure Kotlin (no java.net, so it also compiles for Kotlin/Native). */
fun resolveHlsUri(base: String, ref: String): String {
    if (ref.isEmpty()) return base.substringBefore('#')
    if (Regex("^[a-zA-Z][a-zA-Z0-9+.\\-]*:").containsMatchIn(ref)) return ref
    val m = URI_PARTS.matchEntire(base) ?: return ref
    val scheme = m.groupValues[1]
    val hasAuthority = m.groups[2] != null
    val authority = m.groupValues[3]
    val basePath = m.groupValues[4]
    val baseQuery = m.groupValues[5]
    val prefix = scheme + ":" + (if (hasAuthority) "//$authority" else "")
    if (ref.startsWith("//")) return "$scheme:$ref"

    val cut = ref.indexOfFirst { it == '?' || it == '#' }.let { if (it < 0) ref.length else it }
    val refPath = ref.substring(0, cut)
    val refTail = ref.substring(cut)
    return when {
        refPath.isEmpty() -> prefix + basePath + (if (refTail.startsWith("?")) refTail else baseQuery + refTail)
        refPath.startsWith("/") -> prefix + removeDotSegments(refPath) + refTail
        else -> {
            val dir = basePath.substring(0, basePath.lastIndexOf('/') + 1).ifEmpty { if (hasAuthority) "/" else "" }
            prefix + removeDotSegments(dir + refPath) + refTail
        }
    }
}

private fun removeDotSegments(path: String): String {
    val out = ArrayList<String>()
    val parts = path.split('/')
    for ((i, part) in parts.withIndex()) {
        val last = i == parts.lastIndex
        when (part) {
            "." -> if (last) out += ""
            ".." -> {
                if (out.size > 1) out.removeAt(out.lastIndex)
                if (last) out += ""
            }
            else -> out += part
        }
    }
    val joined = out.joinToString("/")
    return if (path.startsWith("/") && !joined.startsWith("/")) "/$joined" else joined
}
