package com.nuvio.tv.core.epg

/**
 * When does one IPTV account's channel→EPG mapping get (re)computed?
 *
 * Matching is the mirror's expensive step — ~1.2ms/channel across the fuzzy tier meant a
 * 49k-channel household paid 60s of pegged CPU whenever it ran (research/tv-epg-mirror-spin.md),
 * and it used to run for EVERY account on every mirror generation bump, plus on every surface
 * visit for an account whose channel fetch had failed.
 *
 * The scheduling rule follows from what matching actually IS: a pure function of
 * (channel names, index contents). **If neither input changed, re-running it cannot produce a
 * different answer** — so a re-match is not merely wasteful, it is provably pointless. The
 * index's identity is the mirror's `generatedAt`, so that string is the causal trigger:
 *
 *  - a never-mapped account maps immediately (the product-value path), but failures retry on
 *    [ATTEMPT_COOLDOWN_MS] instead of on every visit;
 *  - a mapped account re-matches ONLY when the index generation differs from the one it was
 *    matched against — and at most one such account per sync, so a bump that touches every
 *    account spreads across visits instead of stacking one foreground episode;
 *  - same generation ⇒ zero work, no matter how many times a surface asks;
 *  - `force` (explicit user refresh) re-matches unconditionally.
 *
 * (An earlier revision used a 7-day age TTL. Age is not the causal input — a week-old mapping
 * against an unchanged index is still exactly right, and a one-hour-old mapping against a
 * freshly published index is stale. Generation replaced it.)
 *
 * "Mapped" means a match run COMPLETED ([mappedGeneration] non-empty), not that rows exist —
 * an account whose channels are all 24/7-style entries legitimately matches zero rows and must
 * not be treated as missing forever.
 */
object EpgRemapPolicy {

    /** Retry window after a failed map attempt (fetch failed / no channels). */
    const val ATTEMPT_COOLDOWN_MS: Long = 6 * 60 * 60 * 1000L

    enum class Decision { REMATCH, SKIP }

    fun decide(
        nowMs: Long,
        force: Boolean,
        /** Index generation this account was last successfully matched against; empty = never. */
        mappedGeneration: String,
        /** Index generation available now; empty when the manifest didn't publish one. */
        currentGeneration: String,
        attemptedAtMs: Long,
        agedBudgetLeft: Boolean,
    ): Decision = when {
        force -> Decision.REMATCH
        mappedGeneration.isEmpty() ->
            if (nowMs - attemptedAtMs >= ATTEMPT_COOLDOWN_MS) Decision.REMATCH else Decision.SKIP
        // No generation published: nothing to compare, so treat the existing mapping as current
        // rather than re-matching on every sync.
        currentGeneration.isEmpty() -> Decision.SKIP
        mappedGeneration != currentGeneration && agedBudgetLeft -> Decision.REMATCH
        else -> Decision.SKIP
    }
}
