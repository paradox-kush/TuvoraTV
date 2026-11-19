package com.nuvio.tv.core.iptv

/**
 * "You keep coming back to these 4": which Live TV channels to offer to pin. Pure (no clock, no storage), so the
 * rules test without the guide. The rules are shared with the phone app so both behave alike:
 *
 *  - a channel qualifies with at least [MIN_PLAYS] plays in the last [WINDOW_MS] (14 days);
 *  - channels the viewer already favourited (or pinned) are never suggested;
 *  - up to [MAX_SUGGESTED] (4) are suggested, most played first; nothing when fewer than [MIN_QUALIFYING] (2) qualify;
 *  - "Not now" snoozes the line for [SNOOZE_MS] (14 days); "Stop suggesting" (the switch off) is never.
 */
object PinSuggestionPolicy {
    const val DAY_MS = 24L * 60 * 60 * 1000
    const val WINDOW_MS = 14 * DAY_MS
    const val SNOOZE_MS = 14 * DAY_MS
    const val MIN_PLAYS = 3
    const val MIN_QUALIFYING = 2
    const val MAX_SUGGESTED = 4

    /** The viewer's two controls. [enabled] false = never; [snoozedUntilMs] = hidden until then ("Not now"). */
    data class Prefs(val enabled: Boolean = true, val snoozedUntilMs: Long = 0L)

    /**
     * Channel ids to suggest, best first, or empty. [plays] maps a channel id to its play times (epoch ms);
     * [excluded] holds ids that must not be suggested (favourites, already pinned).
     */
    fun suggest(
        plays: Map<String, List<Long>>,
        excluded: Set<String>,
        prefs: Prefs,
        nowMs: Long,
    ): List<String> {
        if (!prefs.enabled || nowMs < prefs.snoozedUntilMs) return emptyList()
        val cutoff = nowMs - WINDOW_MS
        val qualifying = plays.mapNotNull { (id, times) ->
            if (id in excluded) return@mapNotNull null
            val recent = times.filter { it in cutoff..nowMs }
            if (recent.size < MIN_PLAYS) null else Ranked(id, recent.size, recent.max())
        }
        if (qualifying.size < MIN_QUALIFYING) return emptyList()
        return qualifying
            .sortedWith(compareByDescending<Ranked> { it.count }.thenByDescending { it.latest }.thenBy { it.id })
            .take(MAX_SUGGESTED)
            .map { it.id }
    }

    /** When a "Not now" pressed at [nowMs] stops hiding the line. */
    fun snoozedUntil(nowMs: Long): Long = nowMs + SNOOZE_MS

    /**
     * [rows] with the [suggested] rows first (in their given order) and every other row after, once each.
     * A suggested row missing from [rows] (beyond the shown cap) is still listed.
     */
    fun <T> listedFirst(rows: List<T>, suggested: List<T>, idOf: (T) -> String): List<T> {
        if (suggested.isEmpty()) return rows
        val ids = suggested.map(idOf).toSet()
        return suggested + rows.filter { idOf(it) !in ids }
    }

    private data class Ranked(val id: String, val count: Int, val latest: Long)
}

/**
 * The on-device play log behind [PinSuggestionPolicy]: per channel, the times of its recent plays. Small and capped:
 * plays older than the policy window are dropped, [MAX_PLAYS_PER_CHANNEL] per channel, [MAX_CHANNELS] channels
 * (the least recently played go first). A play within [DEDUPE_MS] (10 minutes) of that channel's previous recorded play
 * counts as the same play (a retry, a reconnect, a re-tune); the apps share this rule.
 */
object PlayCountLog {
    const val MAX_CHANNELS = 100
    const val MAX_PLAYS_PER_CHANNEL = 10
    const val DEDUPE_MS = 10 * 60_000L

    fun record(log: Map<String, List<Long>>, channelId: String, nowMs: Long): Map<String, List<Long>> {
        if (channelId.isBlank()) return log
        val cutoff = nowMs - PinSuggestionPolicy.WINDOW_MS
        val pruned = LinkedHashMap<String, List<Long>>()
        for ((id, times) in log) {
            val kept = times.filter { it >= cutoff }
            if (kept.isNotEmpty()) pruned[id] = kept
        }
        val existing = pruned[channelId].orEmpty()
        val last = existing.lastOrNull()
        if (last != null && nowMs - last < DEDUPE_MS && nowMs >= last) return pruned
        pruned.remove(channelId)
        pruned[channelId] = (existing + nowMs).takeLast(MAX_PLAYS_PER_CHANNEL)
        if (pruned.size > MAX_CHANNELS) {
            val drop = pruned.entries
                .filter { it.key != channelId }
                .sortedBy { it.value.last() }
                .take(pruned.size - MAX_CHANNELS)
                .map { it.key }
            drop.forEach(pruned::remove)
        }
        return pruned
    }
}

/** "Pin all": the one place the suggestion turns into pins, through the overlay's existing channel pin. */
fun interface ChannelPinner {
    /** Pins all [targets] as ONE edit (one local write, one sync push). */
    fun pinChannels(targets: List<PinSuggestionActions.Target>)
}

object PinSuggestionActions {
    data class Target(val entityId: String, val playlistId: String?)

    /** Pins every target that has a durable identity in ONE batch; returns how many were pinned. */
    fun pinAll(targets: List<Target>, pinner: ChannelPinner): Int {
        val pinnable = targets.filter { it.entityId.isNotBlank() }
        if (pinnable.isNotEmpty()) pinner.pinChannels(pinnable)
        return pinnable.size
    }
}
