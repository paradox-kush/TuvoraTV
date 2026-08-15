package com.nuvio.tv.core.memory

/**
 * Transient pressure escalation with anti-flap (twin of the mobile/desktop governor).
 *
 * Android has NO foreground pressure signal since 14 (only TRIM_MEMORY_UI_HIDDEN /
 * BACKGROUND still fire, and those trim the registry directly) — nothing feeds this
 * on TV *today*. It ships anyway because it is the pinned semantics the self-measured
 * foreground source (getMemoryInfo polling / registry arithmetic) will feed next phase,
 * and the policy must stay identical across the repos.
 *
 * Pinned semantics (see MemoryTierPolicyTest):
 *  - escalation needs 2+ CONSECUTIVE pressure samples — a lone sample never changes the tier;
 *  - once escalated, the effective tier is one level below base and HOLDS for [holdMs] after
 *    the last pressure sample — a relax sample inside the hold only resets the counter;
 *  - after the hold expires the tier relaxes back to base.
 */
internal class MemoryPressureGovernor(
    private val baseTier: MemoryTier,
    private val holdMs: Long = DEFAULT_HOLD_MS,
) {

    private var consecutivePressure = 0
    private var escalatedUntilMs = Long.MIN_VALUE

    fun onPressure(nowMs: Long) {
        consecutivePressure++
        if (consecutivePressure >= 2 || isEscalated(nowMs)) {
            // 2+ consecutive samples escalate; further samples (even after a relax reset
            // the streak) extend an escalation that is still holding.
            escalatedUntilMs = nowMs + holdMs
        }
    }

    fun onRelax(nowMs: Long) {
        // Breaks the pressure streak. Never relaxes early — the time hold does that.
        consecutivePressure = 0
    }

    fun effectiveTier(nowMs: Long): MemoryTier =
        if (isEscalated(nowMs)) baseTier.escalated() else baseTier

    private fun isEscalated(nowMs: Long): Boolean = nowMs <= escalatedUntilMs

    companion object {
        const val DEFAULT_HOLD_MS = 30_000L
    }
}
