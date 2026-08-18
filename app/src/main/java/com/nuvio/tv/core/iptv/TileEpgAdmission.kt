package com.nuvio.tv.core.iptv

/**
 * Whether a per-tile EPG fetch is worth making at all, and whether queued work still belongs to
 * the world it was queued for.
 *
 * The mobile twin's TileEpgBacklog answers *what order* to fetch in. This answers the two questions ordering can't:
 *
 *  - **Should we ask this channel again yet?** A channel the panel has no guide for answers empty
 *    every time, and the guide asks again on every scroll past. On a lineup where the panel fills
 *    6% of `epg_channel_id` (Starshare, measured) that is a permanent stream of requests that can
 *    never succeed — the exact traffic that gets an IP blocked at a provider edge. iptvnator meets
 *    this with a 60 s per-stream `failureCooldownMs`; so do we.
 *  - **Is this queued work still for the current world?** Switching playlist, editing an account,
 *    or rebuilding the channel mapping makes every pending fetch answer a question nobody is
 *    asking any more. Each entry remembers the generation it was queued in, and a bumped
 *    generation retires the backlog rather than letting it drain into a screen that moved on.
 *    (iptvnator's `enqueueGeneration` guards the same race from the other end.)
 *
 * Pure and clock-injected so both rules are pinned by tests — the failure mode of getting either
 * wrong is invisible on screen and only shows up as traffic.
 */
class TileEpgAdmission(
    private val cooldownMs: Long = FAILURE_COOLDOWN_MS,
    private val cap: Int = MEMORY_CAP,
) {

    /** Insertion-ordered, so the oldest failure memory is the one evicted at [cap]. */
    private val failedAt = LinkedHashMap<String, Long>()
    private var generation = 0

    val currentGeneration: Int get() = generation

    /**
     * A channel that answered with nothing usable. Not an error path — the common case is a panel
     * that simply has no guide for this channel, and that is precisely what must not be re-asked
     * on every scroll past.
     */
    fun recordEmpty(key: String, nowMs: Long) {
        if (key !in failedAt && failedAt.size >= cap) {
            failedAt.remove(failedAt.keys.first())
        }
        failedAt[key] = nowMs
    }

    /** A channel that answered: forget any cooldown so a recovered panel is trusted immediately. */
    fun recordAnswered(key: String) {
        failedAt.remove(key)
    }

    /** Whether the request is worth making now. */
    fun admits(key: String, nowMs: Long): Boolean {
        val last = failedAt[key] ?: return true
        // A clock that jumped backwards must not pin a channel shut until it catches up.
        if (nowMs < last) return true
        return nowMs - last >= cooldownMs
    }

    /**
     * The world changed (playlist switch, account edit, mapping rebuild): everything queued was
     * for the old one. Failure memory goes too — a new mapping deserves a fresh verdict.
     */
    fun invalidate() {
        generation++
        failedAt.clear()
    }

    /** Whether work queued in [queuedGeneration] should still run. */
    fun accepts(queuedGeneration: Int): Boolean = queuedGeneration == generation

    companion object {
        /** Matches iptvnator's per-stream cooldown; long enough to stop a storm, short enough that
         *  a panel recovering mid-session is picked up without a restart. */
        const val FAILURE_COOLDOWN_MS = 60_000L

        /** Channels remembered before insertion-order eviction — same ceiling as the ladder's. */
        const val MEMORY_CAP = 2_000
    }
}
