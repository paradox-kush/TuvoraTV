package com.nuvio.tv.core.radar.alerts

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The channels being played right now, published by whoever plays: the clean live player (one channel) and multiview
 * (every tile). Each publisher clears only what IT set, so one player leaving never erases another's channel.
 */
@Singleton
class SportsAlertPlayingChannels @Inject constructor() {
    private val state = MutableStateFlow<Set<String>>(emptySet())
    val ids: StateFlow<Set<String>> = state.asStateFlow()

    // Atomic: two players (clean live + a multiview tile) can publish at the same moment.
    fun set(id: String) { state.update { it + id } }

    fun clear(id: String) { state.update { it - id } }
}

/**
 * Per-game answers of the "which channel carries this game?" lookup, cached for [ttlMs] (15 min), negatives included
 * ("no match", "no playlist"), so a burst of goals never repeats the lookup. Pure; the clock is passed in.
 */
class WatchResultCache(private val ttlMs: Long = DEFAULT_TTL_MS) {
    private class Entry(val ids: List<String>, val atMs: Long)

    private val entries = HashMap<String, Entry>()

    @Synchronized fun putFound(eventId: String, matchedIds: List<String>, nowMs: Long) {
        entries[eventId] = Entry(matchedIds.distinct(), nowMs)
    }

    @Synchronized fun putNone(eventId: String, nowMs: Long) {
        entries[eventId] = Entry(emptyList(), nowMs)
    }

    /** Unknown when never asked or expired; [WatchState.Channel] carries every matched channel of the game. */
    @Synchronized fun stateFor(eventId: String, @Suppress("UNUSED_PARAMETER") playing: Set<String>, nowMs: Long): WatchState {
        val e = entries[eventId]?.takeIf { nowMs - it.atMs < ttlMs } ?: return WatchState.Unknown
        if (e.ids.isEmpty()) return WatchState.NoChannel
        return WatchState.Channel(e.ids.toSet())
    }

    @Synchronized fun clear() { entries.clear() }

    /** Every cached, unexpired game's state against the channels playing now (the policy's `watchStates`). */
    @Synchronized fun statesFor(playing: Set<String>, nowMs: Long): Map<String, WatchState> {
        entries.entries.removeAll { nowMs - it.value.atMs >= ttlMs }
        return entries.keys.associateWith { stateFor(it, playing, nowMs) }
    }

    companion object {
        const val DEFAULT_TTL_MS = 15 * 60 * 1000L
    }
}

object SportsAlertWatchPolicy {
    /** A Watch lookup is for Score/Start banners only; Final (and summary) banners offer Open game and nothing else. */
    fun bannerLookupWanted(banner: AlertBanner): Boolean =
        banner.eventId != null && (banner.kind == AlertKind.Score || banner.kind == AlertKind.Start)
}
