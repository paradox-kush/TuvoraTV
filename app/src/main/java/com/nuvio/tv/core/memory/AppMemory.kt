package com.nuvio.tv.core.memory

import android.os.SystemClock

/**
 * App-wide memory state (twin of the mobile/desktop AppMemory): the base [MemoryTier]
 * (set once by [AndroidMemoryTierProbe] at startup), the transient pressure escalation
 * over it, and the [BudgetRegistry] every in-memory cache registers with.
 *
 * Android has no foreground pressure signal since 14 — the two surviving trim callbacks
 * (UI_HIDDEN/BACKGROUND) call [trimCaches] from NuvioApplication; [onPressure]/[onRelax]
 * wait for the self-measured foreground source (next phase).
 */
internal object AppMemory {

    val registry = BudgetRegistry()

    private val lock = Any()
    private var base = MemoryTier.HIGH
    private var governor = MemoryPressureGovernor(base)

    private fun nowMs(): Long = SystemClock.elapsedRealtime()

    /** Called once by the probe at startup; replaces the governor's base. */
    fun setBaseTier(tier: MemoryTier) = synchronized(lock) {
        if (tier != base) {
            base = tier
            governor = MemoryPressureGovernor(tier)
        }
    }

    /** The probe's resting tier — sizes fixed-at-creation budgets (image caches, buffers). */
    fun baseTier(): MemoryTier = synchronized(lock) { base }

    /**
     * The tier consumers should size NEW work by right now: base, dropped one level while
     * a pressure escalation holds (anti-flap semantics pinned in MemoryTierPolicyTest).
     */
    fun effectiveTier(): MemoryTier = synchronized(lock) { governor.effectiveTier(nowMs()) }

    /**
     * A pressure signal. Trims every registered cache immediately — truth is on disk, so
     * trimming on the FIRST sample is safe — while the effective tier only escalates on
     * the governor's 2+-consecutive rule.
     */
    fun onPressure() {
        synchronized(lock) { governor.onPressure(nowMs()) }
        registry.trimAll()
    }

    /** The all-clear. Never relaxes the hold early. */
    fun onRelax() {
        synchronized(lock) { governor.onRelax(nowMs()) }
    }

    /** Drops every registered cache (the UI_HIDDEN/BACKGROUND trim hook). */
    fun trimCaches() = registry.trimAll()
}
