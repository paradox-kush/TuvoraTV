package com.nuvio.tv.core.telemetry.playback

/**
 * One on-device process memory sample. All values are whole megabytes / counts. Sampled at most every
 * [MemorySamplePolicy.MIN_INTERVAL_MS] while playing, off the main thread, and kept in a tiny
 * [MemorySampleRing]; the last one rides on `app_exit`, `app_crash_report`, `user_issue_report` and
 * `multiview_session`.
 */
data class MemorySample(
    val atMs: Long,
    val pssMb: Int,
    val javaMb: Int,
    val nativeMb: Int,
    /** System-wide available memory (`MemAvailable`); -1 when unknown. */
    val availMb: Int = -1,
    /** Threads in this process; -1 when unknown. */
    val threads: Int = -1,
    /** Open file descriptors in this process; -1 when unknown. */
    val fds: Int = -1,
) {
    /** The contract's property names. Unknown (-1) values are left out, never invented. */
    fun toProperties(): Map<String, Any> = buildMap {
        put("mem_pss_mb", pssMb)
        put("mem_java_mb", javaMb)
        put("mem_native_mb", nativeMb)
        if (availMb >= 0) put("mem_avail_mb", availMb)
        if (threads >= 0) put("mem_threads", threads)
        if (fds >= 0) put("mem_fds", fds)
    }

    /** `pss,java,native,avail,threads,fds` for persistence next to the run's other breadcrumbs. */
    fun toCompact(): String = "$pssMb,$javaMb,$nativeMb,$availMb,$threads,$fds"

    companion object {
        /** Parses [toCompact] output; null for anything malformed. [atMs] is not persisted (0). */
        fun fromCompact(raw: String?): MemorySample? {
            val p = raw?.split(',')?.map { it.trim().toIntOrNull() ?: return null } ?: return null
            if (p.size != 6) return null
            return MemorySample(0L, p[0], p[1], p[2], p[3], p[4], p[5])
        }
    }
}

object MemorySamplePolicy {
    const val MIN_INTERVAL_MS = 10_000L

    /** True when a new sample may be taken: never faster than [MIN_INTERVAL_MS]. */
    fun due(lastAtMs: Long?, nowMs: Long): Boolean = lastAtMs == null || nowMs - lastAtMs >= MIN_INTERVAL_MS
}

/** The last few samples. Thread-safe; small by design (a minute of history at the sampling rate). */
class MemorySampleRing(private val capacity: Int = 6) {
    private val items = ArrayDeque<MemorySample>()

    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    @Synchronized
    fun add(sample: MemorySample) {
        if (items.size >= capacity) items.removeFirst()
        items.addLast(sample)
    }

    @Synchronized
    fun last(): MemorySample? = items.lastOrNull()

    @Synchronized
    fun size(): Int = items.size

    @Synchronized
    fun clear() = items.clear()
}

/** Reads the few numbers out of `/proc` text. Pure, so the formats are unit tested. */
object ProcMemoryParser {
    /** `Threads:\t42` from `/proc/self/status`. */
    fun threads(status: String): Int? = status.lineSequence()
        .firstOrNull { it.startsWith("Threads:") }
        ?.substringAfter(':')?.trim()?.toIntOrNull()

    /** `Pss:    12345 kB` from `/proc/self/smaps_rollup`, in kB. */
    fun pssKb(smapsRollup: String): Long? = kbOf(smapsRollup, "Pss:")

    /** `MemAvailable:  123456 kB` from `/proc/meminfo`, in kB. */
    fun memAvailableKb(meminfo: String): Long? = kbOf(meminfo, "MemAvailable:")

    private fun kbOf(text: String, label: String): Long? = text.lineSequence()
        .firstOrNull { it.startsWith(label) }
        ?.substringAfter(':')?.trim()?.substringBefore(' ')?.toLongOrNull()

    fun kbToMb(kb: Long): Int = (kb / 1024L).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
}

/** The memory context an exit, crash or report carries. */
object MemoryContext {
    /**
     * For `app_exit`, which describes the PREVIOUS run: the persisted compact sample when there is one,
     * else whatever the run's older breadcrumbs recorded (PSS / heap / fds), so a run that never played
     * still says something. Never invents avail or threads.
     */
    fun forExit(
        compact: String?,
        pssKb: Long?,
        javaKb: Long?,
        nativeKb: Long?,
        fds: Int?,
    ): Map<String, Any> {
        MemorySample.fromCompact(compact)?.let { return it.toProperties() }
        if (pssKb == null && javaKb == null && nativeKb == null && fds == null) return emptyMap()
        return buildMap {
            pssKb?.let { put("mem_pss_mb", ProcMemoryParser.kbToMb(it)) }
            javaKb?.let { put("mem_java_mb", ProcMemoryParser.kbToMb(it)) }
            nativeKb?.let { put("mem_native_mb", ProcMemoryParser.kbToMb(it)) }
            fds?.let { put("mem_fds", it) }
        }
    }

    /** `prev_*` for `app_exit` from the previous run's breadcrumb state. */
    fun previousRun(
        wasPlaying: Boolean,
        kind: String?,
        surface: String?,
        engine: String?,
        tiles: Int,
    ): Map<String, Any> {
        val inMultiview = tiles > 0
        val playing = wasPlaying || inMultiview
        return buildMap {
            put("prev_playing", playing)
            put("prev_tiles", tiles.coerceAtLeast(0))
            if (!playing) return@buildMap
            val playKind = when {
                inMultiview -> PlayKind.MULTIVIEW_TILE
                surface == "preview" -> PlayKind.PREVIEW
                kind == "live" -> PlayKind.LIVE
                kind == "vod" -> PlayKind.VOD
                else -> null
            }
            playKind?.let { put("prev_kind", it.wire) }
            put("prev_engine", (if (inMultiview && engine == null) PlayEngine.MEDIA3 else PlayEngine.fromLabel(engine)).wire)
        }
    }
}
