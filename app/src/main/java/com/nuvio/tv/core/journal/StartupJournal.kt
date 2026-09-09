package com.nuvio.tv.core.journal

import com.nuvio.tv.BuildConfig
import kotlin.concurrent.Volatile

/** The durable-persistence seam the controller depends on. A production adapter wraps the TV
 *  [StartupJournalStore]; tests supply an in-memory fake with a write-failure toggle. */
internal interface JournalStore {
    fun read(): String?
    fun writeVerified(content: String): Boolean
}

/**
 * All journal + recovery-gate logic, parameterized on a [JournalStore], a clock, and a build id, so
 * the whole lifecycle is driven by integration tests with an in-memory store and a controllable
 * clock — no process/OOM/heap required. Twin of the KMP `StartupJournalController`.
 *
 * CONCURRENCY: read-modify-write is serialized by one monitor; the generation guard in
 * [StartupJournalPolicy.record] is an ADDITIONAL protection against a stale completion, not the only
 * one. All entry points (UI, worker, JobService) share the one app process (no `android:process`).
 *
 * PERSISTENCE: read-back proves the bytes are written and re-readable now; power-loss durability
 * additionally depends on the store's fsync/atomic-rename. The threat targeted is PROCESS DEATH.
 */
internal class StartupJournalController(
    private val store: JournalStore,
    private val clock: () -> Long,
    private val buildId: () -> String,
) {
    private val lock = Any()

    @Volatile private var initialized = false
    @Volatile private var healthy = false
    @Volatile private var runId = 0L
    private var journal = Journal()

    @Volatile private var gated = false
    @Volatile private var safeMode = false
    @Volatile private var uiLaunchMarked = false
    @Volatile private var startupToken: Long? = null

    val isHealthy: Boolean get() { ensureInitialized(); return healthy }
    val currentRunId: Long get() { ensureInitialized(); return runId }
    val isSafeMode: Boolean get() { ensureInitialized(); return safeMode }

    fun ensureInitialized() {
        if (initialized) return
        synchronized(lock) {
            if (initialized) return
            val raw = runCatching { store.read() }.getOrNull()
            val (advanced, newRun) = StartupJournalPolicy.startRun(StartupJournalPolicy.decode(raw), clock())
            runId = newRun
            journal = advanced
            healthy = persistLocked(advanced)
            initialized = true
        }
    }

    private fun persistLocked(j: Journal): Boolean =
        runCatching { store.writeVerified(StartupJournalPolicy.encode(j)) }.getOrDefault(false)

    fun beginAttempt(op: String, subject: String): Long? {
        ensureInitialized()
        synchronized(lock) {
            if (!healthy) return null
            val (updated, generation) = StartupJournalPolicy.begin(journal, op, subject, runId, buildId(), clock())
            if (!persistLocked(updated)) {
                healthy = false
                return null
            }
            journal = updated
            return generation
        }
    }

    fun record(op: String, subject: String, token: Long, outcome: JournalOutcome) {
        synchronized(lock) {
            val updated = StartupJournalPolicy.record(journal, op, subject, token, outcome, clock())
            if (updated !== journal) {
                journal = updated
                persistLocked(updated)
            }
        }
    }

    fun shouldDefer(op: String, subject: String): Boolean {
        ensureInitialized()
        synchronized(lock) { return StartupJournalPolicy.shouldDefer(journal, op, subject, clock()) }
    }

    fun interruptCount(op: String, subject: String): Int {
        ensureInitialized()
        synchronized(lock) { return StartupJournalPolicy.interruptCount(journal, op, subject) }
    }

    /** DECISION ONLY — safe on ANY process start incl. a headless worker/JobService run. Sweeps +
     *  advances the run, decides safe mode from prior consecutive PRE-INTERACTIVE interruptions, and
     *  opens NO attempt (so a headless run never accumulates a failed startup_interactive). */
    fun decideStartupMode(): Boolean {
        ensureInitialized()
        synchronized(lock) {
            if (gated) return safeMode
            gated = true
            safeMode = StartupJournalPolicy.shouldEnterSafeMode(
                StartupJournalPolicy.interruptCount(journal, StartupJournalPolicy.OP_STARTUP_INTERACTIVE, StartupJournalPolicy.SUBJECT_NONE),
            )
            return safeMode
        }
    }

    /** Call ONLY from a real UI-launch entry point (MainActivity), once per process. Opens the
     *  "reached interactive" attempt. Headless runs never call this. Idempotent per process. */
    fun markUiLaunchStarted() {
        decideStartupMode()
        synchronized(lock) {
            if (uiLaunchMarked) return
            uiLaunchMarked = true
        }
        startupToken = beginAttempt(StartupJournalPolicy.OP_STARTUP_INTERACTIVE, StartupJournalPolicy.SUBJECT_NONE)
    }

    /** READINESS: first real navigable screen route composed. Records COMPLETED (resets the loop
     *  counter for the NEXT launch) but does NOT clear [isSafeMode] for the current run. Idempotent. */
    fun markInteractiveReached() {
        val token = startupToken ?: return
        startupToken = null
        record(StartupJournalPolicy.OP_STARTUP_INTERACTIVE, StartupJournalPolicy.SUBJECT_NONE, token, JournalOutcome.COMPLETED)
    }
}

/** Process-wide singleton over one controller. Production call sites use these names unchanged. */
internal object StartupJournal {
    const val OP_INDEX_BUILD = StartupJournalPolicy.OP_INDEX_BUILD
    const val OP_STARTUP_INTERACTIVE = StartupJournalPolicy.OP_STARTUP_INTERACTIVE
    const val SUBJECT_NONE = StartupJournalPolicy.SUBJECT_NONE

    private val impl = StartupJournalController(
        store = ProductionJournalStore,
        clock = { System.currentTimeMillis() },
        buildId = { BuildConfig.VERSION_NAME.ifBlank { "dev" }.take(48) },
    )

    val isHealthy: Boolean get() = impl.isHealthy
    val currentRunId: Long get() = impl.currentRunId
    val isSafeMode: Boolean get() = impl.isSafeMode

    fun ensureInitialized() = impl.ensureInitialized()
    fun beginAttempt(op: String, subject: String): Long? = impl.beginAttempt(op, subject)
    fun record(op: String, subject: String, token: Long, outcome: JournalOutcome) = impl.record(op, subject, token, outcome)
    fun shouldDefer(op: String, subject: String): Boolean = impl.shouldDefer(op, subject)
    fun interruptCount(op: String, subject: String): Int = impl.interruptCount(op, subject)
    fun decideStartupMode(): Boolean = impl.decideStartupMode()
    fun markUiLaunchStarted() = impl.markUiLaunchStarted()
    fun markInteractiveReached() = impl.markInteractiveReached()
}

private object ProductionJournalStore : JournalStore {
    override fun read(): String? = StartupJournalStore.read()
    override fun writeVerified(content: String): Boolean = StartupJournalStore.writeVerified(content)
}
