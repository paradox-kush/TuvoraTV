package com.nuvio.tv.core.telemetry

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Counts the routine successes that used to be one event each, and drains them as one
 * `session_health` event. Keeps "outcomes are reported, so a regression shows within a day"
 * as rates, without the per-event volume.
 */
class SessionHealth {
    private val counts = ConcurrentHashMap<String, AtomicInteger>()

    /** Builds `event:outcome[:surface]`, identifier-safe and bounded in cardinality. */
    fun key(event: String, properties: Map<String, Any?>): String {
        val outcome = (properties["outcome"] as? String) ?: (properties["kind"] as? String)
        val surface = properties["surface"] as? String
        val source = properties["source_type"] as? String
        return listOfNotNull(event, outcome, surface, source)
            .joinToString(":") { DiagTrailPolicy.safeCode(it) }
            .take(80)
    }

    fun increment(key: String) {
        if (counts.size >= MAX_KEYS && !counts.containsKey(key)) return
        counts.getOrPut(key) { AtomicInteger() }.incrementAndGet()
    }

    /** Returns the counts since the last drain and clears them. Empty means nothing to send. */
    fun drain(): Map<String, Int> {
        if (counts.isEmpty()) return emptyMap()
        val out = HashMap<String, Int>()
        for ((k, v) in counts) {
            val n = v.getAndSet(0)
            if (n > 0) out[k] = n
        }
        counts.entries.removeIf { it.value.get() == 0 }
        return out
    }

    companion object {
        const val MAX_KEYS = 64
        const val EVENT = "session_health"
    }
}
