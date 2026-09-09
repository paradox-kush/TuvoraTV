package com.nuvio.tv.core.journal

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Twin of NuvioMobile/NuvioDesktop `StartupJournalPolicy` — hand-ported (TV is a separate codebase).
 * Pure decision layer for the startup journal: a single small, versioned, bounded record of risky
 * operation attempts and their outcomes, shared by the resolver backoff and the crash-loop recovery
 * gate. No storage/clock/coroutines here — the impure edges live in `StartupJournal` + `StartupJournalStore`.
 */
internal enum class JournalOutcome { COMPLETED, EXPECTED_FAILURE, CANCELLED, INTERRUPTED_UNKNOWN }

@Serializable
internal data class JournalEntry(
    val op: String,
    val subject: String,
    val generation: Long,
    val runId: Long,
    val build: String,
    val startedAtMs: Long,
    val outcome: JournalOutcome? = null,
    val interruptCount: Int = 0,
    val lastFailureMs: Long = 0L,
)

@Serializable
internal data class Journal(
    val version: Int = StartupJournalPolicy.VERSION,
    val lastRunId: Long = 0L,
    val entries: List<JournalEntry> = emptyList(),
)

internal object StartupJournalPolicy {
    const val VERSION = 1
    const val MAX_ENTRIES = 64

    const val OP_INDEX_BUILD = "xtream_index_build"
    const val OP_STARTUP_INTERACTIVE = "startup_interactive"
    const val SUBJECT_NONE = ""

    const val MAX_BLOB_CHARS = 131_072

    /** Byte cap for the file READ (before an in-memory string exists). > MAX_BLOB_CHARS so a valid
     *  UTF-8 blob always fits, while a corrupt/tampered file is rejected without fully loading. */
    const val MAX_BLOB_BYTES = 262_144

    val INTERRUPT_BACKOFF_MS = longArrayOf(0L, 60_000L, 5 * 60_000L, 15 * 60_000L, 60 * 60_000L)
    const val EXPECTED_FAILURE_BACKOFF_MS = 60L * 60_000L

    /** Consecutive pre-interactive interruptions before the recovery gate enters safe mode. Two, not
     *  one: a single unfinished startup is more often a reboot / OS reclaim / user force-stop than a
     *  code fault, and must not gate optional features on that alone. */
    const val SAFE_MODE_THRESHOLD = 2

    fun shouldEnterSafeMode(startupInterruptCount: Int): Boolean = startupInterruptCount >= SAFE_MODE_THRESHOLD

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(j: Journal): String = json.encodeToString(Journal.serializer(), j)

    fun decode(raw: String?): Journal {
        if (raw == null || raw.length > MAX_BLOB_CHARS) return Journal()
        val parsed = runCatching { json.decodeFromString(Journal.serializer(), raw) }.getOrNull() ?: return Journal()
        if (parsed.version != VERSION) return Journal(version = VERSION, lastRunId = parsed.lastRunId)
        return parsed.copy(entries = capEntries(parsed.entries))
    }

    private fun capEntries(entries: List<JournalEntry>): List<JournalEntry> =
        if (entries.size <= MAX_ENTRIES) entries else entries.sortedByDescending { it.startedAtMs }.take(MAX_ENTRIES)

    private fun List<JournalEntry>.find(op: String, subject: String) =
        firstOrNull { it.op == op && it.subject == subject }

    fun startRun(j: Journal, nowMs: Long): Pair<Journal, Long> {
        val runId = j.lastRunId + 1
        val swept = j.entries.map { e ->
            if (e.outcome == null && e.runId < runId) {
                e.copy(outcome = JournalOutcome.INTERRUPTED_UNKNOWN, interruptCount = e.interruptCount + 1, lastFailureMs = nowMs)
            } else {
                e
            }
        }
        return j.copy(lastRunId = runId, entries = swept) to runId
    }

    fun begin(j: Journal, op: String, subject: String, runId: Long, build: String, nowMs: Long): Pair<Journal, Long> {
        val prev = j.entries.find(op, subject)
        val generation = (prev?.generation ?: 0L) + 1
        val entry = JournalEntry(
            op = op,
            subject = subject,
            generation = generation,
            runId = runId,
            build = build,
            startedAtMs = nowMs,
            outcome = null,
            interruptCount = prev?.interruptCount ?: 0,
            lastFailureMs = prev?.lastFailureMs ?: 0L,
        )
        val others = j.entries.filterNot { it.op == op && it.subject == subject }
        return j.copy(entries = capEntries(others + entry)) to generation
    }

    fun record(j: Journal, op: String, subject: String, generation: Long, outcome: JournalOutcome, nowMs: Long): Journal {
        val cur = j.entries.find(op, subject) ?: return j
        if (cur.generation != generation) return j
        val updated = when (outcome) {
            JournalOutcome.COMPLETED -> cur.copy(outcome = outcome, interruptCount = 0, lastFailureMs = 0L)
            JournalOutcome.EXPECTED_FAILURE -> cur.copy(outcome = outcome, lastFailureMs = nowMs)
            JournalOutcome.CANCELLED -> cur.copy(outcome = outcome)
            JournalOutcome.INTERRUPTED_UNKNOWN -> cur.copy(outcome = outcome, interruptCount = cur.interruptCount + 1, lastFailureMs = nowMs)
        }
        return j.copy(entries = j.entries.map { if (it.op == op && it.subject == subject) updated else it })
    }

    fun shouldDefer(j: Journal, op: String, subject: String, nowMs: Long): Boolean {
        val e = j.entries.find(op, subject) ?: return false
        return when (e.outcome) {
            JournalOutcome.INTERRUPTED_UNKNOWN -> nowMs - e.lastFailureMs < interruptBackoffMs(e.interruptCount)
            JournalOutcome.EXPECTED_FAILURE -> nowMs - e.lastFailureMs < EXPECTED_FAILURE_BACKOFF_MS
            else -> false
        }
    }

    fun interruptBackoffMs(count: Int): Long {
        if (count <= 0) return 0L
        return INTERRUPT_BACKOFF_MS[minOf(count, INTERRUPT_BACKOFF_MS.size - 1)]
    }

    fun interruptCount(j: Journal, op: String, subject: String): Int =
        j.entries.find(op, subject)?.takeIf { it.outcome == JournalOutcome.INTERRUPTED_UNKNOWN }?.interruptCount ?: 0
}
