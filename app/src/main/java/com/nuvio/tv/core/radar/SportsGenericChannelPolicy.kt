package com.nuvio.tv.core.radar

/**
 * B141: which channels are "generic sports channels" — guide-probe candidates when a fixture's name
 * matched nothing better. They never pad displayed results; they only earn an EPG probe.
 *
 * The matcher ran this over the WHOLE live lineup on every fixture open, and normalised the channel
 * name inside the per-marker loop: up to [MARKERS].size (12) full normalisations per channel that
 * carries no marker, i.e. almost every channel of a 26k-row panel — more CPU than the name-matching
 * pass itself. One normalisation per channel gives the same answer.
 */
internal object SportsGenericChannelPolicy {
    /** Compared against normalised names — punctuation is already stripped. */
    val MARKERS = listOf(
        "sport", "espn", "bein", "dazn", "eurosport", "supersport", "fox sports",
        "sky sports", "tnt sports", "arena", "setanta", "premier sports",
    )

    fun <T> genericSportChannels(
        candidates: List<T>,
        name: (T) -> String,
        normalize: (String) -> String = SportsEventMatchEngine::normalize,
    ): List<T> = candidates.filter { c ->
        val normalized = normalize(name(c))
        MARKERS.any { marker -> normalized.contains(marker) }
    }
}
