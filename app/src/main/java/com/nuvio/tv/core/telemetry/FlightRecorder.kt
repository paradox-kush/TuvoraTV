package com.nuvio.tv.core.telemetry

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock

/**
 * Bounded on-device log of the last few minutes. One owner: every access goes through [lock],
 * and readers copy under it. Entries are sanitized on the way in, so nothing unsafe is ever held.
 */
class FlightRecorder(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = ReentrantLock()
    private val ring = ArrayDeque<DiagEntry>(capacity)

    /** Bumped on every write; the persister writes only when this moved. */
    val version = AtomicLong(0)

    fun record(area: DiagArea, code: String, fields: Map<String, Any?> = emptyMap()) {
        record(DiagEntry(clock(), area, DiagTrailPolicy.safeCode(code), DiagTrailPolicy.sanitize(fields)))
    }

    fun record(entry: DiagEntry) {
        val safe = entry.copy(code = DiagTrailPolicy.safeCode(entry.code), fields = DiagTrailPolicy.sanitize(entry.fields))
        lock.lock()
        try {
            if (ring.size >= capacity) ring.removeFirst()
            ring.addLast(safe)
            version.incrementAndGet()
        } finally {
            lock.unlock()
        }
    }

    fun snapshot(max: Int = capacity): List<DiagEntry> {
        lock.lock()
        try {
            return ring.toList().takeLast(max)
        } finally {
            lock.unlock()
        }
    }

    /**
     * For the crash handler: never blocks the dying process for long. Returns an empty list if the
     * lock is held by another thread past [timeoutMs].
     */
    fun snapshotOrEmpty(max: Int, timeoutMs: Long = 50): List<DiagEntry> {
        if (!lock.tryLock(timeoutMs, TimeUnit.MILLISECONDS)) return emptyList()
        try {
            return ring.toList().takeLast(max)
        } finally {
            lock.unlock()
        }
    }

    fun restore(entries: List<DiagEntry>) {
        lock.lock()
        try {
            ring.clear()
            entries.takeLast(capacity).forEach { ring.addLast(it) }
            version.incrementAndGet()
        } finally {
            lock.unlock()
        }
    }

    companion object {
        const val DEFAULT_CAPACITY = 200
    }
}
